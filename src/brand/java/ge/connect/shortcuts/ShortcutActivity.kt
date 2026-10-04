/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.shortcuts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import ge.connect.GePing
import ge.connect.home.SelectedDevice
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.plugins.clipboard.ClipboardFloatingActivity
import org.kde.kdeconnect.plugins.mousepad.MousePadActivity
import org.kde.kdeconnect.ui.MainActivity
import org.kde.kdeconnect_tp.R

/**
 * Target of the launcher's long-press shortcuts (res/xml/shortcuts.xml): runs one action on the
 * connected computer, then gets out of the way. Without a connected computer, opens the app.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = connectedDevice()
        if (device == null) {
            Toast.makeText(this, R.string.shortcut_not_connected, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java))
        } else when (intent.action) {
            ACTION_CLIPBOARD -> startActivity(ClipboardFloatingActivity.getIntent(this, true))
            ACTION_PING -> GePing.send(applicationContext, device)
            ACTION_TOUCHPAD -> startActivity(Intent(this, MousePadActivity::class.java).putExtra("deviceId", device.deviceId))
            ACTION_SEND_FILES -> startActivity(ge.connect.share.SendSheetActivity.intent(this, device.deviceId))
        }
        finish()
    }

    private fun connectedDevice(): Device? {
        SelectedDevice.resolve(this)?.takeIf { it.isReachable && it.isPaired }?.let { return it }
        return KdeConnect.getInstance().devices.values.firstOrNull { it.isReachable && it.isPaired }
    }

    companion object {
        const val ACTION_CLIPBOARD = "ge.connect.shortcut.CLIPBOARD"
        const val ACTION_PING = "ge.connect.shortcut.PING"
        const val ACTION_TOUCHPAD = "ge.connect.shortcut.TOUCHPAD"
        const val ACTION_SEND_FILES = "ge.connect.shortcut.SEND_FILES"
    }
}
