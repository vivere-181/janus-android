/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.tv

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Google's Android TV Remote protocol (v2), the one the Google TV phone app uses: pairing on port 6467 (the TV shows a
 * six-character code), then remote control on 6466: keys as Android key codes, text into the TV's fields, app links,
 * and the TV's volume and power state. Built into Android TV and Google TV, so it works without anything on the TV.
 * Both ports are TLS with a client certificate (ours, RSA, kept in the app's files); pairing ties that certificate to
 * the TV. Message layouts as in androidtvremote2's pairingmessage.proto and remotemessage.proto.
 */
internal object Pb {
    class W {
        private val out = ByteArrayOutputStream()

        fun varint(value: Long): W {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) { out.write(v.toInt()); return this }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }

        fun int(field: Int, value: Long) = apply { varint((field shl 3).toLong()); varint(value) }
        fun bytes(field: Int, value: ByteArray) = apply { varint(((field shl 3) or 2).toLong()); varint(value.size.toLong()); out.write(value) }
        fun str(field: Int, value: String) = bytes(field, value.toByteArray())
        fun msg(field: Int, value: W) = bytes(field, value.toBytes())
        fun toBytes(): ByteArray = out.toByteArray()
    }

    /** A message's fields: varints as Long, length-delimited ones as ByteArray. */
    class R(bytes: ByteArray) {
        private val fields = HashMap<Int, MutableList<Any>>()

        init {
            var i = 0
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    val b = bytes[i++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }
            parse@ while (i < bytes.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> fields.getOrPut(field) { mutableListOf() }.add(varint())
                    2 -> {
                        val length = varint().toInt()
                        fields.getOrPut(field) { mutableListOf() }.add(bytes.copyOfRange(i, i + length))
                        i += length
                    }
                    1 -> i += 8
                    5 -> i += 4
                    else -> break@parse
                }
            }
        }

        fun has(field: Int) = fields.containsKey(field)
        fun long(field: Int) = fields[field]?.firstOrNull() as? Long
        fun bytes(field: Int) = fields[field]?.firstOrNull() as? ByteArray
        fun msg(field: Int) = bytes(field)?.let(::R)
        fun string(field: Int) = bytes(field)?.toString(Charsets.UTF_8)
    }

    fun write(out: OutputStream, message: W) {
        val bytes = message.toBytes()
        out.write(W().varint(bytes.size.toLong()).toBytes())
        out.write(bytes)
        out.flush()
    }

    fun read(input: InputStream): R {
        var length = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException("The TV closed the connection")
            length = length or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        val bytes = ByteArray(length.toInt())
        var read = 0
        while (read < bytes.size) {
            val n = input.read(bytes, read, bytes.size - read)
            if (n < 0) throw java.io.EOFException("The TV closed the connection")
            read += n
        }
        return R(bytes)
    }
}

/** Our side's certificate for the TV (RSA: the pairing secret is made from the RSA keys of both sides). */
internal object AtvIdentity {
    private const val FILE = "atv_remote.p12"
    private const val ALIAS = "janus"
    private val PASSWORD = "janus-atv".toCharArray()

    @Volatile private var keyStore: KeyStore? = null

    @Synchronized
    private fun keyStore(context: Context): KeyStore {
        keyStore?.let { return it }
        val file = File(context.filesDir, FILE)
        val store = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            file.inputStream().use { store.load(it, PASSWORD) }
        } else {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            // A name of its own: the TV seems to keep one pairing per certificate name (a Mac pairing with the same
            // name replaced the phone's). Certificates made before this keep "Janus remote".
            val id = org.kde.kdeconnect.helpers.DeviceHelper.getDeviceId(context)
            val name = X500Name("CN=Janus remote $id, O=Janus")
            val now = System.currentTimeMillis()
            val holder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 20L * 365 * 86_400_000L), name, pair.public,
            ).build(JcaContentSignerBuilder("SHA256WithRSA").build(pair.private))
            val certificate = JcaX509CertificateConverter().getCertificate(holder)
            store.load(null, null)
            store.setKeyEntry(ALIAS, pair.private, PASSWORD, arrayOf(certificate))
            file.outputStream().use { store.store(it, PASSWORD) }
        }
        keyStore = store
        return store
    }

    fun certificate(context: Context) = keyStore(context).getCertificate(ALIAS) as X509Certificate

    /** TLS with our certificate; the TV's own (self-signed) is accepted and checked by pairing instead. */
    fun socket(context: Context, host: String, port: Int, timeoutMillis: Int = 5_000): SSLSocket {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore(context), PASSWORD) }
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf(trustAll), null) }
        val raw = java.net.Socket()
        raw.connect(InetSocketAddress(host, port), timeoutMillis)
        val socket = ssl.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        socket.soTimeout = timeoutMillis
        socket.startHandshake()
        return socket
    }
}

