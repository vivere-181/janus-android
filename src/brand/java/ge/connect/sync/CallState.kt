/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.sync

import org.json.JSONObject
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket

/**
 * The call in progress, for the Mac's live call card (PROTOCOL.md (janus-mac docs) → ge.connect.callstate, R23): the Mac engine
 * doesn't pass telephony "talking"/cancel packets on, so the state also goes out as a companion notification.
 * id = ge.connect.callstate:<call key>, text = JSON {state: ringing|talking, number, name, start?, now}; a state change
 * is cancel + repost (R22); the end of the call cancels it. One instance per device (from TelephonyPlugin).
 */
class CallState(private val device: Device) {
    private var key: String? = null
    private var number: String = ""
    private var name: String = ""

    private val id get() = "ge.connect.callstate:$key"

    fun ringing(number: String?, name: String?) {
        end()
        key = System.currentTimeMillis().toString()
        this.number = number.orEmpty()
        this.name = name?.takeIf { it != number }.orEmpty()
        post(JSONObject().put("state", "ringing"))
    }

    /** Answered (after ringing) or an outgoing call that started. */
    fun talking(number: String?, name: String?) {
        val answered = key != null
        if (!answered) {
            key = System.currentTimeMillis().toString()
            this.number = number.orEmpty()
            this.name = name?.takeIf { it != number }.orEmpty()
        } else {
            cancel()
        }
        post(JSONObject().put("state", "talking").put("start", System.currentTimeMillis()))
    }

    fun end() {
        if (key == null) return
        cancel()
        key = null
    }

    private fun post(state: JSONObject) {
        device.sendPacket(NetworkPacket("kdeconnect.notification").apply {
            this["id"] = id
            this["appName"] = "Call"
            this["title"] = "Call"
            this["text"] = state.put("number", number).put("name", name).put("now", System.currentTimeMillis()).toString()
            this["ticker"] = ""
            this["isClearable"] = false
            this["silent"] = true
            this["time"] = System.currentTimeMillis().toString()
        })
    }

    private fun cancel() = device.sendPacket(NetworkPacket("kdeconnect.notification").apply {
        this["id"] = id
        this["isCancel"] = true
    })
}
