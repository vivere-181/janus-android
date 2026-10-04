/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

// ge.connect: lives in upstream's package next to TelephonyPlugin (same packet types).
package org.kde.kdeconnect.plugins.telephony

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R

/**
 * Another phone's calls on this one (phone ↔ phone): while it rings, a call notification with the caller, Decline,
 * Mute and Text back (an SMS from that phone); a missed-call notification with Call back and Text. Declaring
 * kdeconnect.telephony as incoming makes the other phone's TelephonyPlugin send to us. Computers are ignored.
 */
@LoadablePlugin
class GeRemoteCallsPlugin : Plugin() {
    override val displayName: String get() = context.getString(R.string.remote_calls_title)
    override val description: String get() = context.getString(R.string.remote_calls_desc)
    override val supportedPacketTypes = arrayOf(PACKET_TELEPHONY)
    override val outgoingPacketTypes = arrayOf(PACKET_SMS_REQUEST, PACKET_MUTE)

    private val fromPhone get() = device.deviceType == DeviceType.PHONE || device.deviceType == DeviceType.TABLET

    override fun onCreate() {
        if (fromPhone) createChannels(context)
    }

    override fun onDestroy() {
        if (fromPhone) NotificationManagerCompat.from(context).cancel(device.deviceId, CALL_ID)
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != PACKET_TELEPHONY || !fromPhone) return false
        val event = np.getStringOrNull("event") ?: return true
        val manager = NotificationManagerCompat.from(context)
        if (np.getBoolean("isCancel")) {
            // Ringing or talking ended.
            if (event == "ringing" || event == "talking") manager.cancel(device.deviceId, CALL_ID)
            return true
        }
        val number = np.getStringOrNull("phoneNumber")
        val name = np.getStringOrNull("contactName")?.takeIf { it.isNotBlank() } ?: number ?: context.getString(R.string.remote_calls_unknown)
        val photo = np.getStringOrNull("phoneThumbnail")?.let { data ->
            runCatching { Base64.decode(data, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
        }
        val builder = when (event) {
            "ringing" -> NotificationCompat.Builder(context, CHANNEL_RINGING)
                .setContentTitle(name)
                .setContentText(context.getString(R.string.remote_calls_ringing, device.name))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .addAction(0, context.getString(R.string.remote_calls_decline), action(ACTION_DECLINE, number))
                .addAction(0, context.getString(R.string.remote_calls_mute), action(ACTION_MUTE, number))
                .apply { if (number != null) addAction(textBack(number)) }
            "talking" -> NotificationCompat.Builder(context, CHANNEL_ONGOING)
                .setContentTitle(name)
                .setContentText(context.getString(R.string.remote_calls_talking, device.name))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setUsesChronometer(true)
                .setWhen(np.getLong("ge.connect.callStart", System.currentTimeMillis()))
                .setOngoing(true)
                .addAction(0, context.getString(R.string.remote_calls_hang_up), action(ACTION_HANG_UP, number))
            "missedCall" -> NotificationCompat.Builder(context, CHANNEL_MISSED)
                .setContentTitle(context.getString(R.string.remote_calls_missed, name))
                .setContentText(context.getString(R.string.remote_calls_missed_on, device.name))
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setAutoCancel(true)
                .apply {
                    if (number != null) {
                        addAction(0, context.getString(R.string.remote_calls_call_back), action(ACTION_CALL_BACK, number))
                        addAction(textBack(number))
                    }
                }
            else -> return true
        }
        builder.setSmallIcon(R.drawable.ic_notification)
            .setSubText(device.name)
            .setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (photo != null) builder.setLargeIcon(photo)
        // A missed call gets its own notification; ringing and talking share one that's replaced.
        val id = if (event == "missedCall") (number ?: name).hashCode() else CALL_ID
        if (event == "missedCall") manager.cancel(device.deviceId, CALL_ID)
        @Suppress("MissingPermission")
        manager.notify(device.deviceId, id, builder.build())
        return true
    }

    private fun intent(action: String, number: String?) = Intent(context, GeRemoteCallActions::class.java)
        .setAction(action)
        .putExtra(EXTRA_DEVICE, device.deviceId)
        .putExtra(EXTRA_NUMBER, number)

    private fun action(action: String, number: String?): PendingIntent = PendingIntent.getBroadcast(
        context, (action + device.deviceId + number).hashCode(), intent(action, number),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun textBack(number: String): NotificationCompat.Action {
        val pending = PendingIntent.getBroadcast(
            context, (ACTION_TEXT + device.deviceId + number).hashCode(), intent(ACTION_TEXT, number),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(0, context.getString(R.string.remote_calls_text), pending)
            .addRemoteInput(RemoteInput.Builder(EXTRA_TEXT).setLabel(context.getString(R.string.remote_calls_text_hint)).build())
            .build()
    }

    companion object {
        const val PACKET_TELEPHONY = "kdeconnect.telephony"
        const val PACKET_MUTE = "kdeconnect.telephony.request_mute"
        const val PACKET_SMS_REQUEST = "kdeconnect.sms.request"
        const val CALL_ID = 7_400
        private const val CHANNEL_RINGING = "ge_remote_calls"
        private const val CHANNEL_ONGOING = "ge_remote_calls_ongoing"
        private const val CHANNEL_MISSED = "ge_remote_calls_missed"
        const val ACTION_DECLINE = "ge.connect.remote.call.DECLINE"
        const val ACTION_MUTE = "ge.connect.remote.call.MUTE"
        const val ACTION_TEXT = "ge.connect.remote.call.TEXT"
        const val ACTION_CALL_BACK = "ge.connect.remote.call.CALL_BACK"
        const val ACTION_HANG_UP = "ge.connect.remote.call.HANG_UP"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_TEXT = "text"

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_RINGING, context.getString(R.string.remote_calls_channel), NotificationManager.IMPORTANCE_HIGH))
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.remote_calls_channel_ongoing), NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(CHANNEL_MISSED, context.getString(R.string.remote_calls_channel_missed), NotificationManager.IMPORTANCE_DEFAULT))
        }

        /** A `ge.connect.*` command for the other phone (the same ones the Mac app sends). */
        fun sendCommand(device: Device, command: String) =
            device.sendPacket(NetworkPacket("kdeconnect.ping").apply { this["message"] = "ge.connect.$command" })
    }
}

