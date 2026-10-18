/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect

/**
 * Keeps the widget current: connection changes, the computer's battery (plugin changes) and Mac states.
 * Event-driven only (no periodic updates); bursts are merged. Started from KdeConnect.onCreate.
 */
object MacWidgetUpdates {
    private const val CALLBACK_KEY = "ge.connect.MacWidget"
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null
    private val watched = HashSet<String>()
    private val pluginsListener = Device.PluginsChangedListener { device ->
        watchCommands(device)
        request()
    }
    /** deviceId → (RunCommandPlugin instance, stop listening): the Mac's command list enables Find Mac and Lock. */
    private val commandWatchers = HashMap<String, Pair<Any, () -> Unit>>()

    private fun watchCommands(device: Device) = synchronized(commandWatchers) {
        val plugin = device.getPlugin(org.kde.kdeconnect.plugins.runcommand.RunCommandPlugin::class.java)
        val current = commandWatchers[device.deviceId]
        if (current?.first === plugin) return@synchronized
        current?.second?.invoke()
        commandWatchers.remove(device.deviceId)
        if (plugin != null) {
            commandWatchers[device.deviceId] = plugin to org.kde.kdeconnect.plugins.runcommand.GeRunCommands.listen(plugin) { request() }
        }
    }

    /** Bumped on every change; a running widget composition re-reads its data when it changes. */
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)

    fun start(context: Context) {
        app = context.applicationContext
        KdeConnect.getInstance().addDeviceListChangedCallback(CALLBACK_KEY) {
            synchronized(watched) {
                KdeConnect.getInstance().devices.values.filter { watched.add(it.deviceId) }
                    .forEach { it.addPluginsChangedListener(pluginsListener) }
            }
            KdeConnect.getInstance().devices.values.forEach(::watchCommands)
            request()
        }
        // Janus Cloud up or down: an away Mac shows as "Via Cloud" or offline.
        scope.launch { ge.connect.cloud.JanusCloud.link.map { it == ge.connect.cloud.JanusCloud.Link.CONNECTED }.distinctUntilChanged().collect { request() } }
        scope.launch { ge.connect.cloud.JanusCloud.peers.collect { request() } }
        // Finished transfers feed the 4×3 "Recent" strip (progress ticks don't matter here).
        scope.launch {
            ge.connect.transfers.Transfers.items
                .map { items -> items.filter { it.state == ge.connect.transfers.Transfers.State.DONE }.take(4).map { it.id to it.thumb } }
                .distinctUntilChanged()
                .collect { request() }
        }
        request()
    }

    /** Redraws the widgets shortly (no-op when none are on the home screen). */
    fun request() {
        if (!::app.isInitialized || !hasWidgets()) return
        synchronized(this) {
            pending?.cancel()
            pending = scope.launch {
                delay(800) // a connection change comes as a burst of events: one redraw
                changes.value = changes.value + 1
                runCatching { MacWidget().updateAll(app) } // starts a composition if none is running
            }
        }
    }

    private fun hasWidgets() = runCatching {
        AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, MacWidgetReceiver::class.java)).isNotEmpty()
    }.getOrDefault(false)

    /** Asks the launcher to add the widget (Android 8+ launchers that support pinning). */
    fun requestPin(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O || !manager.isRequestPinAppWidgetSupported) return false
        return manager.requestPinAppWidget(ComponentName(context, MacWidgetReceiver::class.java), null, null)
    }
}
