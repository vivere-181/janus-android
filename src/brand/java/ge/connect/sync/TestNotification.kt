/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.NotificationManagerCompat
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect_tp.R

/**
 * A real Android notification that goes through the normal sync path (listener → app filter →
 * packet), to check notification sync end to end (the request log R10). It's the one
 * notification of our own app that gets forwarded, and it ignores "Only when the screen is off".
 */
object TestNotification {
    private const val TAG = "ge.connect.test"
    private const val ID = 7_310
    private const val REMOVE_AFTER_MILLIS = 60_000L
    private val handler = Handler(Looper.getMainLooper())
    const val ACTION_REPLY = "ge.connect.test.REPLY"
    const val ACTION_GOT_IT = "ge.connect.test.GOT_IT"
    const val EXTRA_REPLY = "reply"

    fun isTest(sbn: StatusBarNotification) = sbn.tag == TAG && sbn.id == ID

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(TAG, ID)

    /** @return false if notifications are blocked for the app. */
    fun post(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.DEFAULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.test_notification_title))
            .setContentText(context.getString(R.string.test_notification_text))
            .setAutoCancel(true)
            // A reply field and a button, so replies and actions can be checked from the other device too.
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification, context.getString(R.string.test_notification_reply),
                    PendingIntent.getBroadcast(
                        context, 1, Intent(context, TestNotificationActions::class.java).setAction(ACTION_REPLY),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    ),
                ).addRemoteInput(RemoteInput.Builder(EXTRA_REPLY).setLabel(context.getString(R.string.test_notification_reply)).build()).build(),
            )
            .addAction(
                0, context.getString(R.string.test_notification_got_it),
                PendingIntent.getBroadcast(
                    context, 2, Intent(context, TestNotificationActions::class.java).setAction(ACTION_GOT_IT),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        @Suppress("MissingPermission") // checked above via areNotificationsEnabled
        manager.notify(TAG, ID, notification)
        // One removal timer: an earlier test's timer must not remove this one.
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ manager.cancel(TAG, ID) }, REMOVE_AFTER_MILLIS)
        return true
    }
}

/** The test notification's Reply and "Got it", wherever they were pressed (here, the Mac, or another phone). */
class TestNotificationActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val message = when (intent.action) {
            TestNotification.ACTION_REPLY -> RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(TestNotification.EXTRA_REPLY)?.let { context.getString(R.string.test_notification_replied, it) }
            TestNotification.ACTION_GOT_IT -> context.getString(R.string.test_notification_action_done)
            else -> null
        } ?: return
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        android.util.Log.i("TestNotification", message)
        TestNotification.cancel(context)
    }
}