/** Buttons on another phone's call notifications. */
class GeRemoteCallActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device = KdeConnect.getInstance().getDevice(intent.getStringExtra(GeRemoteCallsPlugin.EXTRA_DEVICE)) ?: return
        val number = intent.getStringExtra(GeRemoteCallsPlugin.EXTRA_NUMBER)
        val manager = NotificationManagerCompat.from(context)
        when (intent.action) {
            GeRemoteCallsPlugin.ACTION_DECLINE -> {
                GeRemoteCallsPlugin.sendCommand(device, "call:decline")
                manager.cancel(device.deviceId, GeRemoteCallsPlugin.CALL_ID)
            }
            GeRemoteCallsPlugin.ACTION_MUTE -> GeRemoteCallsPlugin.sendCommand(device, "call:mute")
            GeRemoteCallsPlugin.ACTION_HANG_UP -> GeRemoteCallsPlugin.sendCommand(device, "call:hangup")
            GeRemoteCallsPlugin.ACTION_CALL_BACK -> {
                // The other phone opens its dialer with the number; the call is placed there after confirming.
                number?.let { GeRemoteCallsPlugin.sendCommand(device, "dial:$it") }
                manager.cancel(device.deviceId, (number ?: "").hashCode())
                Toast.makeText(context, context.getString(R.string.remote_calls_call_back_sent, device.name), Toast.LENGTH_SHORT).show()
            }
            GeRemoteCallsPlugin.ACTION_TEXT -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(GeRemoteCallsPlugin.EXTRA_TEXT)?.toString()
                    ?.takeIf { it.isNotBlank() } ?: return
                if (number == null) return
                device.sendPacket(NetworkPacket(GeRemoteCallsPlugin.PACKET_SMS_REQUEST).apply {
                    this["addresses"] = JSONArray().put(JSONObject().put("address", number))
                    this["phoneNumber"] = number
                    this["messageBody"] = text
                })
                // Texting back while it rings also declines the call, like on the phone itself.
                GeRemoteCallsPlugin.sendCommand(device, "call:decline")
                manager.cancel(device.deviceId, GeRemoteCallsPlugin.CALL_ID)
                manager.cancel(device.deviceId, number.hashCode())
                Toast.makeText(context, context.getString(R.string.remote_calls_text_sent, device.name), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
