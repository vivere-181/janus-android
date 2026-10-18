/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.home

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import ge.connect.devices.DevicesActivity
import ge.connect.settings.SettingsActivity
import ge.connect.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.PairingHandler
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.ui.MainActivity

/** Home: the selected computer, its actions and its settings. Replaces upstream's drawer UI. */
class HomeActivity : AppCompatActivity(), HomeCallbacks {
    private var device: Device? = null
    private var ui by mutableStateOf<DeviceUi?>(null)
    private var refreshing by mutableStateOf(false)
    private var pairError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        // Janus Cloud set up or dropped, or its connection changed (R36): the status and Send follow.
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(ge.connect.cloud.JanusCloud.peers, ge.connect.cloud.JanusCloud.link) { _, _ -> }
                .collect { update() }
        }
        setContent {
            AppTheme {
                HomeScreen(ui = ui, refreshing = refreshing, pairError = pairError, callbacks = this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Intents come from MainActivity (launcher, notifications), see MainActivity's extras. */
    private fun handleIntent(intent: Intent) {
        var deviceId = intent.getStringExtra(MainActivity.EXTRA_DEVICE_ID)
        when (intent.getStringExtra(MainActivity.PAIR_REQUEST_STATUS)) {
            MainActivity.PAIRING_ACCEPTED -> deviceId?.let { KdeConnect.getInstance().getDevice(it) }?.acceptPairing()
            MainActivity.PAIRING_REJECTED -> {
                deviceId?.let { KdeConnect.getInstance().getDevice(it) }?.cancelPairing()
                deviceId = null
            }
        }
        if (deviceId != null) SelectedDevice.set(this, deviceId)
        show(SelectedDevice.resolve(this))
        if (intent.hasExtra(MainActivity.FLAG_FORCE_OVERVIEW)) openDevices()
    }

    private var stopCommandListener: (() -> Unit)? = null

    private fun listenForCommands() {
        stopCommandListener?.invoke()
        stopCommandListener = {} // set first: registering a handler below may call it right away
        // Posted, not run inline: the handlers fire while registering and from getAlbumArt during update().
        val refresh = { window.decorView.post { update() }; Unit }
        val runCommand = device?.getPlugin(org.kde.kdeconnect.plugins.runcommand.RunCommandPlugin::class.java)?.let { plugin ->
            org.kde.kdeconnect.plugins.runcommand.GeRunCommands.listen(plugin) { refresh() }
        }
        // What's playing on the device (Now playing card).
        val mpris = device?.getPlugin(org.kde.kdeconnect.plugins.mpris.MprisPlugin::class.java)?.also { plugin ->
            plugin.setPlayerStatusUpdatedHandler(TAG) { refresh() }
            plugin.setPlayerListUpdatedHandler(TAG) { refresh() }
        }
        stopCommandListener = {
            runCommand?.invoke()
            mpris?.removePlayerStatusUpdatedHandler(TAG)
            mpris?.removePlayerListUpdatedHandler(TAG)
        }
    }

    private fun show(newDevice: Device?) {
        if (newDevice !== device) {
            device?.removePairingCallback(pairingCallback)
            device?.removePluginsChangedListener(pluginsChangedListener)
            newDevice?.addPairingCallback(pairingCallback)
            newDevice?.addPluginsChangedListener(pluginsChangedListener)
            device = newDevice
            pairError = null
        }
        update()
    }

    private fun update() {
        val device = device
        ui = device?.toHomeUi(this)
        if (stopCommandListener == null) listenForCommands()
        // Once in the app, the pairing notification is redundant.
        device?.hidePairingNotification()
    }

    private val pairingCallback = object : PairingHandler.PairingCallback {
        override fun incomingPairRequest() = runOnUiThread { update() }
        override fun pairingSuccessful() = runOnUiThread { pairError = null; update() }
        override fun pairingFailed(error: String) = runOnUiThread { pairError = error; update() }
        override fun unpaired(device: Device) = runOnUiThread { update() }
    }

    private val pluginsChangedListener = Device.PluginsChangedListener { runOnUiThread { stopCommandListener?.invoke(); stopCommandListener = null; update() } }

    override fun onStart() {
        super.onStart()
        BackgroundService.Start(applicationContext)
        KdeConnect.getInstance().addDeviceListChangedCallback(TAG) {
            runOnUiThread {
                // The shown device may have been forgotten, or a first one paired.
                val current = device?.deviceId?.let { KdeConnect.getInstance().getDevice(it) }
                show(current?.takeIf { it.isPaired || it.isReachable } ?: SelectedDevice.resolve(this))
            }
        }
        update()
    }

    override fun onStop() {
        KdeConnect.getInstance().removeDeviceListChangedCallback(TAG)
        super.onStop()
    }

    override fun onDestroy() {
        device?.removePairingCallback(pairingCallback)
        device?.removePluginsChangedListener(pluginsChangedListener)
        super.onDestroy()
    }

    // --- HomeCallbacks ---

    override fun openDevices() = startActivity(Intent(this, DevicesActivity::class.java))
    override fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    override fun refresh() {
        BackgroundService.ForceRefreshConnections(this)
        refreshing = true
        lifecycleScope.launch {
            delay(1500)
            refreshing = false
        }
    }

    override fun requestPairing() {
        pairError = null
        device?.requestPairing()
        update()
    }

    override fun acceptPairing() {
        device?.acceptPairing()
        update()
    }

    override fun cancelPairing() {
        device?.cancelPairing()
        update()
    }


    /** With a TV's remote on screen, the volume keys are the TV's (TvVolumeKeys). */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val code = event.keyCode
        val handler = ge.connect.tv.TvVolumeKeys.handler
        if (handler != null && (code == android.view.KeyEvent.KEYCODE_VOLUME_UP || code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) handler(code)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun runAction(action: DeviceAction) = action.run(this)

    override fun requestPermission(request: PermissionRequest) {
        val dialog = if (request.optional) request.plugin.optionalPermissionExplanationDialog else request.plugin.permissionExplanationDialog
        dialog.show(supportFragmentManager, null)
    }



    override fun runMacControl(control: MacControl) {
        val device = device ?: return
        ge.connect.sync.MacState.started(device.deviceId, control.key) // progress + result feedback (R19)
        if (!device.isReachable && ge.connect.cloud.CloudCompanion.away(device)) {
            ge.connect.cloud.CloudCompanion.action(device, control.key) // through Janus Cloud while the Mac is away (R37)
            return
        }
        device.getPlugin(org.kde.kdeconnect.plugins.runcommand.RunCommandPlugin::class.java)?.runCommand(control.key)
    }

    override fun openPreferences() {
        device?.let { startActivity(DevicePreferencesActivity.intent(this, it.deviceId)) }
    }

    override fun openTransfers() = startActivity(ge.connect.transfers.TransfersActivity.intent(this))



    // Upstream's permission dialogs report back to the hosting activity; reload so plugins pick them up.

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) reloadPlugins()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MainActivity.RESULT_NEEDS_RELOAD) reloadPlugins()
    }

    private fun reloadPlugins() {
        lifecycleScope.launch(Dispatchers.IO) {
            KdeConnect.getInstance().devices.values.forEach(Device::reloadPluginsFromSettings)
        }
    }

    private companion object {
        const val TAG = "HomeActivity"
    }
}

interface HomeCallbacks {
    fun openDevices()
    fun openSettings()
    fun refresh()
    fun requestPairing()
    fun acceptPairing()
    fun cancelPairing()
    fun runAction(action: DeviceAction)
    fun requestPermission(request: PermissionRequest)
    fun openTransfers()
    fun openPreferences()
    fun runMacControl(control: MacControl)
}
