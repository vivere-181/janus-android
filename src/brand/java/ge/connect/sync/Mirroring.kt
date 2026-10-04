/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.HandlerCompat
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect_tp.R

/**
 * Screen mirroring to the Mac (PROTOCOL.md (janus-mac docs) → ge.connect.mirror, R25). The Mac mirrors over Wireless debugging
 * (scrcpy); the phone only switches Wireless debugging on for it (WRITE_SECURE_SETTINGS, granted from the Mac over adb),
 * switches it back off afterwards if it was the one that switched it on, and shows "Mirroring to <Mac>" with Stop while
 * it runs (scrcpy has no system indicator of its own). Commands only come from "mine" devices (CompanionMessages).
 */
object Mirroring {
    private const val TAG = "Mirroring"
    private const val PREFS = "ge_connect_mirroring"
    private const val KEY_PAIRED = "paired"
    private const val KEY_TURNED_ON_FOR = "turned_on_for" // the Mac we switched Wireless debugging on for
    private const val KEY_KEEP_ON = "keep_on"
    private const val ADB_WIFI = "adb_wifi_enabled"
    private const val NOTIFICATION_ID = 7_330
    private const val CALLBACK_KEY = "ge.connect.mirroring"
    /** Android switches Wireless debugging straight back off when it isn't allowed (no Wi-Fi, network not trusted). */
    private const val SETTLE_MILLIS = 1_500L
    /** Off long enough for adbd to be stopped before it's switched on again (restart). */
    private const val RESTART_GAP_MILLIS = 500L
    /** Stop pressed but the Mac didn't answer: end it from here. */
    private const val STOP_TIMEOUT_MILLIS = 5_000L
    /** The Mac is gone for this long (so no "done" will come): switch Wireless debugging back off. */
    private const val GONE_MILLIS = 2 * 60_000L
    const val ACTION_STOP = "ge.connect.mirror.STOP"
    const val EXTRA_DEVICE = "device"

