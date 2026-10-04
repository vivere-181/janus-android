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
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect

/**
 * Do Not Disturb sync with the Mac (PROTOCOL.md (janus-mac docs) → ge.connect.dnd, R6).
 * Reports the phone's DND state on every change and when a computer connects; applies on/off
 * commands from the Mac when "Do Not Disturb access" is granted. Started from KdeConnect.onCreate.
 */
object DndSync {
    const val KEY_ENABLED = "ge_connect_dnd_sync"
    private const val CALLBACK_KEY = "ge.connect.DndSync"
    private val reachable = HashSet<String>()

    fun isEnabled(context: Context) = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit { putBoolean(KEY_ENABLED, enabled) }
        if (enabled) reportToAll(context)
    }

    fun hasAccess(context: Context) = notificationManager(context).isNotificationPolicyAccessGranted

    fun start(context: Context) {
        val app = context.applicationContext
        // Sent only to registered receivers, so it has to be registered at runtime.
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = reportToAll(app)
        }, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)

        KdeConnect.getInstance().addDeviceListChangedCallback(CALLBACK_KEY) { onDevicesChanged(app) }
    }

    /** Report to computers that just connected. */
    @Synchronized
    private fun onDevicesChanged(context: Context) {
        val connected = connectedDevices()
        connected.filter { reachable.add(it.deviceId) }.forEach { report(context, it) }
        reachable.retainAll(connected.map { it.deviceId }.toSet())
    }

    private fun reportToAll(context: Context) = connectedDevices().forEach { report(context, it) }

    private fun report(context: Context, device: Device) {
        if (!isEnabled(context)) return
        val on = isOn(context)
        Thread {
            CompanionMessages.sendStatusNotification(context, device, "ge.connect.dnd:", "Do Not Disturb", if (on) "on" else "off", silent = true)
        }.start()
    }

    fun applyFromComputer(context: Context, on: Boolean) {
        if (!isEnabled(context) || !hasAccess(context) || isOn(context) == on) return
        notificationManager(context).setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
        )
    }

    fun isOn(context: Context): Boolean {
        val filter = notificationManager(context).currentInterruptionFilter
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private fun connectedDevices() = KdeConnect.getInstance().devices.values
        .filter { it.isReachable && it.isPaired && ge.connect.devices.DeviceOwnership.isMine(it) }
    private fun notificationManager(context: Context) = context.getSystemService(NotificationManager::class.java)
}
