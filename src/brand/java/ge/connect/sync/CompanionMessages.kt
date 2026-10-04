/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect_tp.R
import java.util.UUID

/**
 * Our messages to/from the companion Mac app over standard packets (PROTOCOL.md (janus-mac docs) → Custom
 * features): status notifications phone → Mac, and ping "commands" Mac → phone.
 */
object CompanionMessages {
    private const val TAG = "CompanionMessages"
    private const val COMMAND_PREFIX = "ge.connect."
    private const val CANCEL_AFTER_MILLIS = 10_000L

    /** Mac app support for ge.connect.transfer (R9) installed 2026-09-27; switch off for older Mac apps. */
    private const val TRANSFER_ANNOUNCE_ENABLED = true

    /**
     * A kdeconnect.notification the Mac app recognizes by its id prefix and never lists as a phone
     * notification; cancelled again after 10 s. Blocks while sending, so call off the main thread.
     * @return whether the notification packet was sent
     */
    fun sendStatusNotification(context: Context, device: Device, idPrefix: String, title: String, text: String, silent: Boolean): Boolean {
        val id = idPrefix + UUID.randomUUID()
        val sent = device.sendPacketBlocking(NetworkPacket("kdeconnect.notification").apply {
            this["id"] = id
            this["appName"] = context.getString(R.string.app_name)
            this["title"] = title
            this["text"] = text
            this["ticker"] = ""
            this["isClearable"] = true
            this["silent"] = silent
            this["time"] = System.currentTimeMillis().toString()
        })
        Handler(Looper.getMainLooper()).postDelayed({
            device.sendPacket(NetworkPacket("kdeconnect.notification").apply {
                this["id"] = id
                this["isCancel"] = true
            })
        }, CANCEL_AFTER_MILLIS)
        return sent
    }

    /**
     * Tells the Mac app how big a file transfer is before its files arrive, so it can show a
     * percentage (PROTOCOL.md (janus-mac docs) → ge.connect.transfer, R9). Text: "<totalBytes>|<numberOfFiles>".
     * Blocks while sending; call from the upload job's thread.
     */
    fun announceTransfer(context: Context, device: Device, totalBytes: Long, numberOfFiles: Int) {
        if (!TRANSFER_ANNOUNCE_ENABLED) return
        sendStatusNotification(context, device, "ge.connect.transfer:", "Transfer", "$totalBytes|$numberOfFiles", silent = true)
    }

    /**
     * Pings whose message starts with "ge.connect." are commands from the Mac app: handle known ones,
     * silently ignore the rest, never show them. @return true if [message] was a command
     */
    fun handleCommand(context: Context, device: Device, message: String?): Boolean {
        if (message == null || !message.startsWith(COMMAND_PREFIX)) return false
        // Commands (ringer, DND, calls, dialing…) are only taken from the owner's own devices; swallowed otherwise.
        if (!ge.connect.devices.DeviceOwnership.isMine(device)) return true
        val command = message.removePrefix(COMMAND_PREFIX)
        when {
            command == "dnd:on" -> DndSync.applyFromComputer(context, on = true).also { PhoneStatus.reportSoon() }
            command == "dnd:off" -> DndSync.applyFromComputer(context, on = false).also { PhoneStatus.reportSoon() }
            command.startsWith("macstate:") || command.startsWith("macaction:") -> MacState.handleCommand(device.deviceId, command) // R19
            command == "status" -> PhoneStatus.reportSoon(forceFor = device.deviceId) // the Mac app (re)started and asks for our status (R18)
            command.startsWith("ringer:") || command.startsWith("flashlight:") -> PhoneStatus.handleCommand(command)
            command.startsWith("dial:") -> CallCommands.dial(context, device, command.removePrefix("dial:"))
            command.startsWith("call:") -> CallCommands.call(context, device, command.removePrefix("call:"))
            command == "calendar:refresh" -> CalendarSync.refresh(device) // R26
            command.startsWith("notes:") -> ge.connect.notes.NotesSync.handle(device, command.removePrefix("notes:")) // R29
            command.startsWith("mirror:") -> Mirroring.handleCommand(context, device, command.removePrefix("mirror:")) // R25
            else -> Log.i(TAG, "Ignoring unknown command from ${device.name}: $message")
        }
        return true
    }
}
