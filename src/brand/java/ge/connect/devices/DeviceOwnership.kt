/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.devices

import android.content.Context
import androidx.core.content.edit
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.TrustedDevices

/**
 * Whether a paired device is the owner's own ("mine") or someone else's. My devices get everything; with someone
 * else's, only sharing works (files, photos, links, text you send on purpose) — each phone decides this for itself,
 * so nothing personal is sent to, or accepted from, another person's device whatever it asks for.
 */
object DeviceOwnership {
    private const val PREFS = "ge_connect_ownership"
    private const val MIGRATED = "migrated_v1"
    const val MINE = "mine"
    const val OTHER = "other"

    /** The only plugins that load for someone else's device. */
    val ALLOWED_FOR_OTHERS = setOf("SharePlugin")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Devices paired before this feature existed are the owner's own: nothing changes for them. */
    fun migrate(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(MIGRATED, false)) return
        prefs.edit {
            TrustedDevices.getAllTrustedDevices(context).forEach { putString(it, MINE) }
            putBoolean(MIGRATED, true)
        }
    }

    /** Unanswered (just paired) counts as someone else's until the owner says otherwise. */
    fun isMine(context: Context, deviceId: String) = prefs(context).getString(deviceId, null) == MINE
    fun isMine(device: Device) = isMine(device.context, device.deviceId)
    fun isAnswered(context: Context, deviceId: String) = prefs(context).contains(deviceId)

    fun set(context: Context, deviceId: String, mine: Boolean) {
        prefs(context).edit { putString(deviceId, if (mine) MINE else OTHER) }
        // Load or unload the personal features right away.
        KdeConnect.getInstance().getDevice(deviceId)?.launchBackgroundReloadPluginsFromSettings()
    }

    fun forget(context: Context, deviceId: String) = prefs(context).edit { remove(deviceId) }

    /** For Device.isPluginEnabled: personal plugins never load for someone else's device. */
    fun allows(context: Context, deviceId: String, pluginKey: String) =
        pluginKey in ALLOWED_FOR_OTHERS || isMine(context, deviceId)
}
