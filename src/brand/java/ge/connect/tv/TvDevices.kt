/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.tv

import org.kde.kdeconnect.Device
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.helpers.DeviceHelper

/**
 * TVs (owner, 2026-10-16): a TV is for watching and casting, not for files, the clipboard, notes or Find. Between a
 * TV and anything else (on the phone for a paired TV, on the TV for everything) only these features load:
 * the pointer and keyboard (mousepad and its receivers), media control, and screen sharing.
 */
object TvDevices {
    private val TV_PLUGINS = setOf(
        "MousePadPlugin", "MouseReceiverPlugin", "RemoteKeyboardPlugin", "RemoteKeyboardIMEPlugin",
        "MprisPlugin", "MprisReceiverPlugin", "GeScreenPlugin", "GeTvPlugin",
    )

    fun isTv(device: Device) = device.deviceType == DeviceType.TV

    /** This app runs on a TV. */
    val thisIsTv: Boolean get() = DeviceHelper.isTv

    /** For Device.isPluginEnabled. */
    fun allows(device: Device, pluginKey: String) = !(isTv(device) || thisIsTv) || pluginKey in TV_PLUGINS
}
