/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.widget

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.edit
import org.kde.kdeconnect_tp.R

/**
 * Everything the widget can show as a tile. [command] = the Mac action it runs (R14), if any; [forPhone] = an action
 * for another phone (true), for a computer (false), or for both (null).
 */
enum class WidgetAction(
    val id: String, @StringRes val label: Int, @DrawableRes val icon: Int,
    val command: String? = null, val forPhone: Boolean? = null,
) {
    SEND_FILES("send_files", R.string.widget_send_files, R.drawable.ms_upload),
    CLIPBOARD("clipboard", R.string.widget_clipboard, R.drawable.ms_content_paste),
    FIND_MAC("findmac", R.string.mac_find, R.drawable.ms_ring_volume, "ge-findmac", forPhone = false),
    LOCK("lock", R.string.mac_lock, R.drawable.ms_lock_filled, "ge-lock", forPhone = false),
    MUTE("mute", R.string.mac_mute, R.drawable.ms_volume_off, "ge-mute", forPhone = false),
    SCREENSHOT("screenshot", R.string.mac_screenshot, R.drawable.ms_screenshot_monitor, "ge-screenshot", forPhone = false),
    RING("ring_phone", R.string.peer_ring, R.drawable.ms_notifications_active, forPhone = true),
    SOUND("ringer", R.string.peer_ringer_sound, R.drawable.ms_volume_up, forPhone = true),
    DND("dnd", R.string.peer_dnd_short, R.drawable.ms_do_not_disturb_on, forPhone = true),
    FLASHLIGHT("flashlight", R.string.peer_flashlight, R.drawable.ms_flashlight_off, forPhone = true),
    REMOTE("remote", R.string.widget_remote, R.drawable.ms_mouse),
    PING("ping", R.string.widget_ping, R.drawable.ms_notifications_active, forPhone = false),
    PRESENTER("presenter", R.string.widget_presenter, R.drawable.ms_co_present, forPhone = false);

    fun fits(phone: Boolean) = forPhone == null || forPhone == phone

    companion object {
        const val MAX = 4
        val DEFAULT = listOf(SEND_FILES, CLIPBOARD, FIND_MAC, LOCK)
        val DEFAULT_PHONE = listOf(SEND_FILES, CLIPBOARD, RING, SOUND)
        private const val PREFS = "ge_connect_widget"

        fun defaults(phone: Boolean) = if (phone) DEFAULT_PHONE else DEFAULT

        /** The tiles chosen for one widget (Customize), in the chosen order, for the kind of device it shows. */
        fun forWidget(context: Context, appWidgetId: Int, phone: Boolean): List<WidgetAction> {
            val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("actions_$appWidgetId", null)
                ?: return defaults(phone)
            val saved = stored.split(',').mapNotNull { id -> entries.firstOrNull { it.id == id } }
            val fitting = saved.filter { it.fits(phone) }
            // Actions for another kind of device (e.g. Lock on a phone widget) are replaced by that device's defaults.
            return fitting + defaults(phone).filter { it !in fitting }.take(saved.size - fitting.size)
        }

        fun setForWidget(context: Context, appWidgetId: Int, actions: List<WidgetAction>) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString("actions_$appWidgetId", actions.take(MAX).joinToString(",") { it.id })
            }

        /** The device a widget shows; null = follow the app's selected device. */
        fun deviceForWidget(context: Context, appWidgetId: Int): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("device_$appWidgetId", null)

        fun setDeviceForWidget(context: Context, appWidgetId: Int, deviceId: String?) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("device_$appWidgetId", deviceId) }
    }
}
