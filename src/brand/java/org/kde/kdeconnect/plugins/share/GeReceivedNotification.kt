/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.kde.kdeconnect.plugins.share

import android.net.Uri
import org.kde.kdeconnect.Device

/** Upstream's "file received" notification (picture preview, tap to open) for files that didn't come over the LAN. */
object GeReceivedNotification {
    fun show(device: Device, title: String, uri: Uri, mime: String, name: String) {
        ReceiveNotification(device, 0).apply {
            setFinished(title)
            setURI(uri, mime, name)
            show()
        }
    }
}
