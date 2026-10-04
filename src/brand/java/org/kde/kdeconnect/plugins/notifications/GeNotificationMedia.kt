/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.kde.kdeconnect.plugins.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream

/**
 * ge.connect (R20, the request log): what the Mac shows next to a notification, and whether it should stay quiet.
 * Picture (BigPicture / MessagingStyle image) > sender avatar (large icon / MessagingStyle person) > app icon,
 * at least [ICON_SIZE] px instead of upstream's 96 px monochrome small icon.
 */
internal object GeNotificationMedia {
    private const val TAG = "GeNotificationMedia"
    private const val ICON_SIZE = 256
    private const val PICTURE_MAX = 1024

    /** Encoded image for the notification's icon payload, or null if there's nothing usable. */
    fun imageBytes(context: Context, sbn: StatusBarNotification, allowPictures: Boolean): ByteArray? = runCatching {
        val notification = sbn.notification
        val foreign = context.createPackageContext(sbn.packageName, 0)
        if (allowPictures) picture(context, foreign, notification)?.let {
            return@runCatching jpeg(fit(it, PICTURE_MAX)).also { bytes -> log(sbn, "picture", bytes) }
        }
        val avatar = notification.getLargeIcon()?.let { drawable(foreign, it) }
            ?: personIcon(foreign, notification)
        val icon = avatar ?: context.packageManager.getApplicationIcon(sbn.packageName)
        png(render(icon, ICON_SIZE)).also { bytes -> log(sbn, if (avatar != null) "avatar" else "app icon", bytes) }
    }.onFailure { Log.w(TAG, "No image for ${sbn.packageName}: ${it.message}") }.getOrNull()

    private fun log(sbn: StatusBarNotification, kind: String, bytes: ByteArray) =
        Log.i(TAG, "${sbn.packageName}: sending $kind (${bytes.size / 1024} KB)")

    /** The app's launcher icon as a 256 px PNG (Live Activities, R21). */
    fun appIconBytes(context: Context, packageName: String): ByteArray? = runCatching {
        png(render(context.packageManager.getApplicationIcon(packageName), ICON_SIZE))
    }.getOrNull()

    /** BigPictureStyle picture, or the newest image in a MessagingStyle conversation (if we're allowed to read it). */
    private fun picture(context: Context, foreign: Context, notification: Notification): Bitmap? {
        val extras = notification.extras
        @Suppress("DEPRECATION")
        (extras.get(Notification.EXTRA_PICTURE) as? Bitmap)?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            (extras.get(Notification.EXTRA_PICTURE_ICON) as? Icon)?.let { icon -> drawable(foreign, icon)?.let { return render(it, PICTURE_MAX) } }
        }
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification) ?: return null
        val message = style.messages.lastOrNull { it.dataMimeType?.startsWith("image/") == true && it.dataUri != null } ?: return null
        return runCatching {
            context.contentResolver.openInputStream(message.dataUri!!)?.use { android.graphics.BitmapFactory.decodeStream(it) }
        }.onFailure { Log.i(TAG, "Can't read message image: ${it.message}") }.getOrNull()
    }

    private fun personIcon(foreign: Context, notification: Notification): Drawable? {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification) ?: return null
        val icon = style.messages.lastOrNull()?.person?.icon ?: return null
        return runCatching { icon.loadDrawable(foreign) }.getOrNull()
    }

    private fun drawable(foreign: Context, icon: Icon): Drawable? = runCatching { icon.loadDrawable(foreign) }.getOrNull()

    /** Bitmaps keep their size (up to [max]); other drawables are drawn at [max] px. */
    private fun render(drawable: Drawable, max: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return fit(drawable.bitmap, max)
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: max
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: max
        val scale = max.toFloat() / maxOf(width, height)
        val bitmap = createBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun fit(bitmap: Bitmap, max: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= max) return bitmap
        val scale = max.toFloat() / longest
        return bitmap.scale((bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1))
    }

    private fun png(bitmap: Bitmap) = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    private fun jpeg(bitmap: Bitmap) = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()

    /**
     * Whether the phone kept this notification quiet: low-importance channel, a channel without sound or vibration,
     * or silenced by Do Not Disturb. Alert-once updates are NOT treated as silent: apps like WhatsApp mark each
     * message update alert-once and ring through the group summary (which isn't forwarded), so the Mac would miss
     * the sound for real messages.
     */
    @Suppress("UNUSED_PARAMETER")
    fun wasSilent(listener: NotificationListenerService?, sbn: StatusBarNotification, isUpdate: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || listener == null) return false
        val ranking = NotificationListenerService.Ranking()
        val found = runCatching { listener.currentRanking?.getRanking(sbn.key, ranking) == true }.getOrDefault(false)
        if (!found) return false
        if (!ranking.matchesInterruptionFilter()) return true // Do Not Disturb
        if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return true
        val channel = ranking.channel ?: return false
        return channel.sound == null && !channel.shouldVibrate()
    }
}
