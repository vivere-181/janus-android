/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.camera

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket

/** Viewer side: the latest preview frame and status from each camera phone. */
object CameraViewer {
    data class Feed(
        val frame: Bitmap? = null,
        /** starting, waiting (camera phone must open the camera), ready, countdown, captured, stopped, denied */
        val status: String = "starting",
        val lens: String = "back",
        val countdown: Int = 0,
    )

    private val _feeds = MutableStateFlow<Map<String, Feed>>(emptyMap())
    val feeds: StateFlow<Map<String, Feed>> = _feeds.asStateFlow()

    fun update(deviceId: String, block: (Feed) -> Feed) = _feeds.update { it + (deviceId to block(it[deviceId] ?: Feed())) }
    fun reset(deviceId: String) = _feeds.update { it + (deviceId to Feed()) }

    fun request(device: Device, action: String, delay: Int = 0) =
        device.sendPacket(NetworkPacket(GeCameraPlugin.REQUEST).apply {
            this["action"] = action
            if (delay > 0) this["delay"] = delay
        })
}
