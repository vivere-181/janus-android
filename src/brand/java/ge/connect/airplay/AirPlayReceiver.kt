/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.airplay

import android.content.Context
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import io.github.jqssun.airplay.bridge.LogListener
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import io.github.jqssun.airplay.renderer.AudioConfig
import io.github.jqssun.airplay.renderer.AudioRenderer
import io.github.jqssun.airplay.renderer.VideoRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.kde.kdeconnect.helpers.DeviceHelper
import java.net.NetworkInterface
import kotlin.math.roundToInt

/**
 * AirPlay on a TV (PROTOCOL.md (janus-mac docs) → AirPlay): a Mac (or iPhone, iPad) mirrors its screen to the TV with its own Screen
 * Mirroring, with sound, or plays just sound on the TV's speakers. The TV is announced under its Janus name for as long
 * as Janus runs there. The receiver is UxPlay with the Android parts of jqssun/android-airplay-server (src/airplay);
 * this is Janus's side of it: starting it, announcing it, and showing a mirrored screen in [MirrorActivity].
 */
object AirPlayReceiver : RaopCallbackHandler, LogListener {
    private const val TAG = "AirPlay"
    private const val PORT = 7000
    private val QHD = 2560 to 1440

    data class State(
        val running: Boolean = false,
        /** Senders connected (a Mac mirroring counts once it has connected). */
        val connections: Int = 0,
        /** A screen is being mirrored: its shape, width / height. */
        val mirroring: Float? = null,
        /** The name this TV has in Screen Mirroring. */
        val name: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var app: Context
    private var handle = 0L
    private var nsd: NsdManager? = null
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()
    private var multicast: WifiManager.MulticastLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val audio by lazy { app.getSystemService(AudioManager::class.java) }
    private val audioRenderer = AudioRenderer()
    private val videoRenderer by lazy { VideoRenderer(app) }

    /** On a TV, starts receiving (once); elsewhere nothing. */
    @Synchronized
    fun start(context: Context) {
        if (!DeviceHelper.isTv || handle != 0L) return
        app = context.applicationContext
        Thread({ runCatching { startServer() }.onFailure { Log.e(TAG, "Couldn't start", it) } }, "AirPlayStart").start()
    }

    @Synchronized
    private fun startServer() {
        if (handle != 0L) return
        val name = DeviceHelper.getDeviceName(app)
        multicast = app.getSystemService(WifiManager::class.java)?.createMulticastLock("janus_airplay")?.apply {
            setReferenceCounted(false); acquire()
        }
        // OpenSL ES (before Android 8.1) can't find these itself.
        NativeBridge.nativeSetDefaultStreamValues(
            audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
            audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0,
        )
        val handle = NativeBridge.nativeInit(this, hardwareAddress(), name, app.filesDir.resolve("airplay.pem").absolutePath, true, false)
        if (handle == 0L) { Log.e(TAG, "Native init failed"); release(); return }
        this.handle = handle
        // The receiver app's defaults, as tested on the TV.
        audioRenderer.attachEngine(handle)
        audioRenderer.updateConfig(AudioConfig(forceSwAlac = true, lowLatency = false))

        // Senders are asked for 1440p (the owner's choice over 4K, for smoothness) when the decoder can take it: TVs
        // whose UI is 1080p still show video at the panel's resolution (the decoder draws straight to the screen).
        val fps = 60
        val h265 = videoRenderer.selectDecoders(QHD.first, QHD.second, fps, true)
        val (panelWidth, panelHeight) = displaySize()
        val (maxWidth, maxHeight) = videoRenderer.maxResolution()
        val width = maxOf(panelWidth, QHD.first).coerceAtMost(maxWidth)
        val height = maxOf(panelHeight, QHD.second).coerceAtMost(maxHeight)
        videoRenderer.setResolution(width, height)
        NativeBridge.nativeSetH265Enabled(handle, h265)
        NativeBridge.nativeSetCodecs(handle, false, true) // AAC; no ALAC
        NativeBridge.nativeSetHlsEnabled(handle, false) // no "AirPlay video" (links played by the TV)
        NativeBridge.nativeSetAudioEnabled(handle, true) // also a speaker for music
        NativeBridge.nativeSetLang(handle, "", "", app.resources.configuration.locales.toLanguageTags().replace(',', ':'))
        NativeBridge.nativeSetPlist(handle, "maxFPS", fps)
        NativeBridge.nativeSetPlist(handle, "overscanned", 0)
        NativeBridge.nativeSetDisplaySize(handle, width, height, fps)

        // 7000 like Apple TVs; any free port if something else has it (the announcement says which).
        var port = NativeBridge.nativeStart(handle, PORT)
        if (port < 0) port = NativeBridge.nativeStart(handle, 0)
        if (port < 0) { Log.e(TAG, "Couldn't listen"); release(); return }

        val manager = app.getSystemService(NsdManager::class.java)
        nsd = manager
        announce(manager, NativeBridge.nativeGetRaopServiceName(handle) ?: name, "_raop._tcp", port, NativeBridge.nativeGetRaopTxtRecords(handle))
        announce(manager, NativeBridge.nativeGetServerName(handle) ?: name, "_airplay._tcp", port, NativeBridge.nativeGetAirplayTxtRecords(handle))
        _state.update { it.copy(running = true, name = name) }
        Log.i(TAG, "Receiving as \"$name\" on port $port, up to ${width}x$height (H.265: $h265)")
    }

    private fun announce(manager: NsdManager, name: String, type: String, port: Int, txt: Map<String, String>?) {
        val info = NsdServiceInfo().apply {
            serviceName = name; serviceType = type; this.port = port
            txt.orEmpty().forEach { (key, value) -> setAttribute(key, value) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.i(TAG, "Announced $type as ${info.serviceName}") }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.w(TAG, "Couldn't announce $type: $code") }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        registrations += listener
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    private fun release() {
        registrations.forEach { runCatching { nsd?.unregisterService(it) } }
        registrations.clear()
        audioRenderer.detachEngine()
        if (handle != 0L) { NativeBridge.nativeStop(handle); NativeBridge.nativeDestroy(handle); handle = 0L }
        multicast?.release(); multicast = null
        _state.value = State()
    }

    /** Where [MirrorActivity] shows the mirrored screen (the decoder keeps running without one). */
    fun setSurface(surface: Surface) = videoRenderer.setSurface(surface)
    fun clearSurface(surface: Surface) = videoRenderer.clearSurface(surface)

    // From the receiver (its threads).

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) = videoRenderer.feedFrame(data, ntpTimeNs, isH265)

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        if (w <= 0 || h <= 0) return
        videoRenderer.setResolution(w.toInt(), h.toInt())
        val showing = _state.value.mirroring != null
        _state.update { it.copy(mirroring = w / h) }
        if (!showing) MirrorActivity.open(app)
    }

