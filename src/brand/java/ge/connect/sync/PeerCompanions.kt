/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect_tp.R

/**
 * Companion messages (`ge.connect.*` notifications) that another phone running our app sends us — the ones it sends
 * to every connected device, the Mac included. Used by the phone ↔ phone features; unknown kinds are ignored.
 */
object PeerCompanions {
    fun handle(context: Context, device: Device, np: NetworkPacket) {
        val id = np.getStringOrNull("id") ?: return
        if (np.getBoolean("isCancel")) return
        val text = np.getStringOrNull("text").orEmpty()
        when {
            // Ringer, DND and flashlight (sent on connect and on every change).
            id.startsWith("ge.connect.status:") -> PeerState.applyStatus(device.deviceId, text)
            // Result of Decline/Mute we sent: e.g. "decline:failed:Muted instead…"
            id.startsWith("ge.connect.callresult:") -> {
                val parts = text.split(':', limit = 3)
                if (parts.getOrNull(1) == "failed") {
                    val reason = parts.getOrNull(2).orEmpty()
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, context.getString(R.string.remote_calls_result_failed, device.name, reason), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
