/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.share

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect_tp.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Sharing from someone else's device (DeviceOwnership): files wait for Accept / Decline (their first packet is held
 * with its payload stream open — the sender just waits — and Decline closes it); links and text become a
 * notification with Open / Copy instead of opening or overwriting the clipboard by themselves.
 */
object IncomingConsent {
    private const val CHANNEL = "ge_incoming_others"
    private const val TIMEOUT_MS = 120_000L
    const val ACTION_ACCEPT = "ge.connect.share.ACCEPT"
    const val ACTION_DECLINE = "ge.connect.share.DECLINE"
    const val ACTION_COPY = "ge.connect.share.COPY"
    const val EXTRA_ID = "id"
    const val EXTRA_TEXT = "text"

    /** One incoming transfer (all its files) waiting for the owner's answer. */
    private class Offer(val id: Int, val device: Device, val expected: Int, val totalSize: Long, val receive: (NetworkPacket) -> Unit) {
        val packets = mutableListOf<NetworkPacket>()
    }

    /** After an answer, the rest of that transfer's files follow the same answer (for a little while). */
    private class Answered(val accepted: Boolean, var remaining: Int, var lastAt: Long)

    /** Signals back to the sender (our app shows them; other apps ignore these share packets). */
    const val DECLINED_KEY = "geConnectDeclined"
    const val ACCEPTED_KEY = "geConnectAccepted"
    const val AWAITING_KEY = "geConnectAwaitingAccept"
    private const val FOLLOW_UP_MS = 60_000L

    private fun signal(device: Device, key: String) =
        device.sendPacket(NetworkPacket("kdeconnect.share.request").apply { this[key] = true })

    private val offers = ConcurrentHashMap<Int, Offer>()
    private val pendingByDevice = HashMap<String, Offer>()
    private val answered = HashMap<String, Answered>()
    private val main = Handler(Looper.getMainLooper())

    private fun channel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.consent_channel), NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    private fun broadcast(context: Context, action: String, id: Int, text: String? = null): PendingIntent = PendingIntent.getBroadcast(
        context, id * 7 + action.hashCode() % 7,
        Intent(context, IncomingConsentActions::class.java).setAction(action).putExtra(EXTRA_ID, id).putExtra(EXTRA_TEXT, text),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * A file from someone else's device. Each packet carries the whole transfer's file count and size, so the first one
     * asks once for all of them and the next ones join that request; after the answer, the rest of the transfer
     * follows it. [receive] runs on a background thread for each accepted file, in order.
     */
    fun handleFile(context: Context, device: Device, np: NetworkPacket, receive: (NetworkPacket) -> Unit) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            answered[device.deviceId]?.takeIf { it.remaining > 0 && now - it.lastAt < FOLLOW_UP_MS }?.let { previous ->
                previous.remaining--
                previous.lastAt = now
                if (previous.accepted) Thread { receive(np) }.start() else np.payload?.close()
                return
            }
            pendingByDevice[device.deviceId]?.let { offer ->
                offer.packets += np
                return
            }
            val offer = Offer(
                id = (device.deviceId + System.nanoTime()).hashCode(), device = device,
                expected = np.getInt("numberOfFiles", 1).coerceAtLeast(1),
                totalSize = np.getLong("totalPayloadSize", np.payloadSize), receive = receive,
            )
            offer.packets += np
            offers[offer.id] = offer
            pendingByDevice[device.deviceId] = offer
            notifyOffer(context, offer, np.getString("filename", ""))
        }
        signal(device, AWAITING_KEY)
        // No answer: decline, so the sender isn't left waiting.
        pendingByDevice[device.deviceId]?.let { offer -> main.postDelayed({ decide(context, offer.id, false) }, TIMEOUT_MS) }
    }

    @SuppressLint("MissingPermission")
    private fun notifyOffer(context: Context, offer: Offer, firstName: String) {
        channel(context)
        val size = Formatter.formatShortFileSize(context, offer.totalSize)
        val title = context.resources.getQuantityString(R.plurals.consent_files_title, offer.expected, offer.device.name, offer.expected)
        val text = if (offer.expected == 1) context.getString(R.string.consent_files_text, firstName, size) else size
        NotificationManagerCompat.from(context).notify(
            CHANNEL, offer.id,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .addAction(0, context.getString(R.string.consent_decline), broadcast(context, ACTION_DECLINE, offer.id))
                .addAction(0, context.getString(R.string.consent_accept), broadcast(context, ACTION_ACCEPT, offer.id))
                .setDeleteIntent(broadcast(context, ACTION_DECLINE, offer.id))
                .setTimeoutAfter(TIMEOUT_MS)
                .build(),
        )
    }

    fun decide(context: Context, id: Int, accepted: Boolean) {
        val offer = offers.remove(id) ?: return
        val held: List<NetworkPacket>
        synchronized(this) {
            pendingByDevice.remove(offer.device.deviceId)
            held = offer.packets.toList()
            // Files of this transfer still on their way get the same answer.
            answered[offer.device.deviceId] = Answered(accepted, (offer.expected - held.size).coerceAtLeast(0), System.currentTimeMillis())
        }
        NotificationManagerCompat.from(context).cancel(CHANNEL, id)
        Thread {
            if (accepted) {
                signal(offer.device, ACCEPTED_KEY)
                held.forEach(offer.receive)
            } else {
                // Tell the sender it was declined (our app shows "Declined"), then drop everything.
                signal(offer.device, DECLINED_KEY)
                held.forEach { it.payload?.close() }
            }
        }.start()
    }

    /** A link from someone else: Open / Copy (never opened by itself). */
    @SuppressLint("MissingPermission")
    fun link(context: Context, device: Device, url: String) {
        channel(context)
        val id = (device.deviceId + url + System.nanoTime()).hashCode()
        val open = PendingIntent.getActivity(
            context, id, Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationManagerCompat.from(context).notify(
            CHANNEL, id,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.consent_link_title, device.name))
                .setContentText(url)
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.consent_open), open)
                .addAction(0, context.getString(R.string.consent_copy), broadcast(context, ACTION_COPY, id, url))
                .build(),
        )
    }

    /** Text from someone else: shown with Copy (your clipboard stays yours). */
    @SuppressLint("MissingPermission")
    fun text(context: Context, device: Device, text: String) {
        channel(context)
        val id = (device.deviceId + text + System.nanoTime()).hashCode()
        NotificationManagerCompat.from(context).notify(
            CHANNEL, id,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.consent_text_title, device.name))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.consent_copy), broadcast(context, ACTION_COPY, id, text))
                .build(),
        )
    }

    fun copy(context: Context, id: Int, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, text))
        NotificationManagerCompat.from(context).cancel(CHANNEL, id)
        Toast.makeText(context, R.string.consent_copied, Toast.LENGTH_SHORT).show()
    }
}

class IncomingConsentActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(IncomingConsent.EXTRA_ID, 0)
        when (intent.action) {
            IncomingConsent.ACTION_ACCEPT -> IncomingConsent.decide(context, id, true)
            IncomingConsent.ACTION_DECLINE -> IncomingConsent.decide(context, id, false)
            IncomingConsent.ACTION_COPY -> intent.getStringExtra(IncomingConsent.EXTRA_TEXT)?.let { IncomingConsent.copy(context, id, it) }
        }
    }
}
