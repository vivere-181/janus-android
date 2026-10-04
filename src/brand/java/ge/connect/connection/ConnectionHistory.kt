/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.connection

import android.content.Context
import androidx.core.content.edit
import org.kde.kdeconnect.KdeConnect

/**
 * Remembers when each paired device connected and disconnected (last [MAX_EVENTS] per device),
 * so the connection screen can show "connected since" and a history. Started from KdeConnect.onCreate.
 */
object ConnectionHistory {
    data class Event(val connected: Boolean, val time: Long)

    private const val PREFS = "ge_connect_connection_history"
    private const val MAX_EVENTS = 30
    private const val CALLBACK_KEY = "ge.connect.ConnectionHistory"
    private val lastState = HashMap<String, Boolean>()

    fun start(context: Context) {
        val app = context.applicationContext
        KdeConnect.getInstance().addDeviceListChangedCallback(CALLBACK_KEY) { record(app) }
        record(app)
    }

    @Synchronized
    private fun record(context: Context) {
        for (device in KdeConnect.getInstance().devices.values.filter { it.isPaired }) {
            val now = device.isReachable
            val before = lastState.put(device.deviceId, now)
            // At startup a disconnected device tells us nothing new; a connected one is a connection.
            if (before != now && (before != null || now)) append(context, device.deviceId, Event(now, System.currentTimeMillis()))
        }
    }

    private fun append(context: Context, deviceId: String, event: Event) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val events = listOf(event) + parse(prefs.getString(deviceId, null))
        prefs.edit { putString(deviceId, events.take(MAX_EVENTS).joinToString(",") { "${if (it.connected) 1 else 0}:${it.time}" }) }
    }

    /** Newest first. */
    fun events(context: Context, deviceId: String): List<Event> =
        parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(deviceId, null))

    private fun parse(value: String?): List<Event> = value.orEmpty().split(',').mapNotNull { entry ->
        val (state, time) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        time.toLongOrNull()?.let { Event(state == "1", it) }
    }
}
