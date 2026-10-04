/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect

import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.helpers.DeviceHelper

/**
 * Announce the phone's own name (Settings > About phone > Device name) instead of upstream's
 * default, which is the model number or a name looked up online ("Galaxy S25").
 * Done once; after that the name in the app's Settings is the user's to change.
 */
object PhoneName {
    private const val KEY_ADOPTED = "ge_connect_system_name_adopted"

    fun adoptSystemNameOnce(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(KEY_ADOPTED, false)) return
        val name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            // The protocol forbids ' in names; the typographic apostrophe is allowed and looks the same.
            ?.replace('\'', '’')
            ?.let(DeviceHelper::filterInvalidCharactersFromDeviceNameAndLimitLength)
        prefs.edit {
            if (!name.isNullOrBlank()) putString(DeviceHelper.KEY_DEVICE_NAME_PREFERENCE, name)
            putBoolean(KEY_ADOPTED, true)
        }
        if (!name.isNullOrBlank()) BackgroundService.ForceRefreshConnections(context) // re-announce
    }
}
