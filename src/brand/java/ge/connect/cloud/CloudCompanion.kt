/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.cloud

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import ge.connect.sync.CompanionMessages
import ge.connect.transfers.Transfers
import org.json.JSONObject
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.IntentHelper
import org.kde.kdeconnect_tp.R

/**
 * Companion messages, links and text, and Mac actions through Janus Cloud (R37, shared/CLOUD.md §13), only while the
 * Mac isn't on the LAN: what would have been a `ge.connect.` ping arrives as `c` {cmd}, what would have been a
 * companion notification leaves as `c` {nid, text}.
 */
object CloudCompanion {
    private const val TAG = "CloudCompanion"
    /** Mac → phone commands taken over the cloud (the part before the first ':'). */
    private val IN_KINDS = setOf("notes", "status", "ringer", "flashlight", "dnd", "macstate", "macaction", "calendar", "findmyphone")
    /** Phone → Mac companion notifications sent over the cloud (`ge.connect.<kind>:` / `ge.connect.<kind>`). */
    private val OUT_KINDS = setOf("notes", "status", "ring", "dnd", "device", "ping", "calendar")

    private lateinit var app: Context
    /** Envelopes go out in order, one at a time, off the callers' threads. */
    private val sender by lazy { Handler(HandlerThread(TAG).apply { start() }.looper) }

    fun init(context: Context) { app = context.applicationContext }

    /** One of the owner's Macs that's away from the LAN but linked through the cloud. */
    fun away(device: Device): Boolean = device.isPaired && !device.isReachable && JanusCloud.peer(device.deviceId) != null &&
        ge.connect.devices.DeviceOwnership.isMine(device)

    /** The owner's Macs that are away but reachable through the cloud. */
    fun awayMacs(): List<Device> = KdeConnect.getInstance().devices.values.filter(::away)

    /** Whether a companion notification with this id (prefix) may go through the cloud. */
    fun carries(notificationId: String): Boolean =
        notificationId.removePrefix("ge.connect.").substringBefore(':') in OUT_KINDS

    /** A companion notification [nid] with [text] for an away Mac. Doesn't block. @return whether it was queued */
    fun send(device: Device, nid: String, text: String): Boolean {
        val peer = JanusCloud.peer(device.deviceId) ?: return false
        post(peer, "c", JSONObject().put("nid", nid).put("text", text))
        return true
    }

    /** Shared text or a link for an away Mac (the `text` envelope). */
    fun sendText(device: Device, text: String, link: Boolean) {
        val peer = JanusCloud.peer(device.deviceId) ?: return
        ge.connect.sync.ClipboardGuard.sent(text) // the Mac copies it; not shown again when its clipboard comes back
        post(peer, "text", JSONObject().put("text", text).put("link", link))
        Transfers.message(device, Transfers.Direction.OUT, if (link) Transfers.Kind.LINK else Transfers.Kind.TEXT, text, cloud = true)
    }

    /** A Mac action (`lock`, `mute`… without `ge-`); the Mac answers with `macaction:<name>:ok|failed` over the cloud. */
    fun action(device: Device, name: String) {
        val peer = JanusCloud.peer(device.deviceId) ?: return
        post(peer, "action", JSONObject().put("name", name.removePrefix("ge-")))
    }

    private fun post(peer: CloudPeer, type: String, fields: JSONObject) = sender.post {
        runCatching { JanusCloud.send(peer, type, fields) }
            .onFailure { Log.w(TAG, "Couldn't send $type to ${peer.name} through the cloud: ${it.message}") }
    }

    /** `c` or `text` from [peer]. @return whether it was one of ours */
    fun received(peer: CloudPeer, type: String, e: JSONObject): Boolean {
        val device = KdeConnect.getInstance().getDevice(peer.kdeId) ?: return type == "c" || type == "text"
        when (type) {
            "c" -> {
                val cmd = e.optString("cmd")
                if (cmd.substringBefore(':') !in IN_KINDS) Log.i(TAG, "Not taking ${cmd.substringBefore(':')} over the cloud")
                else {
                    CompanionMessages.handleCommand(app, device, "ge.connect.$cmd")
                    // The Mac changed something: confirm it now rather than after the once-a-minute limit (§13).
                    if (cmd.substringBefore(':') in setOf("ringer", "flashlight", "dnd")) ge.connect.sync.PhoneStatus.reportSoon(forceFor = device.deviceId)
                }
            }
            "text" -> {
                val text = e.optString("text")
                if (text.isEmpty() || !ge.connect.devices.DeviceOwnership.isMine(device)) return true
                if (e.optBoolean("link")) {
                    // Like a LAN link share: opened (or offered in a notification when we're in the background).
                    Transfers.message(device, Transfers.Direction.IN, Transfers.Kind.LINK, text, cloud = true)
                    val intent = Intent(Intent.ACTION_VIEW, text.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        runCatching { IntentHelper.startActivityFromBackgroundOrCreateNotification(app, intent, text) }
                    }
                } else {
                    // Like a LAN text share: on the clipboard.
                    Transfers.message(device, Transfers.Direction.IN, Transfers.Kind.TEXT, text, cloud = true)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        app.getSystemService(ClipboardManager::class.java)?.text = text
                        Toast.makeText(app, R.string.shareplugin_text_saved, Toast.LENGTH_LONG).show()
                    }
                }
            }
            else -> return false
        }
        return true
    }
}
