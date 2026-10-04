/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.sync

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect

/**
 * Ringer mode, Do Not Disturb and flashlight, reported to the Mac app and changeable from it
 * (PROTOCOL.md (janus-mac docs) → ge.connect.status, R13). Started from KdeConnect.onCreate.
 */
object PhoneStatus {
    private const val TAG = "PhoneStatus"
    private const val CALLBACK_KEY = "ge.connect.PhoneStatus"
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val reachable = HashSet<String>()
    @Volatile private var torchOn = false
    private var torchCameraId: String? = null

    fun start(context: Context) {
        app = context.applicationContext
        // Ringer and DND changes (both only delivered to receivers registered at runtime).
        val filter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = reportSoon()
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Flashlight: the torch callback tells us about changes from any app or the quick settings.
        runCatching {
            val cameras = app.getSystemService(CameraManager::class.java)
            torchCameraId = cameras.cameraIdList.firstOrNull { id ->
                cameras.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            cameras.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchCameraId && torchOn != enabled) {
                        torchOn = enabled
                        reportSoon()
                    }
                }
            }, main)
        }.onFailure { Log.w(TAG, "No flashlight: ${it.message}") }

        // Report to computers that just connected.
        KdeConnect.getInstance().addDeviceListChangedCallback(CALLBACK_KEY) {
            synchronized(reachable) {
                val connected = connectedDevices()
                connected.filter { reachable.add(it.deviceId) }.forEach { lastSent.remove(it.deviceId); report(it) }
                reachable.retainAll(connected.map { it.deviceId }.toSet())
            }
        }

        // R24: Wi-Fi / carrier / network type changes, and a storage check whenever the screen goes off (no polling).
        runCatching {
            app.getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(
                object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) = reportSoon()
                    // Wi-Fi reports a capabilities change on every signal-strength step: only react when the kind of
                    // network or its validation changes (R34).
                    override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                        val kind = listOf(
                            android.net.NetworkCapabilities.TRANSPORT_WIFI, android.net.NetworkCapabilities.TRANSPORT_CELLULAR,
                            android.net.NetworkCapabilities.TRANSPORT_ETHERNET, android.net.NetworkCapabilities.TRANSPORT_VPN,
                        ).filter { caps.hasTransport(it) }.joinToString(",") +
                            "|" + caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        if (kind != lastNetworkKind) { lastNetworkKind = kind; reportSoon() }
                    }
                    override fun onLost(network: android.net.Network) = reportSoon()
                },
            )
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = reportSoon()
        }, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Commands from the Mac app: "ringer:normal|vibrate|silent", "flashlight:on|off". @return handled */
    fun handleCommand(command: String): Boolean {
        val (what, value) = command.split(':', limit = 2).takeIf { it.size == 2 } ?: return false
        when (what) {
            "ringer" -> runCatching {
                val mode = when (value) {
                    "normal" -> AudioManager.RINGER_MODE_NORMAL
                    "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                    "silent" -> AudioManager.RINGER_MODE_SILENT
                    else -> return false
                }
                // Silent needs "Do Not Disturb access"; if refused, the report below corrects the Mac.
                app.getSystemService(AudioManager::class.java).ringerMode = mode
            }.onFailure { Log.w(TAG, "Ringer change refused: ${it.message}") }
            "flashlight" -> runCatching {
                app.getSystemService(CameraManager::class.java).setTorchMode(torchCameraId ?: return false, value == "on")
            }.onFailure { Log.w(TAG, "Flashlight change refused: ${it.message}") }
            else -> return false
        }
        reportSoon()
        return true
    }

    /** Several changes often arrive together; send one report for them. */
    /** [forceFor]: that device asked, so it gets the report even if nothing changed. */
    fun reportSoon(forceFor: String? = null) {
        forceFor?.let { lastSent.remove(it) }
        if (!::app.isInitialized) return
        main.removeCallbacks(reportAll)
        main.postDelayed(reportAll, 300)
    }

    private val reportAll = Runnable { connectedDevices().forEach(::report) }

    @Volatile private var lastNetworkKind: String? = null

    /** Last text sent to each device: a report is only sent when something changed. */
    private val lastSent = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun report(device: Device) {
        val text = (listOfNotNull(
            "ringer=" + when (app.getSystemService(AudioManager::class.java).ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            },
            "dnd=" + if (DndSync.isOn(app)) "on" else "off",
            torchCameraId?.let { "flashlight=" + if (torchOn) "on" else "off" },
        ) + DeviceStats.keys(app)).joinToString(";")
        if (lastSent.put(device.deviceId, text) == text) return
        Thread { CompanionMessages.sendStatusNotification(app, device, "ge.connect.status:", "Status", text, silent = true) }.start()
    }

    private fun connectedDevices() = KdeConnect.getInstance().devices.values
        .filter { it.isReachable && it.isPaired && ge.connect.devices.DeviceOwnership.isMine(it) }
}