    override fun onMirrorRunning(running: Boolean) {
        if (running) videoRenderer.startSession() else {
            videoRenderer.stopSession()
            _state.update { it.copy(mirroring = null) }
        }
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        audioRenderer.start()
        audioRenderer.setFormat(ct, spf)
    }

    override fun onConnectionInit() = _state.update { it.copy(connections = it.connections + 1) }

    override fun onConnectionDestroy() {
        val left = (_state.value.connections - 1).coerceAtLeast(0)
        _state.update { it.copy(connections = left, mirroring = if (left == 0) null else it.mirroring) }
        if (left == 0) {
            audioRenderer.stop() // the speakers aren't held while nobody plays
            main.post {
                // Senders turn their volume all the way down as they leave: the TV's goes back to where it was.
                if (volumeBefore >= 0) audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBefore, 0)
                volumeBefore = -1
            }
        }
    }

    private var volumeBefore = -1

    /** The sender's volume (-30…0 dB, -144 = mute) sets the TV's. */
    override fun onVolumeChange(volume: Float) {
        val fraction = if (volume <= -144f) 0f else ((volume + 30f) / 30f).coerceIn(0f, 1f)
        main.post {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val level = (fraction * max).roundToInt()
            volumeBefore = if (level == 0) (if (volumeBefore < 0) current else volumeBefore) else -1
            if (level != current) audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        }
    }

    override fun onClientVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (volume == 0) -144f else -30f + 30f * volume / max
    }

    override fun onAudioTeardown() = Unit
    override fun onConnectionReset(reason: Int) { Log.i(TAG, "Connection reset: $reason") }
    override fun onDisplayPin(pin: String) = Unit
    override fun onMetadata(data: ByteArray) = Unit
    override fun onCoverArt(data: ByteArray) = Unit
    override fun onProgress(start: Long, curr: Long, end: Long) = Unit
    override fun onDacpId(dacpId: String, activeRemote: String) = Unit
    override fun onVideoPlay(location: String, startPositionSeconds: Float) = Unit
    override fun onVideoScrub(positionSeconds: Float) = Unit
    override fun onVideoRate(rate: Float) = Unit
    override fun onVideoStop() = Unit
    override fun onVideoSessionPoll() = Unit
    override fun onLog(msg: String) { Log.i(TAG, msg) }

    /** The TV's panel, landscape (what senders are told to send at most). */
    private fun displaySize(): Pair<Int, Int> {
        val mode = app.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.mode
        val (w, h) = mode?.let { it.physicalWidth to it.physicalHeight } ?: (1920 to 1080)
        return maxOf(w, h) to minOf(w, h)
    }

    /**
     * The receiver's identity for senders: the network card's address, else a random one kept for good (senders pair
     * with it). Locally administered, so never someone else's.
     */
    private fun hardwareAddress(): ByteArray {
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.name.startsWith("wlan") || it.name.startsWith("eth") }
                .firstNotNullOfOrNull { iface -> iface.hardwareAddress?.takeIf { mac -> mac.size == 6 && mac.any { it != 0.toByte() } && !(mac[0] == 2.toByte() && mac.drop(1).all { it == 0.toByte() }) } }
        }.getOrNull()?.let { return it }
        val prefs = app.getSharedPreferences("airplay", Context.MODE_PRIVATE)
        prefs.getString("address", null)?.let { saved -> runCatching { return saved.split(":").map { it.toInt(16).toByte() }.toByteArray() } }
        val mac = ByteArray(6).also { java.security.SecureRandom().nextBytes(it) }
        mac[0] = ((mac[0].toInt() and 0xF0) or 0x0A).toByte()
        prefs.edit().putString("address", mac.joinToString(":") { "%02x".format(it) }).apply()
        return mac
    }
}
