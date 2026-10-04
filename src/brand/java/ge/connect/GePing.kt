/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import ge.connect.sync.CompanionMessages
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect_tp.R

/**
 * "Send ping" that the Mac app can show (PROTOCOL.md (janus-mac docs) → ge.connect.ping, R4/R5): the Mac
 * engine swallows kdeconnect.ping, so the ping also goes out as a status notification.
 */
object GePing {
    fun send(context: Context, device: Device, message: String = "") {
        device.sendPacket(NetworkPacket("kdeconnect.ping")) // other desktops, and DeviceStats
        Thread {
            val sent = CompanionMessages.sendStatusNotification(context, device, "ge.connect.ping:", "Ping", message, silent = false)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, if (sent) R.string.ping_sent else R.string.ping_not_sent, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
}
