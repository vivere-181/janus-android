/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.cloud

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import ge.connect.sync.CompanionMessages
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.helpers.LifecycleHelper
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect.plugins.findmyphone.FindMyPhoneActivity
import org.kde.kdeconnect.plugins.findmyphone.FindMyPhoneReceiver
import org.kde.kdeconnect.plugins.findmyphone.FlashlightManager
import org.kde.kdeconnect_tp.R
import java.lang.ref.WeakReference

/**
 * Find My Phone from a Mac that's away (R37 `findmyphone`): rings like upstream's FindMyPhonePlugin (alarm stream at
 * full volume, the chosen ringtone, flashing if enabled), which only exists while the Mac is on the LAN. The same
 * "Your phone is ringing" screen and "Found it" notification stop it; a second request stops it too (a toggle, R12).
 * The ring state goes back as `ge.connect.ring`, through the cloud.
 */
object CloudRinger {
    private const val TAG = "CloudRinger"
    private val main = Handler(Looper.getMainLooper())
    private const val NOTIFICATION_ID = 0x4a52 // "JR"

    // Main thread only.
    private var player: MediaPlayer? = null
    private var flashlight: FlashlightManager? = null
    private var previousVolume = -1
    private var device: Device? = null
    var ringingActivity: WeakReference<android.app.Activity>? = null

    /** Whether [deviceId]'s ring is ours (no FindMyPhonePlugin while the Mac is away). */
    fun owns(deviceId: String) = device?.deviceId == deviceId

    fun toggle(context: Context, device: Device) = main.post {
        val app = context.applicationContext
        if (player?.isPlaying == true) {
            stop(app)
            ringingActivity?.get()?.finish()
            return@post
        }
        this.device = device
        val power = app.getSystemService(PowerManager::class.java)
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || LifecycleHelper.isInForeground ->
                app.startActivity(activityIntent(app, device).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            power.isInteractive -> {
                start(app)
                notify(app, PendingIntent.getBroadcast(
                    app, 0,
                    Intent(app, FindMyPhoneReceiver::class.java).setAction(FindMyPhoneReceiver.ACTION_FOUND_IT)
                        .putExtra(FindMyPhoneReceiver.EXTRA_DEVICE_ID, device.deviceId).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ))
            }
            else -> notify(app, PendingIntent.getActivity(app, 0, activityIntent(app, device), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
    }

    private fun activityIntent(context: Context, device: Device) =
        Intent(context, FindMyPhoneActivity::class.java).putExtra("deviceId", device.deviceId)

    /** Starts ringing (the ringing screen calls this when it shows). */
    fun start(context: Context) {
        if (player?.isPlaying == true) return
        val app = context.applicationContext
        runCatching {
            val prefs = PreferenceManager.getDefaultSharedPreferences(app)
            val ringtone = prefs.getString(app.getString(R.string.findmyphone_preference_key_ringtone), "")
                ?.takeIf { it.isNotEmpty() }?.let(Uri::parse) ?: Settings.System.DEFAULT_RINGTONE_URI
            val audio = app.getSystemService(AudioManager::class.java)
            player = MediaPlayer().apply {
                setDataSource(app, ringtone)
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED).build(),
                )
                setWakeMode(app, PowerManager.SCREEN_DIM_WAKE_LOCK)
                isLooping = true
                prepare()
            }
            // Heard even when the phone is silent; the volume is put back afterwards.
            previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            player?.start()
            val flash = prefs.getBoolean(app.getString(R.string.findmyphone_preference_key_flashlight), false) &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (flash) flashlight = FlashlightManager(app).apply { startFlashing() }
            report(app, true)
        }.onFailure { Log.w(TAG, "Couldn't ring", it) }
    }

    /** Stops ringing and puts the volume back. */
    fun stop(context: Context) {
        val app = context.applicationContext
        val wasPlaying = player?.isPlaying == true
        player?.runCatching { stop(); release() }
        player = null
        flashlight?.stopFlashing()
        flashlight = null
        if (previousVolume != -1) {
            app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
            previousVolume = -1
        }
        app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        if (wasPlaying) report(app, false)
        ringingActivity = null
    }

    private fun report(context: Context, on: Boolean) {
        val device = device ?: return
        Thread { CompanionMessages.sendStatusNotification(context, device, "ge.connect.ring:", "Find my phone", if (on) "on" else "off", true) }.start()
    }

    private fun notify(context: Context, intent: PendingIntent) {
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setFullScreenIntent(intent, true)
            .setContentIntent(intent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(true)
            .setContentTitle(context.getString(R.string.findmyphone_found))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
}