/** Pairing (port 6467): request, options, configuration; the TV then shows a code, which [finish] turns into the secret. */
class AtvPairing(private val context: Context, host: String) : Closeable {
    private val socket = AtvIdentity.socket(context, host, PORT)
    private val input = socket.inputStream
    private val output = socket.outputStream

    private fun message() = Pb.W().int(1, 2).int(2, 200)

    private fun expect(field: Int): Pb.R {
        val reply = Pb.read(input)
        val status = reply.long(2) ?: 0
        if (status != 200L) throw IllegalStateException("The TV refused (status $status)")
        if (!reply.has(field)) throw IllegalStateException("Unexpected reply from the TV")
        return reply
    }

    /** Up to the code on the TV's screen. */
    fun start(name: String) {
        socket.soTimeout = 10_000
        Pb.write(output, message().msg(10, Pb.W().str(1, "Janus").str(2, name)))
        expect(11)
        val hex6 = Pb.W().int(1, 3).int(2, 6) // hexadecimal, six symbols
        Pb.write(output, message().msg(20, Pb.W().msg(1, hex6).int(3, 1))) // we're the input side
        expect(20)
        Pb.write(output, message().msg(30, Pb.W().msg(1, hex6).int(2, 1)))
        expect(31)
        socket.soTimeout = 0 // the owner types the code at their pace
    }

    /** @return false if [code] isn't the one on the TV (its first byte checks the rest) */
    fun finish(code: String): Boolean {
        val clean = code.trim().uppercase()
        if (!clean.matches(Regex("[0-9A-F]{6}"))) return false
        val client = AtvIdentity.certificate(context).publicKey as RSAPublicKey
        val server = socket.session.peerCertificates[0].publicKey as RSAPublicKey
        fun magnitude(n: BigInteger) = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        val nonce = clean.substring(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val check = clean.substring(0, 2).toInt(16).toByte()
        val secret = listOf(client to server, server to client).map { (a, b) ->
            MessageDigest.getInstance("SHA-256").run {
                update(magnitude(a.modulus)); update(magnitude(a.publicExponent))
                update(magnitude(b.modulus)); update(magnitude(b.publicExponent))
                update(nonce)
                digest()
            }
        }.firstOrNull { it[0] == check } ?: return false
        socket.soTimeout = 10_000
        Pb.write(output, message().msg(40, Pb.W().bytes(1, secret)))
        expect(41)
        return true
    }

    override fun close() = runCatching { socket.close() }.let { }

    companion object {
        const val PORT = 6467
    }
}

/**
 * The remote connection (port 6466). Answers the TV's configure/activate/ping, reports its volume and power, and
 * sends keys, text and app links. One per TV while its remote is on screen.
 */
class AtvRemote(private val context: Context, private val host: String, private val listener: Listener) : Closeable {
    interface Listener {
        fun onConnected()
        fun onVolume(level: Int, max: Int, muted: Boolean)
        fun onPower(on: Boolean)
        /** The TV opened a voice session (it shows that it's listening). */
        fun onVoiceSession(id: Long) = Unit
        /** The app in front on the TV (its package), whenever that changes. */
        fun onApp(packageName: String) = Unit
        /** Ended; [needsPairing] when the TV doesn't know us (yet). */
        fun onClosed(error: Throwable?, needsPairing: Boolean)
    }

    private val sender = Executors.newSingleThreadExecutor()
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var closed = false
    @Volatile private var imeCounter = 0L
    @Volatile private var fieldCounter = 0L
    @Volatile private var features = FEATURES

    fun start() = Thread({ run() }, "AtvRemote").start()

