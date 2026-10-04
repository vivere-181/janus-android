/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.camera

import android.graphics.BitmapFactory
import android.util.Base64
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R

/**
 * Remote camera between two phones running our app. The viewer sends ge.connect.camera.request
 * (start / stop / shutter [delay] / switch); the camera phone answers with ge.connect.camera.frame (small JPEG
 * previews) and ge.connect.camera.status (waiting, ready, countdown, captured, stopped). Photos themselves go
 * through SharePlugin as a normal file transfer.
 */
@LoadablePlugin
class GeCameraPlugin : Plugin() {
    override val displayName: String get() = context.getString(R.string.camera_title)
    override val description: String get() = context.getString(R.string.camera_desc)
    override val supportedPacketTypes = arrayOf(REQUEST, FRAME, STATUS)
    override val outgoingPacketTypes = arrayOf(REQUEST, FRAME, STATUS)

    private val fromPhone get() = device.deviceType == DeviceType.PHONE || device.deviceType == DeviceType.TABLET

    override fun onDestroy() {
        CameraHost.controllerGone(device.deviceId)
        CameraViewer.update(device.deviceId) { it.copy(status = "stopped", frame = null) }
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (!fromPhone) return false
        when (np.type) {
            REQUEST -> CameraHost.onRequest(context, device, np.getString("action", ""), np.getInt("delay", 0))
            FRAME -> {
                val bytes = runCatching { Base64.decode(np.getString("jpeg", ""), Base64.NO_WRAP) }.getOrNull() ?: return true
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return true
                CameraViewer.update(device.deviceId) { it.copy(frame = bitmap, status = if (it.status == "waiting" || it.status == "starting") "ready" else it.status) }
            }
            STATUS -> CameraViewer.update(device.deviceId) {
                it.copy(
                    status = np.getString("state", ""),
                    lens = np.getString("lens", it.lens),
                    countdown = np.getInt("countdown", 0),
                )
            }
        }
        return true
    }

    companion object {
        const val REQUEST = "ge.connect.camera.request"
        const val FRAME = "ge.connect.camera.frame"
        const val STATUS = "ge.connect.camera.status"
    }
}
