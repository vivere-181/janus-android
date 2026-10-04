/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.helpers.LifecycleHelper
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect.plugins.telephony.TelephonyPlugin
import org.kde.kdeconnect_tp.R

/**
 * Call commands from the Mac app (PROTOCOL.md (janus-mac docs) → ge.connect.dial / ge.connect.call, R15/R16).
 * Dialing never places a call by itself: the owner confirms on the phone.
 */
object CallCommands {
    private const val TAG = "CallCommands"
    private const val DIAL_NOTIFICATION_ID = 7_320

    /** "ge.connect.dial:<number>": dialer with the number filled in; the owner taps Call. */
    fun dial(context: Context, device: Device, number: String) {
        if (!number.matches(Regex("[0-9+*#]+"))) return
        val dialIntent = Intent(Intent.ACTION_DIAL, "tel:${android.net.Uri.encode(number)}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (LifecycleHelper.isInForeground) {
            context.startActivity(dialIntent)
            return
        }
        // In the background Android won't let us open the dialer, so offer it as a heads-up notification.
        val pending = PendingIntent.getActivity(context, 0, dialIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.dial_notification_title, device.name))
            .setContentText(number)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pending)
            .addAction(0, context.getString(R.string.dial_notification_action), pending)
            .setAutoCancel(true)
            .build()
        @SuppressLint("MissingPermission")
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(DIAL_NOTIFICATION_ID, notification)
        }
    }

    fun hasCallControl(context: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    /**
     * "ge.connect.call:decline|mute": only while a call is ringing. If declining isn't possible (no permission,
     * or the phone refuses), the ringer is muted instead so the phone stops ringing. The result goes back to the
     * Mac as "ge.connect.callresult" (decline:ok, decline:failed:<reason>, mute:ok).
     */
    fun call(context: Context, device: Device, action: String) {
        when (action) {
            // Ends the call in progress (or a ringing one), e.g. from the Mac's live call card.
            "hangup" -> {
                if (!hasCallControl(context)) {
                    askForCallControl(context)
                    report(context, device, "hangup:failed:" + context.getString(R.string.call_result_no_permission_hangup))
                    return
                }
                val telecom = context.getSystemService(TelecomManager::class.java)
                @Suppress("DEPRECATION")
                val ended = runCatching { telecom.endCall() }.onFailure { Log.w(TAG, "Hang up failed: ${it.message}") }.getOrDefault(false)
                report(context, device, if (ended) "hangup:ok" else "hangup:failed:" + context.getString(R.string.call_result_refused_hangup))
            }
            "decline" -> {
                if (!hasCallControl(context)) {
                    Log.w(TAG, "Can't decline: no call-control permission; muting instead")
                    muteRinger(context, device)
                    askForCallControl(context)
                    report(context, device, "decline:failed:" + context.getString(R.string.call_result_no_permission))
                    return
                }
                val telecom = context.getSystemService(TelecomManager::class.java)
                @Suppress("DEPRECATION") // endCall is the only public way to reject a ringing call for a non-dialer app
                val ended = runCatching { telecom.endCall() }.onFailure { Log.w(TAG, "Decline failed: ${it.message}") }.getOrDefault(false)
                if (ended) {
                    report(context, device, "decline:ok")
                } else {
                    Log.w(TAG, "The phone didn't end the call; muting instead")
                    muteRinger(context, device)
                    report(context, device, "decline:failed:" + context.getString(R.string.call_result_refused))
                }
            }
            "mute" -> {
                muteRinger(context, device)
                report(context, device, "mute:ok")
            }
        }
    }

    private fun muteRinger(context: Context, device: Device) {
        // Upstream's telephony plugin mutes the ringer and restores it after the call.
        device.getPlugin(TelephonyPlugin::class.java)?.muteRinger()
            ?: context.getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
    }

    private fun report(context: Context, device: Device, text: String) {
        Thread { CompanionMessages.sendStatusNotification(context, device, "ge.connect.callresult:", "Call", text, silent = true) }.start()
    }

    /** Notification that asks for call control (one tap: same permission group as the call state we already have). */
    private fun askForCallControl(context: Context) {
        val pending = PendingIntent.getActivity(
            context, 1, Intent(context, CallPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.call_control_title))
            .setContentText(context.getString(R.string.call_control_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.call_control_text)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .addAction(0, context.getString(R.string.call_control_allow), pending)
            .setAutoCancel(true)
            .build()
        @SuppressLint("MissingPermission")
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(CALL_CONTROL_NOTIFICATION_ID, notification)
        }
    }

    const val CALL_CONTROL_NOTIFICATION_ID = 7_321
}
