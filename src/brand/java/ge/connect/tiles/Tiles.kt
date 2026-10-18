/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.tiles

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.DrawableRes
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import kotlinx.coroutines.launch
import ge.connect.home.SelectedDevice
import ge.connect.home.symbol
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.plugins.mousepad.MousePadActivity
import org.kde.kdeconnect.ui.MainActivity
import org.kde.kdeconnect_tp.R

/** Base for our Quick Settings tiles: refreshes while the shade is open, launches activities. */
abstract class DeviceTileService : TileService() {
    private val callbackKey get() = javaClass.name

    /** The device tiles act on: the selected one if connected, else any connected paired device. */
    protected fun connectedDevice(): Device? {
        SelectedDevice.resolve(this)?.takeIf { it.isReachable && it.isPaired }?.let { return it }
        return KdeConnect.getInstance().devices.values.firstOrNull { it.isReachable && it.isPaired }
    }

    override fun onStartListening() {
        super.onStartListening()
        KdeConnect.getInstance().addDeviceListChangedCallback(callbackKey) { refresh() }
        refresh()
    }

    override fun onStopListening() {
        KdeConnect.getInstance().removeDeviceListChangedCallback(callbackKey)
        super.onStopListening()
    }

    protected fun refresh() {
        val tile = qsTile ?: return
        update(tile)
        tile.updateTile()
    }

    protected abstract fun update(tile: Tile)

    /** A tile that only works with a connected device: greyed out otherwise. */
    protected fun Tile.availableWhenConnected(@DrawableRes icon: Int) {
        this.icon = Icon.createWithResource(this@DeviceTileService, icon)
        state = if (connectedDevice() != null) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
    }

    protected fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        TileServiceCompat.startActivityAndCollapse(
            this, PendingIntentActivityWrapper(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT, false),
        )
    }
}

/** Shows whether the computer is connected (and its battery); opens the app. */
class StatusTileService : DeviceTileService() {
    override fun update(tile: Tile) {
        val device = connectedDevice()
        val remembered = device ?: SelectedDevice.resolve(this)
        tile.label = remembered?.name ?: getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, remembered?.symbol ?: R.drawable.ms_laptop_mac)
        tile.state = if (device != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val battery = device?.let(ge.connect.sync.MacState::battery)
            tile.subtitle = when {
                device == null -> getString(R.string.home_not_connected)
                battery != null -> getString(R.string.tile_connected_battery, battery.currentCharge)
                else -> getString(R.string.home_connected)
            }
        }
    }

    override fun onClick() {
        super.onClick()
        launch(Intent(this, MainActivity::class.java))
    }
}

/** Opens the touchpad for the connected computer. */
class TouchpadTileService : DeviceTileService() {
    override fun update(tile: Tile) {
        tile.label = getString(R.string.tile_touchpad)
        tile.availableWhenConnected(R.drawable.ms_trackpad_input)
    }

    override fun onClick() {
        super.onClick()
        val device = connectedDevice() ?: return
        launch(Intent(this, MousePadActivity::class.java).putExtra("deviceId", device.deviceId))
    }
}

/** Picks files and sends them to the connected computer. */
class SendFilesTileService : DeviceTileService() {
    override fun update(tile: Tile) {
        tile.label = getString(R.string.tile_send_files)
        tile.availableWhenConnected(R.drawable.ms_upload)
    }

    override fun onClick() {
        super.onClick()
        val device = connectedDevice() ?: return
        launch(ge.connect.share.SendSheetActivity.intent(this, device.deviceId))
    }
}

/** Locks the Mac (its ge-lock command, R14). Available when the connected computer offers it. */
class LockMacTileService : DeviceTileService() {
    private fun plugin() = connectedDevice()?.getPlugin(org.kde.kdeconnect.plugins.runcommand.RunCommandPlugin::class.java)
        ?.takeIf { p -> org.kde.kdeconnect.plugins.runcommand.GeRunCommands.commands(p).any { it.first == "ge-lock" } }

    private var watch: kotlinx.coroutines.Job? = null

    override fun onStartListening() {
        super.onStartListening()
        // Lock state and progress reported by the Mac (R19).
        watch = kotlinx.coroutines.MainScope().launch { ge.connect.sync.MacState.states.collect { refresh() } }
    }

    override fun onStopListening() {
        watch?.cancel()
        watch = null
        super.onStopListening()
    }

    override fun update(tile: Tile) {
        val device = connectedDevice()
        val state = device?.let { ge.connect.sync.MacState.of(it.deviceId) }
        val progress = state?.progress?.get("lock")
        tile.label = getString(R.string.tile_lock_mac)
        tile.icon = Icon.createWithResource(this, R.drawable.ms_lock_filled)
        tile.state = when {
            plugin() == null -> Tile.STATE_UNAVAILABLE
            state?.locked == true || progress == ge.connect.sync.MacState.Progress.DONE -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                tile.state == Tile.STATE_UNAVAILABLE -> null
                progress == ge.connect.sync.MacState.Progress.WORKING -> getString(R.string.mac_working)
                progress == ge.connect.sync.MacState.Progress.FAILED -> getString(R.string.mac_no_response_short)
                state?.locked == true || progress == ge.connect.sync.MacState.Progress.DONE -> getString(R.string.mac_locked)
                else -> null
            }
        }
    }

    override fun onClick() {
        super.onClick()
        val device = connectedDevice() ?: return
        val plugin = plugin() ?: return
        ge.connect.sync.MacState.started(device.deviceId, "ge-lock")
        plugin.runCommand("ge-lock")
    }
}
