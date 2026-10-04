/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

// ge.connect: lives in upstream's package next to NotificationsPlugin (same packet types).
package org.kde.kdeconnect.plugins.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import org.json.JSONArray
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R
import java.io.File

/**
 * Shows another phone's notifications on this one (phone ↔ phone): icon or picture, conversation, Reply and the app's
 * own buttons; dismissing here dismisses it there and the other way round. Declaring kdeconnect.notification as
 * incoming also makes the other phone's NotificationsPlugin start sending to us. Computers are ignored.
 */
@LoadablePlugin
class GeRemoteNotificationsPlugin : Plugin() {
    override val displayName: String get() = context.getString(R.string.remote_notifications_title)
    override val description: String get() = context.getString(R.string.remote_notifications_desc)
    override val supportedPacketTypes = arrayOf(PACKET_NOTIFICATION)
    override val outgoingPacketTypes = arrayOf(PACKET_REQUEST, PACKET_REPLY, PACKET_ACTION)

    private val fromPhone get() = device.deviceType == DeviceType.PHONE || device.deviceType == DeviceType.TABLET

    override fun onCreate() {
        if (!fromPhone) return
        createChannels(context)
        // Ask for what's already in the other phone's shade (it sends those as silent).
        device.sendPacket(NetworkPacket(PACKET_REQUEST).apply { this["request"] = true })
    }

    override fun onDestroy() {
        // Disconnected: its notifications can't be acted on any more.
        if (fromPhone) runCatching { cancelAll(context, device.deviceId) }
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != PACKET_NOTIFICATION || !fromPhone) return false
        val id = np.getStringOrNull("id") ?: return true
        // Companion messages between our own apps (status, live, transfer…) are handled elsewhere or ignored.
        if (id.startsWith("ge.connect.")) {
            ge.connect.sync.PeerCompanions.handle(context, device, np)
            np.payload?.close()
            return true
        }
        if (np.getBoolean("isCancel")) {
            NotificationManagerCompat.from(context).cancel(device.deviceId, id.hashCode())
            return true
        }
        runCatching { show(np, id) }.onFailure { Log.w(TAG, "Can't show notification: ${it.message}") }
        return true
    }

    private fun show(np: NetworkPacket, id: String) {
        val image = image(np)
        val appName = np.getStringOrNull("appName").orEmpty()
        val title = np.getStringOrNull("title").orEmpty()
        val text = np.getStringOrNull("text").orEmpty()
        val silent = np.getBoolean("silent")
        // Pictures (a photo in a message) are sent large and not square; icons and avatars are ~256 px squares.
        val isPicture = image != null && (maxOf(image.width, image.height) > 320 || image.width != image.height)
        val tag = device.deviceId
        val notificationId = id.hashCode()

        val builder = NotificationCompat.Builder(context, if (silent) CHANNEL_SILENT else CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifEmpty { appName })
            .setContentText(text)
            .setSubText(listOf(appName, device.name).filter { it.isNotEmpty() }.joinToString(" · "))
            .setWhen(np.getStringOrNull("time")?.toLongOrNull() ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setSilent(silent)
            .setGroup("ge.remote.${device.deviceId}")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.remote_notifications_public, device.name)).build(),
            )
            .setContentIntent(openApp())
            .setDeleteIntent(broadcast(ACTION_DISMISS, id, notificationId))

        if (image != null && !isPicture) builder.setLargeIcon(image)

        val conversation = np.getJSONArray("conversation")
        when {
            conversation != null && conversation.length() > 0 -> builder.setStyle(messagingStyle(conversation, np.getStringOrNull("groupName"), title))
            isPicture -> builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(image).setSummaryText(text))
            text.length > 40 -> builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }

        np.getStringOrNull("requestReplyId")?.let { replyId ->
            val input = RemoteInput.Builder(EXTRA_REPLY).setLabel(context.getString(R.string.remote_notifications_reply)).build()
            val reply = PendingIntent.getBroadcast(
                context, notificationId * 31 + 1,
                intent(ACTION_REPLY, id, notificationId).putExtra(EXTRA_REPLY_ID, replyId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_notification, context.getString(R.string.remote_notifications_reply), reply)
                    .addRemoteInput(input).setAllowGeneratedReplies(true).build(),
            )
        }
        np.getJSONArray("actions")?.let { actions ->
            for (i in 0 until minOf(actions.length(), 3)) {
                val action = actions.optString(i).takeIf { it.isNotEmpty() } ?: continue
                val pending = PendingIntent.getBroadcast(
                    context, notificationId * 31 + 2 + i,
                    intent(ACTION_ACTION, id, notificationId).putExtra(EXTRA_ACTION, action),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(0, action, pending)
            }
        }
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(tag, notificationId, builder.build())
    }

    private fun messagingStyle(conversation: JSONArray, groupName: String?, title: String): NotificationCompat.MessagingStyle {
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName(context.getString(R.string.remote_notifications_you)).build())
        if (!groupName.isNullOrEmpty()) {
            style.setConversationTitle(groupName).setGroupConversation(true)
        }
        val now = System.currentTimeMillis()
        val count = conversation.length()
        for (i in 0 until count) {
            val message = conversation.optJSONObject(i) ?: continue
            val sender = message.optString("sender").ifEmpty { title }
            style.addMessage(message.optString("content"), now - (count - i), Person.Builder().setName(sender).build())
        }
        return style
    }

    /** The icon, avatar or picture: read from the payload when offered, else from our cache by its hash. */
    private fun image(np: NetworkPacket): Bitmap? {
        val hash = np.getStringOrNull("payloadHash")
        val dir = File(context.cacheDir, "remote_icons").apply { mkdirs() }
        val file = hash?.let { File(dir, it.filter(Char::isLetterOrDigit)) }
        val payload = np.payload
        if (payload != null) {
            try {
                val bytes = payload.inputStream?.readBytes()?.takeIf { it.size in 1..(4 shl 20) }
                if (bytes != null) {
                    file?.writeBytes(bytes)
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Icon download failed: ${e.message}")
            } finally {
                payload.close()
            }
        }
        return file?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, device.deviceId.hashCode(),
        Intent(context, ge.connect.home.HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun intent(action: String, key: String, notificationId: Int) = Intent(context, GeRemoteNotificationActions::class.java)
        .setAction(action)
        .putExtra(EXTRA_DEVICE, device.deviceId)
        .putExtra(EXTRA_KEY, key)
        .putExtra(EXTRA_NOTIFICATION_ID, notificationId)

    private fun broadcast(action: String, key: String, notificationId: Int): PendingIntent =
        PendingIntent.getBroadcast(context, notificationId * 31, intent(action, key, notificationId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        private const val TAG = "GeRemoteNotifications"
        const val PACKET_NOTIFICATION = "kdeconnect.notification"
        const val PACKET_REQUEST = "kdeconnect.notification.request"
        const val PACKET_REPLY = "kdeconnect.notification.reply"
        const val PACKET_ACTION = "kdeconnect.notification.action"
        private const val CHANNEL = "ge_remote_notifications"
        private const val CHANNEL_SILENT = "ge_remote_notifications_silent"
        const val ACTION_ACTION = "ge.connect.remote.ACTION"
        const val ACTION_REPLY = "ge.connect.remote.REPLY"
        const val ACTION_DISMISS = "ge.connect.remote.DISMISS"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_KEY = "key"
        const val EXTRA_ACTION = "action"
        const val EXTRA_REPLY = "reply"
        const val EXTRA_REPLY_ID = "replyId"
        const val EXTRA_NOTIFICATION_ID = "notificationId"

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.remote_notifications_channel), NotificationManager.IMPORTANCE_HIGH),
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_SILENT, context.getString(R.string.remote_notifications_channel_silent), NotificationManager.IMPORTANCE_LOW),
            )
        }

        fun cancelAll(context: Context, deviceId: String) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.filter { it.tag == deviceId && it.notification.group == "ge.remote.$deviceId" }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }
}