    private val worker by lazy { Handler(HandlerThread(TAG).apply { start() }.looper) }
    // Only touched on the worker thread.
    private var mirroringFor: String? = null
    private var goneTimer = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The Mac reported a successful Wireless debugging pairing (the setup step shows ✓). */
    fun isPaired(context: Context) = prefs(context).getBoolean(KEY_PAIRED, false)
    fun keepOn(context: Context) = prefs(context).getBoolean(KEY_KEEP_ON, false)
    fun setKeepOn(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_KEEP_ON, on) }

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Samsung Auto Blocker ("rampart") blocks adb commands, so mirroring can't work while it's on. */
    fun autoBlockerOn(context: Context) =
        runCatching { Settings.Secure.getInt(context.contentResolver, "rampart_main_switch_enabled", 0) == 1 }.getOrDefault(false)

    fun developerOptionsOn(context: Context) =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    private fun wirelessDebuggingOn(context: Context) = Settings.Global.getInt(context.contentResolver, ADB_WIFI, 0) == 1

    /** The setup step: Wireless debugging (highlighted where supported), or About phone to turn Developer options on. */
    fun settingsIntent(context: Context): Intent =
        if (developerOptionsOn(context)) {
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        } else {
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        }

    fun start(app: Application) {
        // No "done" comes from a Mac that went away: switch Wireless debugging back off after a while.
        KdeConnect.getInstance().addDeviceListChangedCallback(CALLBACK_KEY) {
            worker.post {
                val forId = prefs(app).getString(KEY_TURNED_ON_FOR, null) ?: mirroringFor
                val gone = forId != null && KdeConnect.getInstance().getDevice(forId)?.isReachable != true
                if (!gone) {
                    worker.removeCallbacksAndMessages(CALLBACK_KEY)
                    goneTimer = false
                } else if (!goneTimer) {
                    goneTimer = true
                    HandlerCompat.postDelayed(worker, {
                        goneTimer = false
                        if (KdeConnect.getInstance().getDevice(forId!!)?.isReachable != true) finish(app, forId)
                    }, CALLBACK_KEY, GONE_MILLIS)
                }
            }
        }
    }

    /** "ge.connect.mirror:<command>" from a Mac. */
    fun handleCommand(context: Context, device: Device, command: String) {
        val app = context.applicationContext
        worker.post {
            when (command) {
                "prepare" -> reply(app, device, prepare(app, device))
                "restart" -> reply(app, device, restart(app))
                "done" -> finish(app, device.deviceId)
                "started" -> showNotification(app, device)
                "stopped" -> hideNotification(app)
                "paired" -> prefs(app).edit { putBoolean(KEY_PAIRED, true) }
                else -> Log.i(TAG, "Ignoring mirror command $command")
            }
        }
    }

    /** Why Wireless debugging can't be switched on now, or null. */
    private fun blocker(context: Context): String? = when {
        !hasPermission(context) -> R.string.mirroring_failed_permission
        autoBlockerOn(context) -> R.string.mirroring_failed_auto_blocker
        !developerOptionsOn(context) -> R.string.mirroring_failed_developer_options
        !onWifi(context) -> R.string.mirroring_failed_wifi
        else -> null
    }?.let { "failed:" + context.getString(it) }

    /** @return "ready" or "failed:<reason>" */
    private fun prepare(context: Context, device: Device): String {
        blocker(context)?.let { return it }
        if (wirelessDebuggingOn(context)) return "ready" // on already (by the owner, or by us earlier and not yet finished)
        // Remembered before switching, so it's switched back off even if the owner allows it later from the phone's prompt.
        prefs(context).edit(commit = true) { putString(KEY_TURNED_ON_FOR, device.deviceId) }
        val switched = runCatching { Settings.Global.putInt(context.contentResolver, ADB_WIFI, 1) }
            .onFailure { Log.w(TAG, "Couldn't switch Wireless debugging on: ${it.message}") }.getOrDefault(false)
        if (!switched) return "failed:" + context.getString(R.string.mirroring_failed_permission)
        Thread.sleep(SETTLE_MILLIS)
        return if (wirelessDebuggingOn(context)) "ready" else "failed:" + context.getString(R.string.mirroring_failed_network)
    }

    /**
     * adbd died under a running Wireless debugging (e.g. a USB mode switch on unlock stops it) and Android only starts
     * it again when the setting changes: switch it off and on. Who switched it on (and the notification) stays as it was.
     * @return "ready" or "failed:<reason>", like [prepare]
     */
    private fun restart(context: Context): String {
        blocker(context)?.let { return it }
        val restarted = runCatching {
            Settings.Global.putInt(context.contentResolver, ADB_WIFI, 0)
            Thread.sleep(RESTART_GAP_MILLIS)
            Settings.Global.putInt(context.contentResolver, ADB_WIFI, 1)
        }.onFailure { Log.w(TAG, "Couldn't restart Wireless debugging: ${it.message}") }.getOrDefault(false)
        if (!restarted) return "failed:" + context.getString(R.string.mirroring_failed_permission)
        Thread.sleep(SETTLE_MILLIS)
        return if (wirelessDebuggingOn(context)) "ready" else "failed:" + context.getString(R.string.mirroring_failed_network)
    }

    /** Mirroring is over: switch Wireless debugging back off if we switched it on (and the owner doesn't keep it on). */
    private fun finish(context: Context, deviceId: String, force: Boolean = false) {
        if (mirroringFor == deviceId) hideNotification(context)
        val prefs = prefs(context)
        if (!force && prefs.getString(KEY_TURNED_ON_FOR, null) != deviceId) return
        if (force || !keepOn(context)) {
            runCatching { Settings.Global.putInt(context.contentResolver, ADB_WIFI, 0) }
                .onFailure { Log.w(TAG, "Couldn't switch Wireless debugging off: ${it.message}") }
        }
        prefs.edit { remove(KEY_TURNED_ON_FOR) }
    }

    private fun onWifi(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    private fun reply(context: Context, device: Device, text: String) {
        CompanionMessages.sendStatusNotification(context, device, "ge.connect.mirror:", "Mirroring", text, silent = true)
    }

    @SuppressLint("MissingPermission") // checked via areNotificationsEnabled
    private fun showNotification(context: Context, device: Device) {
        mirroringFor = device.deviceId
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val stop = PendingIntent.getBroadcast(
            context, NOTIFICATION_ID,
            Intent(context, MirroringActions::class.java).setAction(ACTION_STOP).putExtra(EXTRA_DEVICE, device.deviceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.DEFAULT)
            .setSmallIcon(R.drawable.ms_cast)
            .setContentTitle(context.getString(R.string.mirroring_notification_title, device.name))
            .setContentText(context.getString(R.string.mirroring_notification_text, device.name))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(open)
            .addAction(R.drawable.ms_close, context.getString(R.string.mirroring_stop), stop)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun hideNotification(context: Context) {
        mirroringFor = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * Stop on the notification: ask the Mac to stop. If it doesn't within a few seconds, end it from here by switching
     * Wireless debugging off (even when it's kept on: the owner asked for mirroring to stop).
     */
    fun stop(context: Context, deviceId: String) {
        val app = context.applicationContext
        worker.post {
            val device = KdeConnect.getInstance().getDevice(deviceId)
            if (device != null) reply(app, device, "stop")
            worker.postDelayed({ if (mirroringFor == deviceId) finish(app, deviceId, force = true) }, STOP_TIMEOUT_MILLIS)
        }
    }
}

class MirroringActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Mirroring.ACTION_STOP) return
        Mirroring.stop(context, intent.getStringExtra(Mirroring.EXTRA_DEVICE) ?: return)
    }
}
