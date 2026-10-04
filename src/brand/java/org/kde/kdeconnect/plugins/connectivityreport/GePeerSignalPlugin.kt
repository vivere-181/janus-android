/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

// ge.connect: lives next to upstream's ConnectivityReportPlugin (same packet type).
package org.kde.kdeconnect.plugins.connectivityreport

import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R

/** Receives another phone's mobile signal (phone ↔ phone "at a glance"); declaring it makes that phone send it. */
@LoadablePlugin
class GePeerSignalPlugin : Plugin() {
    override val displayName: String get() = context.getString(R.string.peer_signal_title)
    override val description: String get() = context.getString(R.string.peer_signal_desc)
    override val supportedPacketTypes = arrayOf("kdeconnect.connectivity_report")
    override val outgoingPacketTypes = emptyArray<String>()

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (!ge.connect.sync.PeerState.isPhone(device)) return false
        ge.connect.sync.PeerState.applyConnectivity(device.deviceId, np)
        return true
    }
}
