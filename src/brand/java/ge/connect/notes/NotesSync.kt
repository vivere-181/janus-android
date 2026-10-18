/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.notes

import android.app.Application
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import ge.connect.sync.CompanionMessages
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect

/**
 * Notes sync with the owner's Macs (PROTOCOL.md (janus-mac docs) → ge.connect.notes, R29). Mac → phone: ping
 * `ge.connect.notes:<json>`; phone → Mac: companion notification `ge.connect.notes:<uuid>`. We push to a Mac only after
 * its `hello` on the current connection (so older Mac apps never get these), then everything it doesn't have.
 */
object NotesSync {
    private const val TAG = "NotesSync"
    private const val CALLBACK_KEY = "ge.connect.notes"
    private const val DEBOUNCE_MILLIS = 1_000L
    private const val MAX_NOTES = 50
    private const val MAX_CHARS = 200_000
    /** Through Janus Cloud an envelope is at most 96 KiB, sealed and base64'd (R37). */
    private const val CLOUD_MAX_CHARS = 40_000

    private lateinit var app: Application
    private val worker by lazy { Handler(HandlerThread(TAG).apply { start() }.looper) }
    /** Macs that said hello on their current connection. Worker thread only. */
    private val ready = HashSet<String>()

    fun start(app: Application) {
        this.app = app
        KdeConnect.getInstance().addDeviceListChangedCallback(CALLBACK_KEY) {
            worker.post {
                // A new connection needs a new hello; a Mac that's away stays ready through Janus Cloud (R37).
                val reachable = KdeConnect.getInstance().devices.values
                    .filter { it.isReachable || ge.connect.cloud.CloudCompanion.away(it) }.map { it.deviceId }.toSet()
                ready.retainAll(reachable)
            }
        }
    }

    /** A note changed here: push it ~1 s after editing stops. */
    fun localChange() {
        if (!::app.isInitialized) return
        worker.removeCallbacks(pushAll)
        worker.postDelayed(pushAll, DEBOUNCE_MILLIS)
    }

    private val pushAll = Runnable { macs().forEach(::push) }

    private fun macs() = KdeConnect.getInstance().devices.values.filter {
        it.deviceId in ready && (it.isReachable || ge.connect.cloud.CloudCompanion.away(it)) && it.isPaired &&
            ge.connect.devices.DeviceOwnership.isMine(it)
    }

    /** "ge.connect.notes:<json>" from a Mac. */
    fun handle(device: Device, json: String) = worker.post {
        val message = runCatching { JSONObject(json) }.getOrElse { Log.w(TAG, "Bad notes message from ${device.name}"); return@post }
        when (message.optString("op")) {
            "hello" -> {
                ready += device.deviceId
                push(device)
                // R30: files announced earlier that never arrived (attachments need the LAN, R37)
                if (device.isReachable) NoteAttachments.requestMissing(device)
            }
            "push" -> {
                val notes = message.optJSONArray("notes") ?: JSONArray()
                val acks = JSONArray()
                var changed = false
                for (i in 0 until notes.length()) {
                    val o = notes.optJSONObject(i) ?: continue
                    val note = runCatching { Note.fromJson(o) }.getOrNull() ?: continue
                    val base = o.optString("base").takeIf { o.has("base") && !o.isNull("base") && it.isNotEmpty() }
                    if (NotesStore.receive(device.deviceId, note, base)) changed = true
                    acks.put(JSONObject().put("id", note.id).put("rev", note.rev))
                }
                send(device, JSONObject().put("v", 1).put("op", "ack").put("acks", acks))
                if (changed && device.isReachable) NoteAttachments.requestMissing(device) // R30: files these notes refer to
                // Pass what arrived on to other Macs, and send back conflict copies made here.
                if (changed) localChange()
            }
            "ack" -> {
                val acks = message.optJSONArray("acks") ?: JSONArray()
                for (i in 0 until acks.length()) {
                    val a = acks.optJSONObject(i) ?: continue
                    NotesStore.ack(device.deviceId, a.optString("id").ifEmpty { continue }, a.optString("rev").ifEmpty { continue })
                }
            }
            // R30 attachments
            "want" -> if (device.isReachable) NoteAttachments.wanted(device, strings(message.optJSONArray("files")))
            "file" -> NoteAttachments.announced(message.optString("name"))
            "have" -> NoteAttachments.had(device, strings(message.optJSONArray("files")))
            else -> Log.i(TAG, "Ignoring notes op ${message.optString("op")}")
        }
    }

    /** Everything [device] doesn't have yet, in batches of ≤ 50 notes / ~200 KB. */
    private fun push(device: Device) {
        val pending = NotesStore.pendingFor(device.deviceId)
        if (pending.isEmpty()) return
        var batch = JSONArray()
        var size = 0
        val maxChars = if (device.isReachable) MAX_CHARS else CLOUD_MAX_CHARS
        for ((note, base) in pending) {
            val o = note.toJson().apply { base?.let { put("base", it) } }
            val length = o.toString().length
            if (batch.length() > 0 && (batch.length() >= MAX_NOTES || size + length > maxChars)) {
                sendPush(device, batch)
                batch = JSONArray(); size = 0
            }
            batch.put(o); size += length
        }
        if (batch.length() > 0) sendPush(device, batch)
        Log.i(TAG, "Pushed ${pending.size} note(s) to ${device.name}")
    }

    private fun strings(array: JSONArray?): List<String> = (0 until (array?.length() ?: 0)).mapNotNull { array?.optString(it)?.takeIf { s -> s.isNotEmpty() } }

    /** `want` / `have` with a list of attachment names (R30). */
    fun sendOp(device: Device, op: String, files: List<String>) = worker.post {
        send(device, JSONObject().put("v", 1).put("op", op).put("files", JSONArray(files)))
    }

    /** `file`: the share that follows is this attachment (R30). Blocks; called from the attachments thread. */
    fun sendFileOp(device: Device, name: String, size: Long) =
        send(device, JSONObject().put("v", 1).put("op", "file").put("name", name).put("size", size))

    private fun sendPush(device: Device, notes: JSONArray) = send(device, JSONObject().put("v", 1).put("op", "push").put("notes", notes))

    private fun send(device: Device, message: JSONObject) {
        CompanionMessages.sendStatusNotification(app, device, "ge.connect.notes:", "Notes", message.toString(), silent = true)
    }
}