/** Buttons and dismissals on another phone's notifications, sent back to that phone. */
class GeRemoteNotificationActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device = KdeConnect.getInstance().getDevice(intent.getStringExtra(GeRemoteNotificationsPlugin.EXTRA_DEVICE)) ?: return
        val key = intent.getStringExtra(GeRemoteNotificationsPlugin.EXTRA_KEY) ?: return
        val notificationId = intent.getIntExtra(GeRemoteNotificationsPlugin.EXTRA_NOTIFICATION_ID, 0)
        when (intent.action) {
            GeRemoteNotificationsPlugin.ACTION_DISMISS -> device.sendPacket(NetworkPacket(GeRemoteNotificationsPlugin.PACKET_REQUEST).apply {
                this["cancel"] = key
            })
            GeRemoteNotificationsPlugin.ACTION_ACTION -> {
                device.sendPacket(NetworkPacket(GeRemoteNotificationsPlugin.PACKET_ACTION).apply {
                    this["key"] = key
                    this["action"] = intent.getStringExtra(GeRemoteNotificationsPlugin.EXTRA_ACTION).orEmpty()
                })
                NotificationManagerCompat.from(context).cancel(device.deviceId, notificationId)
            }
            GeRemoteNotificationsPlugin.ACTION_REPLY -> {
                val message = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(GeRemoteNotificationsPlugin.EXTRA_REPLY)?.toString()?.takeIf { it.isNotBlank() } ?: return
                device.sendPacket(NetworkPacket(GeRemoteNotificationsPlugin.PACKET_REPLY).apply {
                    this["requestReplyId"] = intent.getStringExtra(GeRemoteNotificationsPlugin.EXTRA_REPLY_ID).orEmpty()
                    this["message"] = message
                })
                // Show the reply under the notification (Android keeps a spinner until it's updated).
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.activeNotifications.firstOrNull { it.tag == device.deviceId && it.id == notificationId }?.let { active ->
                    val updated = Notification.Builder.recoverBuilder(context, active.notification)
                        .setRemoteInputHistory(arrayOf(message))
                        .setOnlyAlertOnce(true)
                        .build()
                    manager.notify(device.deviceId, notificationId, updated)
                }
            }
        }
    }
}
