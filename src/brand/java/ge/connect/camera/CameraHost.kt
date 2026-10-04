/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.camera

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect_tp.R

/**
 * Camera-phone side: routes requests to the open camera screen, or — since Android doesn't let apps open the camera
 * from the background — asks the person holding this phone to open it with a notification.
 */
object CameraHost {
    private const val CHANNEL = "ge_remote_camera"
    private const val NOTIFICATION_ID = 7_500

    /** The open camera screen, if any. */
    interface Screen {
        val controllerId: String
        fun shutter(delaySeconds: Int)
        fun switchLens()
        fun stop()
    }

    @Volatile var screen: Screen? = null

    fun onRequest(context: Context, device: Device, action: String, delay: Int) {
        val open = screen?.takeIf { it.controllerId == device.deviceId }
        when (action) {
            "start" -> if (open == null) {
                val current = screen
                if (current != null) {
                    // The camera is in use by another phone: switch it over.
                    current.stop()
                }
                askToOpen(context, device)
                status(device, "waiting")
            } else {
                status(device, "ready")
            }
            "stop" -> {
                open?.stop()
                NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            }
            "shutter" -> open?.shutter(delay)
            "switch" -> open?.switchLens()
        }
    }

    fun controllerGone(deviceId: String) {
        screen?.takeIf { it.controllerId == deviceId }?.stop()
    }

    fun status(device: Device, state: String, lens: String? = null, countdown: Int = 0) =
        device.sendPacket(NetworkPacket(GeCameraPlugin.STATUS).apply {
            this["state"] = state
            lens?.let { this["lens"] = it }
            if (countdown > 0) this["countdown"] = countdown
        })

    @SuppressLint("MissingPermission")
    private fun askToOpen(context: Context, device: Device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.camera_channel), NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val open = PendingIntent.getActivity(
            context, device.deviceId.hashCode(), CameraHostActivity.intent(context, device.deviceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ms_photo_camera)
                .setContentTitle(context.getString(R.string.camera_request_title, device.name))
                .setContentText(context.getString(R.string.camera_request_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(open)
                .addAction(0, context.getString(R.string.camera_request_open), open)
                .setAutoCancel(true)
                .setTimeoutAfter(120_000)
                .build(),
        )
    }

    fun cancelRequest(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
}
