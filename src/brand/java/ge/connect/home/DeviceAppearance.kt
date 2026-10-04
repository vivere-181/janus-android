/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.home

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.core.content.edit
import org.kde.kdeconnect.Device
import org.kde.kdeconnect_tp.R

/** How a computer is drawn: its actual model (our own illustrations) or the generic icon. Per device. */
enum class DeviceAppearance(@StringRes val label: Int, @DrawableRes val art: Int?) {
    GENERIC(R.string.appearance_generic, null),
    MACBOOK_PRO_SPACE_BLACK(R.string.appearance_mbp_space_black, R.drawable.art_macbook_pro_space_black),
    MACBOOK_PRO_SILVER(R.string.appearance_mbp_silver, R.drawable.art_macbook_pro_silver);

    companion object {
        private const val PREFS = "ge_connect_device_appearance"

        /** Stored choice; a MacBook Pro defaults to Space Black (the owner's machine). */
        fun of(context: Context, device: Device): DeviceAppearance {
            val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(device.deviceId, null)
            stored?.let { name -> entries.firstOrNull { it.name == name } }?.let { return it }
            // What the Mac says it is (R28), else its name.
            val market = ge.connect.sync.MacInfo.market(context, device.deviceId) ?: device.name
            return if (market.contains("MacBook Pro", ignoreCase = true)) MACBOOK_PRO_SPACE_BLACK else GENERIC
        }

        fun set(context: Context, deviceId: String, value: DeviceAppearance) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(deviceId, value.name) }
    }
}

/**
 * The device's illustration if it has one, else nothing (callers fall back to their icon style).
 * Illustrations are 3:2; [width] sets the size.
 */
@Composable
fun DeviceArtOrNull(device: Device?, width: Dp, modifier: Modifier = Modifier): (@Composable () -> Unit)? {
    val context = LocalContext.current
    val art = device?.let { DeviceAppearance.of(context, it).art } ?: return null
    return { Image(painterResource(art), null, modifier.size(width, width * 2 / 3)) }
}
