/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.cloud

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.getSystemService
import ge.connect.sync.CompanionMessages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import java.util.UUID

/**
 * Janus Cloud (R36, shared/CLOUD.md): reaching the owner's Macs through their relay when they aren't on the same
 * network. The Mac hands over its keys over the LAN (`ge.connect.cloud:` ping ↔ companion notification); after its
 * `ready`, the phone signs in and keeps a WebSocket open while one of those Macs isn't reachable over the LAN (or a
 * cloud transfer is running), so nothing costs anything while they're together.
 */
object JanusCloud {
    private const val TAG = "JanusCloud"
    private const val CALLBACK_KEY = "ge.connect.cloud"
    private const val PREFIX = "ge.connect.cloud:"
    private const val LINGER_MILLIS = 60_000L
    private const val MAX_BACKOFF_MILLIS = 60_000L
    private const val MAX_AGE_MILLIS = 72 * 3_600_000L

    enum class Link { OFF, CONNECTING, CONNECTED, CLOCK, RECONNECT }

    private lateinit var app: Application
    private val worker by lazy { Handler(HandlerThread(TAG).apply { start() }.looper) }

    private val _peers = MutableStateFlow<List<CloudPeer>>(emptyList())
    /** The Macs set up for the cloud (ready or not). */
    val peers: StateFlow<List<CloudPeer>> = _peers.asStateFlow()
    private val _link = MutableStateFlow(Link.OFF)
    val link: StateFlow<Link> = _link.asStateFlow()

    @Volatile private var relay: Relay? = null
    // Worker thread only.
    private var socket: WebSocket? = null
    private var connecting = false
    private var lastNetwork: Network? = null
    private var backoff = 1_000L
    private var drainUntil = 0L
    private val helloSent = HashSet<String>()
    private val keys = HashMap<String, ByteArray>()

