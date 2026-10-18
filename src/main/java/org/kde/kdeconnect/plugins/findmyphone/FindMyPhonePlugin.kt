/*
 * SPDX-FileCopyrightText: 2015 David Edmundson <david@davidedmundson.co.uk>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.kde.kdeconnect.plugins.findmyphone

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.DeviceHelper
import org.kde.kdeconnect.helpers.LifecycleHelper
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R
import java.io.IOException
import java.lang.ref.WeakReference

@LoadablePlugin
class FindMyPhonePlugin : Plugin() {
    private lateinit var notificationManager: NotificationManager
    private var notificationId = 0
    private var audioManager: AudioManager? = null
    private var mediaPlayer: MediaPlayer? = null
    private var previousVolume = -1
    private lateinit var powerManager: PowerManager
    private var flashlightManager: FlashlightManager? = null
    /** ge.connect: the "Your phone is ringing" screen while it's shown, so a second request can close it. */
    internal var ringingActivity: WeakReference<Activity>? = null

    override val displayName: String
        get() = when (DeviceHelper.deviceType) {
            DeviceType.TV -> context.getString(R.string.findmyphone_title_tv)
            DeviceType.TABLET -> context.getString(R.string.findmyphone_title_tablet)
            DeviceType.PHONE -> context.getString(R.string.findmyphone_title)
            else -> context.getString(R.string.findmyphone_title)
        }

    override val description: String
        get() = context.getString(R.string.findmyphone_description)

    @Throws(IOException::class)
    override fun onCreate() {
        notificationManager = context.getSystemService<NotificationManager>()!!
        notificationId = System.currentTimeMillis().toInt()
        audioManager = context.getSystemService<AudioManager>()
        powerManager = context.getSystemService<PowerManager>()!!
        flashlightManager = FlashlightManager(context)

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val ringtoneString = prefs.getString(context.getString(R.string.findmyphone_preference_key_ringtone), "")!!
        val ringtone: Uri = if (ringtoneString.isEmpty()) {
            Settings.System.DEFAULT_RINGTONE_URI
        } else {
            Uri.parse(ringtoneString)
        }

        val mediaPlayer = MediaPlayer()
        this.mediaPlayer = mediaPlayer
        mediaPlayer.setDataSource(context, ringtone)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()
        @Suppress("DEPRECATION")
        mediaPlayer.setWakeMode(context, PowerManager.SCREEN_DIM_WAKE_LOCK) // Prevent screen turning off, requires WAKE_LOCK permission
        mediaPlayer.setAudioAttributes(audioAttributes)
        mediaPlayer.isLooping = true
        mediaPlayer.prepare()
    }

    override fun onDestroy() {
        if (mediaPlayer!!.isPlaying) {
            stopPlaying()
        }
        audioManager = null
        mediaPlayer!!.release()
        mediaPlayer = null

        flashlightManager = null
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        // ge.connect: a request while already ringing stops it (Find Phone is a toggle on the Mac, R12)
        if (mediaPlayer?.isPlaying == true) {
            stopPlaying()
            stopFlashing()
            hideNotification()
            ringingActivity?.get()?.finish()
            return true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || LifecycleHelper.isInForeground) {
            val intent = Intent(context, FindMyPhoneActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(FindMyPhoneActivity.EXTRA_DEVICE_ID, device.deviceId)
            context.startActivity(intent)
        } else {
            if (powerManager.isInteractive) {
                startPlaying()
                startFlashing()
                showBroadcastNotification()
            } else {
                showActivityNotification()
            }
        }
        return true
    }

    private fun showBroadcastNotification() {
        val intent = Intent(context, FindMyPhoneReceiver::class.java)
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        intent.setAction(FindMyPhoneReceiver.ACTION_FOUND_IT)
        intent.putExtra(FindMyPhoneReceiver.EXTRA_DEVICE_ID, device.deviceId)

        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        createNotification(pendingIntent)
    }

    private fun showActivityNotification() {
        val intent = Intent(context, FindMyPhoneActivity::class.java)
        intent.putExtra(FindMyPhoneActivity.EXTRA_DEVICE_ID, device.deviceId)

        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        createNotification(pi)
    }

    private fun createNotification(pendingIntent: PendingIntent) {
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
        notification
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(false)
            .setFullScreenIntent(pendingIntent, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(true)
            .setContentTitle(context.getString(R.string.findmyphone_found))
        notification.setGroup("BackgroundService")

        notificationManager.notify(notificationId, notification.build())
    }

    internal fun startPlaying() {
        val mediaPlayer = mediaPlayer
        if (mediaPlayer != null && !mediaPlayer.isPlaying) {
            val audioManager = audioManager!!
            // Make sure we are heard even when the phone is silent, restore original volume later
            previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

            mediaPlayer.start()
            reportRinging(true) // ge.connect
        }
    }

    /** ge.connect: tell the Mac app whether the phone is ringing (PROTOCOL.md (janus-mac docs) → ge.connect.ring, R12). */
    private fun reportRinging(on: Boolean) {
        Thread {
            ge.connect.sync.CompanionMessages.sendStatusNotification(
                context, device, "ge.connect.ring:", "Find my phone", if (on) "on" else "off", true)
        }.start()
    }

    internal fun startFlashing() {
        if (isFlashlightEnabledInSettings() && isPermissionGranted(Manifest.permission.CAMERA)) {
            flashlightManager!!.startFlashing()
        }
    }

    internal fun hideNotification() {
        notificationManager.cancel(notificationId)
    }

    internal fun stopPlaying() {
        val audioManager = audioManager
            // The Plugin was destroyed (probably the device disconnected)
            ?: return
        val mediaPlayer = mediaPlayer!!

        val wasPlaying = mediaPlayer.isPlaying // ge.connect
        if (previousVolume != -1) {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
        }
        mediaPlayer.stop()
        if (wasPlaying) reportRinging(false) // ge.connect
        try {
            mediaPlayer.prepare()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    internal fun stopFlashing() {
        flashlightManager!!.stopFlashing()
    }

    internal fun isPlaying(): Boolean {
        return mediaPlayer!!.isPlaying
    }

    override val supportedPacketTypes: Array<String>
        get() = arrayOf(PACKET_TYPE_FINDMYPHONE_REQUEST)

    override val outgoingPacketTypes: Array<String>
        get() = emptyArray()

    override fun hasSettings(): Boolean {
        return true
    }

    override fun getSettingsFragment(activity: Activity): PluginSettingsFragment {
        return FindMyPhoneSettingsFragment.newInstance(pluginKey, device.deviceId, R.xml.findmyphoneplugin_preferences)
    }

    override val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

    override val permissionExplanation: Int
        get() = R.string.findmyphone_notifications_explanation

    private fun isFlashlightEnabledInSettings(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(context.getString(R.string.findmyphone_preference_key_flashlight), false)
    }

    companion object {
        const val PACKET_TYPE_FINDMYPHONE_REQUEST = "kdeconnect.findmyphone.request"
    }
}