    private fun run() {
        var connected = false
        try {
            val s = AtvIdentity.socket(context, host, PORT)
            socket = s
            s.soTimeout = 0
            val input = s.inputStream
            while (!closed) {
                val message = Pb.read(input)
                when {
                    message.has(1) -> { // configure: tell it who we are, with what we support
                        // What both sides support (as androidtvremote2): the TV only offers voice if it's in here.
                        features = FEATURES and (message.msg(1)?.long(1) ?: FEATURES)
                        Log.i(TAG, "TV supports features ${message.msg(1)?.long(1)}, using $features")
                        val info = Pb.W().str(1, Build.MODEL).str(2, Build.MANUFACTURER).int(3, 1).str(4, "1")
                            .str(5, "io.github.vivere_181.janus").str(6, "1.0.0")
                        send(Pb.W().msg(1, Pb.W().int(1, features).msg(2, info)))
                    }
                    message.has(2) -> {
                        send(Pb.W().msg(2, Pb.W().int(1, features)))
                        if (!connected) { connected = true; listener.onConnected() }
                    }
                    message.has(8) -> send(Pb.W().msg(9, Pb.W().int(1, message.msg(8)?.long(1) ?: 0)))
                    message.has(21) -> message.msg(21)?.let { edit ->
                        imeCounter = edit.long(1) ?: imeCounter
                        fieldCounter = edit.long(2) ?: fieldCounter
                    }
                    // ime_key_inject: the TV's IME state, with the app in front (app_info.app_package).
                    message.has(20) -> message.msg(20)?.msg(1)?.string(12)?.takeIf { it.isNotEmpty() }?.let(listener::onApp)
                    message.has(50) -> message.msg(50)?.let { volume ->
                        listener.onVolume((volume.long(7) ?: 0).toInt(), (volume.long(6) ?: 0).toInt(), (volume.long(8) ?: 0) != 0L)
                    }
                    message.has(40) -> listener.onPower((message.msg(40)?.long(1) ?: 0) != 0L)
                    message.has(30) -> { Log.i(TAG, "Voice session ${message.msg(30)?.long(1)}"); listener.onVoiceSession(message.msg(30)?.long(1) ?: 0) }
                    message.has(31) || message.has(32) -> Log.i(TAG, "Voice message from the TV: ${if (message.has(31)) "payload" else "end"}")
                }
            }
            listener.onClosed(null, false)
        } catch (e: Exception) {
            if (closed) return
            // An unknown certificate is refused during the handshake or by closing right after it.
            val needsPairing = e is javax.net.ssl.SSLException || (!connected && e is java.io.EOFException)
            Log.i(TAG, "Remote connection ended: ${e.message}")
            listener.onClosed(e, needsPairing)
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun send(message: Pb.W) {
        sender.execute {
            runCatching { socket?.outputStream?.let { Pb.write(it, message) } }.onFailure { Log.i(TAG, "Couldn't send: ${it.message}") }
        }
    }

    /** An Android key code; [direction] 3 = press, 1/2 = start/end of a long press. */
    fun key(keyCode: Int, direction: Int = SHORT) = send(Pb.W().msg(10, Pb.W().int(1, keyCode.toLong()).int(2, direction.toLong())))

    /** Replaces what's in the TV's focused text field. */
    fun text(value: String) {
        val position = (value.length - 1).toLong().coerceAtLeast(0)
        val field = Pb.W().int(1, position).int(2, position).str(3, value)
        val edit = Pb.W().int(1, 1).msg(2, field)
        send(Pb.W().msg(21, Pb.W().int(1, imeCounter).int(2, fieldCounter).msg(3, edit)))
    }

    /**
     * Voice (as androidtvremote2): press Search; the TV answers with a session (Listener.onVoiceSession), which is
     * confirmed with [voiceBegin]; then speech in [voice] chunks, and [voiceEnd].
     */
    fun voiceBegin(session: Long) = send(Pb.W().msg(30, Pb.W().int(1, session)))

    /** Speech: 16-bit mono PCM at VOICE_RATE, in chunks of VOICE_CHUNK_MIN to VOICE_CHUNK_MAX bytes (else the TV hangs up). */
    fun voice(session: Long, samples: ByteArray) = send(Pb.W().msg(31, Pb.W().int(1, session).bytes(2, samples)))

    fun voiceEnd(session: Long) = send(Pb.W().msg(32, Pb.W().int(1, session)))

    /** Opens a link in the TV app that handles it (https://www.youtube.com/…, market://…). */
    fun open(link: String) = send(Pb.W().msg(90, Pb.W().str(1, link)))

    override fun close() {
        closed = true
        runCatching { socket?.close() }
        sender.shutdown()
    }

    companion object {
        private const val TAG = "AtvRemote"
        const val PORT = 6466
        /** What we support (androidtvremote2's 622: ping, key, IME, power, volume, app link). */
        private const val FEATURES = 622L
        const val SHORT = 3
        const val VOICE_RATE = 8_000
        const val VOICE_CHUNK_MIN = 8 * 1024
        const val VOICE_CHUNK_MAX = 20 * 1024
        const val START_LONG = 1
        const val END_LONG = 2
    }
}

/** Which TVs this phone is paired with for the remote, and where they were last seen. */
object AtvPairings {
    private const val PREFS = "ge_connect_atv_remote"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isPaired(context: Context, deviceId: String) = prefs(context).getBoolean("$deviceId/paired", false)
    fun setPaired(context: Context, deviceId: String, paired: Boolean) = prefs(context).edit { putBoolean("$deviceId/paired", paired) }
    fun host(context: Context, deviceId: String) = prefs(context).getString("$deviceId/host", null)
    fun setHost(context: Context, deviceId: String, host: String) = prefs(context).edit { putString("$deviceId/host", host) }
}