    fun start(app: Application) {
        this.app = app
        CloudStore.init(app)
        // A TV has no use for the cloud (files and messages with an away Mac): never connects, and keys from before
        // are dropped (R38; a Mac's {"op":"forget"} can't reach it, as a TV loads no pings).
        if (ge.connect.tv.TvDevices.thisIsTv) {
            CloudStore.peers().forEach { peer -> CloudStore.update(peer.kdeId) { null } }
            return
        }
        CloudTransfers.init(app)
        CloudCompanion.init(app)
        _peers.value = CloudStore.peers()
        drainUntil = System.currentTimeMillis() + LINGER_MILLIS // take what waited while the app wasn't running
        KdeConnect.getInstance().addDeviceListChangedCallback(CALLBACK_KEY) { worker.post(::devicesChanged) }
        app.getSystemService<ConnectivityManager>()?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = worker.post {
                backoff = 1_000L
                // A new network: the old socket may be dead without knowing it (relay pings only every 150 s).
                if (network != lastNetwork && lastNetwork != null && socket != null) {
                    Log.i(TAG, "Network changed; reconnecting to the relay")
                    socket?.cancel()
                    socket = null
                }
                lastNetwork = network
                evaluate()
            }.let { }
        })
        worker.post(::evaluate)
    }

    /** The Mac with KDE id [kdeId], if it's set up and ready for the cloud. */
    fun peer(kdeId: String): CloudPeer? = _peers.value.firstOrNull { it.kdeId == kdeId && it.ready }

    /** [device] isn't reachable over the LAN but is through the cloud. */
    fun canSend(device: Device): Boolean = !device.isReachable && device.isPaired && peer(device.deviceId) != null

    /** Files for [device] go through the cloud: it's away, or "Always use the cloud" (testing) is on. */
    fun useCloud(device: Device): Boolean = device.isPaired && peer(device.deviceId) != null && (!device.isReachable || CloudStore.force)

    var force: Boolean
        get() = CloudStore.force
        set(value) { CloudStore.force = value; worker.post(::evaluate) }

    // LAN handshake (§3) ----------------------------------------------------------------------------------------------

    /** `ge.connect.cloud:<json>` from a Mac. */
    fun handle(device: Device, json: String) = worker.post {
        val o = runCatching { JSONObject(json) }.getOrElse { return@post }
        when (o.optString("op")) {
            "hello" -> {
                val sign = o.optString("sign"); val box = o.optString("box"); val relayUrl = o.optString("relay")
                val signRaw = runCatching { CloudCrypto.unb64(sign) }.getOrNull()
                val boxRaw = runCatching { CloudCrypto.unb64(box) }.getOrNull()
                if (signRaw?.size != 32 || boxRaw?.size != 32 || !relayUrl.startsWith("https://")) {
                    Log.w(TAG, "Ignoring a malformed cloud hello from ${device.name}")
                    return@post
                }
                val id = CloudCrypto.deviceId(signRaw)
                if (o.has("id") && o.optString("id") != id) return@post // the id must match the key
                CloudStore.update(device.deviceId) {
                    CloudPeer(device.deviceId, id, o.optString("name").ifEmpty { device.name }, sign, box, relayUrl)
                }
                keys.remove(id)
                sendHello(device)
                peersChanged()
                Log.i(TAG, "${device.name} offered Janus Cloud")
            }
            "ready" -> {
                CloudStore.update(device.deviceId) { p ->
                    p?.copy(ready = true, stale = false, relay = o.optString("relay").takeIf { it.startsWith("https://") } ?: p.relay)
                }
                peersChanged()
                backoff = 1_000L
                drainUntil = System.currentTimeMillis() + LINGER_MILLIS
                Log.i(TAG, "${device.name} is ready for Janus Cloud")
                evaluate()
            }
            "forget" -> {
                CloudStore.update(device.deviceId) { null }
                peersChanged()
                Log.i(TAG, "${device.name} turned Janus Cloud off")
                evaluate()
            }
        }
    }

    /** Our keys to [device] (blocks on the worker thread). */
    private fun sendHello(device: Device) {
        val me = CloudStore.identity
        val text = JSONObject().put("op", "hello").put("id", me.id).put("name", org.kde.kdeconnect.helpers.DeviceHelper.getDeviceName(app))
            .put("sign", CloudCrypto.b64(me.signPublic)).put("box", CloudCrypto.b64(me.boxPublic)).toString()
        CompanionMessages.sendStatusNotification(app, device, PREFIX, "Cloud", text, silent = true)
        helloSent += device.deviceId
    }

    /** Turn the cloud off for this Mac from the phone. */
    fun turnOff(kdeId: String) = worker.post {
        val device = KdeConnect.getInstance().getDevice(kdeId)
        if (device?.isReachable == true) {
            CompanionMessages.sendStatusNotification(app, device, PREFIX, "Cloud", JSONObject().put("op", "forget").toString(), silent = true)
        }
        CloudStore.update(kdeId) { null }
        peersChanged()
        evaluate()
    }

    private fun devicesChanged() {
        val devices = KdeConnect.getInstance().devices
        helloSent.retainAll { devices[it]?.isReachable == true } // a new connection may need a new hello
        // The relay forgot us, or our keys were made anew: the Mac adds us again when it hears our hello.
        CloudStore.peers().filter { it.stale && it.kdeId !in helloSent }.forEach { p ->
            devices[p.kdeId]?.takeIf { it.isReachable && it.isPaired }?.let(::sendHello)
        }
        evaluate()
    }

    private fun peersChanged() {
        _peers.value = CloudStore.peers()
    }

    // The relay connection (§4–5, §9) ---------------------------------------------------------------------------------

    /** Wake the connection logic (a transfer started or ended). */
    fun poke() = worker.post(::evaluate)

    private fun evaluate() {
        worker.removeCallbacks(lingerCheck)
        ge.connect.sync.PhoneStatus.cloudChanged() // battery reports while a Mac is away (R37)
        val ready = CloudStore.peers().filter { it.ready }
        if (ready.isEmpty()) {
            close()
            relay = null
            _link.value = if (CloudStore.peers().any { it.stale }) Link.RECONNECT else Link.OFF
            return
        }
        val base = ready.first().relay
        if (relay?.base != base.trimEnd('/')) { close(); relay = Relay(base, CloudStore.identity) }
        val devices = KdeConnect.getInstance().devices
        val now = System.currentTimeMillis()
        val wanted = ready.any { devices[it.kdeId]?.isReachable != true } || CloudTransfers.busy() || now < drainUntil || CloudStore.force
        when {
            wanted && socket == null && !connecting -> connect()
            !wanted && socket != null -> close()
            !wanted -> if (_link.value == Link.CONNECTING) _link.value = Link.OFF
        }
        if (now < drainUntil) worker.postDelayed(lingerCheck, drainUntil - now + 100)
    }

    private val lingerCheck = Runnable { evaluate() }
    private val reconnect = Runnable { if (socket == null && !connecting) evaluate() }

    private fun connect() {
        val relay = relay ?: return
        connecting = true
        if (_link.value != Link.CLOCK) _link.value = Link.CONNECTING
        Thread {
            val ws = runCatching { relay.webSocket(listener) }
            worker.post {
                connecting = false
                ws.onSuccess { socket = it }.onFailure { failed(it) }
            }
        }.start()
    }

    private fun close() {
        if (socket != null) Log.i(TAG, "Closing the relay connection (the Mac is on the same network)")
        socket?.close(1000, null)
        socket = null
        if (_link.value == Link.CONNECTED || _link.value == Link.CONNECTING) _link.value = Link.OFF
    }

    private fun failed(e: Throwable) {
        socket = null
        val code = (e as? RelayException)?.code
        when (code) {
            "unknown_device" -> {
                // Removed from the relay (or it lost its data): only the Mac can add us again, over the LAN.
                Log.w(TAG, "The relay doesn't know this phone; asking the Mac to add it again")
                CloudStore.savePeers(CloudStore.peers().map { it.copy(ready = false, stale = true) })
                helloSent.clear()
                peersChanged()
                devicesChanged()
                return
            }
            "clock" -> _link.value = Link.CLOCK
            else -> if (_link.value != Link.CLOCK) _link.value = Link.CONNECTING
        }
        Log.i(TAG, "Relay connection failed (${e.message}); again in ${backoff / 1000} s")
        worker.removeCallbacks(reconnect)
        worker.postDelayed(reconnect, backoff)
        backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = worker.post {
            if (socket !== webSocket) return@post
            backoff = 1_000L
            _link.value = Link.CONNECTED
            Log.i(TAG, "Connected to the relay")
            // Like a new LAN connection: away Macs get our status (with battery) and so learn we're back (R37).
            val away = CloudCompanion.awayMacs()
            ge.connect.sync.DeviceProfile.cloudConnected(away) // so the Mac knows what we can do over the cloud (cloud2)
            away.forEach { ge.connect.sync.PhoneStatus.reportSoon(forceFor = it.deviceId) }
        }.let { }

        override fun onMessage(webSocket: WebSocket, text: String) = worker.post { received(text) }.let { }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended(webSocket, null)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            ended(webSocket, if (response?.code == 401) RelayException(401, null, "sign in again") else t)

        private fun ended(webSocket: WebSocket, t: Throwable?) = worker.post {
            if (socket !== webSocket) return@post
            socket = null
            if (t != null) failed(t) else { _link.value = Link.OFF; evaluate() }
        }.let { }
    }

    // Envelopes (§5) ------------------------------------------------------------------------------------------------

    private fun received(text: String) {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (m.optString("type") != "message") return
        val relayId = m.optString("id")
        val from = m.optString("from")
        val peer = CloudStore.peers().firstOrNull { it.cloudId == from && it.ready } ?: return ack(relayId)
        val plain = runCatching { CloudCrypto.open(m.optString("box"), key(peer), from, CloudStore.identity.id) }
            .getOrElse { Log.w(TAG, "An envelope from ${peer.name} didn't open"); return ack(relayId) }
        val e = runCatching { JSONObject(String(plain)) }.getOrElse { return ack(relayId) }
        val id = e.optString("id")
        if (id.isEmpty() || CloudStore.seen(id) || System.currentTimeMillis() - e.optLong("ts") > MAX_AGE_MILLIS) return ack(relayId)
        val handled = { CloudStore.markSeen(id); ack(relayId) }
        when (e.optString("t")) {
            "file" -> CloudTransfers.receive(peer, e) { worker.post(handled) }
            "file-done" -> { CloudTransfers.delivered(e.optString("blob")); handled() }
            "file-cancel" -> { CloudTransfers.cancelled(e.optString("blob")); handled() }
            "c", "text" -> { CloudCompanion.received(peer, e.optString("t"), e); handled() } // R37
            else -> handled()
        }
    }

    private fun ack(relayId: String) {
        if (relayId.isEmpty()) return
        if (socket?.send(JSONObject().put("type", "ack").put("id", relayId).toString()) == true) return
        val relay = relay ?: return
        Thread { runCatching { relay.call("DELETE", "/v1/messages/$relayId").close() } }.start()
    }

    private fun key(peer: CloudPeer): ByteArray = synchronized(keys) {
        keys.getOrPut(peer.cloudId) { CloudCrypto.pairKey(CloudStore.identity, CloudCrypto.unb64(peer.box), peer.cloudId) }
    }

    /** Seal and post an envelope to [peer]. Blocks. */
    fun send(peer: CloudPeer, type: String, fields: JSONObject) {
        val relay = relay ?: Relay(peer.relay, CloudStore.identity)
        val plain = fields.put("t", type).put("id", UUID.randomUUID().toString()).put("ts", System.currentTimeMillis())
        val box = CloudCrypto.seal(plain.toString().toByteArray(), key(peer), CloudStore.identity.id, peer.cloudId)
        relay.json("POST", "/v1/messages", JSONObject().put("to", peer.cloudId).put("box", box))
    }

    /** The relay client for transfers (made when needed). */
    fun relayFor(peer: CloudPeer): Relay = relay?.takeIf { it.base == peer.relay.trimEnd('/') } ?: Relay(peer.relay, CloudStore.identity).also { r ->
        worker.post { if (relay == null) relay = r }
    }
}
