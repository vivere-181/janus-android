/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import org.kde.kdeconnect.helpers.NotificationHelper

/**
 * The "Connected to …" notification of the background service. Android requires it and won't let
 * apps lower it enough to hide it (a foreground-service notification is always at least "silent"),
 * so hiding it means the user turns off its notification category; the service keeps running.
 */
object ConnectionNotification {
    fun isShown(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        val channel = manager.getNotificationChannelCompat(NotificationHelper.Channels.PERSISTENT)
        return manager.areNotificationsEnabled() && channel?.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    /** Android's page for just this notification category (one switch). */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, NotificationHelper.Channels.PERSISTENT)
}
