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
        forceFor?.let { lastSent.remove(it); cloudAsked.add(it) }
        if (!::app.isInitialized) return
        main.removeCallbacks(reportAll)
        main.postDelayed(reportAll, 300)
    }

    private val reportAll = Runnable {
        connectedDevices().forEach(::report)
        ge.connect.cloud.CloudCompanion.awayMacs().forEach(::reportCloud)
    }

    @Volatile private var lastNetworkKind: String? = null

    /** Last text sent to each device: a report is only sent when something changed. */
    private val lastSent = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun report(device: Device) {
        val text = statusText()
        if (lastSent.put(device.deviceId, text) == text) return
        Thread { CompanionMessages.sendStatusNotification(app, device, "ge.connect.status:", "Status", text, silent = true) }.start()
    }

    // R37: through Janus Cloud while the Mac is away, with battery (the LAN has KDE's battery plugin for that). Sent when
    // the Mac asks, when the ringer, DND, flashlight or charging change, or the battery moves 5 points; at most once a minute.
    private const val CLOUD_MIN_INTERVAL_MILLIS = 60_000L
    private class CloudSent(val key: String, val battery: Int, val time: Long)
    private val cloudSent = java.util.concurrent.ConcurrentHashMap<String, CloudSent>()
    private val cloudAsked = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private var batteryReceiver: BroadcastReceiver? = null
    private var lastBattery = -1

    private fun reportCloud(device: Device) {
        val (battery, charging) = battery()
        val base = statusText()
        // What counts as a change here: everything but the network/storage details (they ride along).
        val key = base.split(';').filter { it.substringBefore('=') in setOf("ringer", "dnd", "flashlight") }.joinToString(";") + ";charging=$charging"
        val last = cloudSent[device.deviceId]
        val asked = cloudAsked.remove(device.deviceId)
        val changed = last == null || last.key != key || kotlin.math.abs(last.battery - battery) >= 5
        if (!asked && !changed) return
        val now = System.currentTimeMillis()
        if (!asked && last != null && now - last.time < CLOUD_MIN_INTERVAL_MILLIS) {
            main.removeCallbacks(reportAll)
            main.postDelayed(reportAll, CLOUD_MIN_INTERVAL_MILLIS - (now - last.time) + 100)
            return
        }
        cloudSent[device.deviceId] = CloudSent(key, battery, now)
        val text = "$base;battery=$battery;charging=${if (charging) 1 else 0}"
        Thread { CompanionMessages.sendStatusNotification(app, device, "ge.connect.status:", "Status", text, silent = true) }.start()
    }

    private fun battery(): Pair<Int, Boolean> {
        val manager = app.getSystemService(android.os.BatteryManager::class.java)
        return manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100) to manager.isCharging
    }

    /** Battery and charging changes matter only while a Mac is away: listened to just then. */
    fun cloudChanged() = main.post {
        val away = ge.connect.cloud.CloudCompanion.awayMacs().isNotEmpty()
        if (away && batteryReceiver == null) {
            batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val percent = level * 100 / scale
                    // Sticky and frequent (temperature, voltage): only react to what reportCloud cares about.
                    if (percent != lastBattery || intent.action != Intent.ACTION_BATTERY_CHANGED) { lastBattery = percent; reportSoon() }
                }
            }.also {
                ContextCompat.registerReceiver(app, it, IntentFilter().apply {
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        } else if (!away && batteryReceiver != null) {
            runCatching { app.unregisterReceiver(batteryReceiver) }
            batteryReceiver = null
            cloudSent.clear()
        }
    }

    private fun statusText(): String {
        return (listOfNotNull(
            "ringer=" + when (app.getSystemService(AudioManager::class.java).ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            },
            "dnd=" + if (DndSync.isOn(app)) "on" else "off",
            torchCameraId?.let { "flashlight=" + if (torchOn) "on" else "off" },
        ) + DeviceStats.keys(app)).joinToString(";")
    }

    private fun connectedDevices() = KdeConnect.getInstance().devices.values
        .filter { it.isReachable && it.isPaired && ge.connect.devices.DeviceOwnership.isMine(it) }
}
