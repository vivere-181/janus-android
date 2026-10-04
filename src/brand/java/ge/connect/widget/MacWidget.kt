/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import ge.connect.GePing
import ge.connect.home.DeviceAppearance
import ge.connect.home.HomeActivity
import ge.connect.home.SelectedDevice
import ge.connect.sync.MacState
import ge.connect.sync.PeerState
import ge.connect.transfers.Transfers
import ge.connect.transfers.TransfersActivity
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.plugins.battery.BatteryPlugin
import org.kde.kdeconnect.plugins.clipboard.ClipboardFloatingActivity
import org.kde.kdeconnect.plugins.mousepad.MousePadActivity
import org.kde.kdeconnect.plugins.mousepad.MousePadPlugin
import org.kde.kdeconnect.plugins.ping.PingPlugin
import org.kde.kdeconnect.plugins.presenter.PresenterActivity
import org.kde.kdeconnect.plugins.presenter.PresenterPlugin
import org.kde.kdeconnect.plugins.runcommand.GeRunCommands
import org.kde.kdeconnect.plugins.runcommand.RunCommandPlugin
import org.kde.kdeconnect_tp.R

/**
 * Home-screen widget: the computer, its status and four quick actions (chosen per widget: long-press →
 * Customize). One widget that adapts to its size: 4×1 slim row, 2×2 grid, 4×2 tiles, 4×3+ with recent transfers.
 * Material You colours; updated by events only (MacWidgetUpdates).
 */
class MacWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SLIM, SMALL, WIDE, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            // Glance keeps this composition alive between updates, so the data is re-read on every change signal.
            val change by MacWidgetUpdates.changes.collectAsState()
            val data = remember(change) { WidgetData.load(context, appWidgetId) }
            GlanceTheme(colors = ge.connect.ui.theme.JanusGlanceColors) { Content(context, data) }
        }
    }

    companion object {
        private val SLIM = DpSize(240.dp, 48.dp)
        private val SMALL = DpSize(110.dp, 110.dp)
        private val WIDE = DpSize(240.dp, 110.dp)
        private val TALL = DpSize(240.dp, 250.dp)
        val KEY = ActionParameters.Key<String>("command")
        val KEY_DEVICE = ActionParameters.Key<String>("device")
    }
}

class MacWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MacWidget()
}

/** Runs a Mac action (Find Mac, Lock, Mute, Screenshot) from the widget; the result shows up through MacState. */
class MacCommandAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val key = parameters[MacWidget.KEY] ?: return
        val device = parameters[MacWidget.KEY_DEVICE]?.let { KdeConnect.getInstance().getDevice(it) } ?: return
        val plugin = device.getPlugin(RunCommandPlugin::class.java) ?: return
        MacState.started(device.deviceId, key)
        plugin.runCommand(key)
    }
}

class PingAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        parameters[MacWidget.KEY_DEVICE]?.let { KdeConnect.getInstance().getDevice(it) }?.takeIf { it.isReachable }
            ?.let { GePing.send(context.applicationContext, it) }
    }
}

/** Ring, ringer, Do not disturb and flashlight of another phone (PeerState), from the widget. */
class PhoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val device = parameters[MacWidget.KEY_DEVICE]?.let { KdeConnect.getInstance().getDevice(it) }?.takeIf { it.isReachable } ?: return
        val peer = PeerState.of(device.deviceId)
        when (parameters[MacWidget.KEY]) {
            WidgetAction.RING.id -> PeerState.ring(device)
            WidgetAction.SOUND.id -> {
                val ringer = peer.ringer ?: PeerState.Ringer.NORMAL
                PeerState.setRinger(device, PeerState.Ringer.entries[(ringer.ordinal + 1) % PeerState.Ringer.entries.size])
            }
            WidgetAction.DND.id -> PeerState.setDnd(device, peer.dnd != true)
            WidgetAction.FLASHLIGHT.id -> PeerState.setFlashlight(device, peer.flashlight != true)
        }
    }
}

private data class WidgetData(
    val deviceId: String?,
    val name: String?,
    val connected: Boolean,
    val battery: Int?,
    val charging: Boolean,
    val art: Int?,
    val commands: Set<String>,
    val plugins: Set<String>,
    val mac: MacState.State,
    val actions: List<WidgetAction>,
    val recents: List<Transfers.Item>,
    val isPhone: Boolean = false,
    val peer: PeerState.Peer = PeerState.Peer(),
    val icon: Int = R.drawable.ms_laptop_mac_filled,
) {
    companion object {
        /** The widget's own device (Customize), else the app's selected one, else a connected one, else any paired one. */
        fun device(context: Context, appWidgetId: Int): Device? {
            WidgetAction.deviceForWidget(context, appWidgetId)?.let { KdeConnect.getInstance().getDevice(it) }?.takeIf { it.isPaired }?.let { return it }
            SelectedDevice.resolve(context)?.takeIf { it.isPaired }?.let { return it }
            val devices = KdeConnect.getInstance().devices.values.filter { it.isPaired }
            return devices.firstOrNull { it.isReachable } ?: devices.firstOrNull()
        }

        fun load(context: Context, appWidgetId: Int): WidgetData {
            val recents = Transfers.items.value.filter { it.state == Transfers.State.DONE }.take(4)
            val device = device(context, appWidgetId)
                ?: return WidgetData(null, null, false, null, false, null, emptySet(), emptySet(), MacState.State(),
                    WidgetAction.forWidget(context, appWidgetId, phone = false), recents)
            val isPhone = PeerState.isPhone(device)
            val actions = WidgetAction.forWidget(context, appWidgetId, isPhone)
            val connected = device.isReachable
            val battery = if (connected) device.getPlugin(BatteryPlugin::class.java)?.remoteBatteryInfo else null
            val commands = if (connected) device.getPlugin(RunCommandPlugin::class.java)
                ?.let { plugin -> GeRunCommands.commands(plugin).map { it.first }.toSet() }.orEmpty() else emptySet()
            val plugins = if (connected) listOfNotNull(
                "mousepad".takeIf { device.getPlugin(MousePadPlugin::class.java) != null },
                "presenter".takeIf { device.getPlugin(PresenterPlugin::class.java) != null },
                "ping".takeIf { device.getPlugin(PingPlugin::class.java) != null },
            ).toSet() else emptySet()
            return WidgetData(
                deviceId = device.deviceId,
                name = device.name,
                connected = connected,
                battery = battery?.currentCharge,
                charging = battery?.isCharging == true,
                art = DeviceAppearance.of(context, device).art,
                commands = commands,
                plugins = plugins,
                mac = MacState.of(device.deviceId),
                actions = actions,
                recents = recents.filter { it.deviceId == device.deviceId }.ifEmpty { recents },
                isPhone = isPhone,
                peer = PeerState.of(device.deviceId),
                icon = with(ge.connect.home.DeviceModelIcons) { device.deviceType.filledIcon },
            )
        }
    }
}

private class Tile(val label: String, val icon: Int, val action: Action, val look: Look = Look.Normal) {
    enum class Look { Normal, On, Alert, Working, Done, Failed, Disabled }
}

// Layouts ------------------------------------------------------------------------------------------------------------

@Composable
private fun Content(context: Context, data: WidgetData) {
    val openApp = actionStartActivity(Intent(context, HomeActivity::class.java))
    val size = LocalSize.current
    val tiles = data.actions.map { tile(context, data, it, openApp) }
    Column(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .padding(if (size.height < 110.dp) 10.dp else 14.dp),
    ) {
        when {
            size.height < 110.dp -> Slim(context, data, tiles, openApp)
            size.width < 240.dp -> {
                Header(context, data, showArt = false, openApp)
                Spacer(GlanceModifier.height(10.dp))
                TileGrid(tiles.chunked(2), labelled = false, modifier = GlanceModifier.defaultWeight())
            }
            size.height < 250.dp -> {
                Header(context, data, showArt = true, openApp)
                Spacer(GlanceModifier.height(12.dp))
                TileGrid(listOf(tiles), labelled = true, modifier = GlanceModifier.defaultWeight())
            }
            else -> {
                Header(context, data, showArt = true, openApp)
                Spacer(GlanceModifier.height(12.dp))
                TileGrid(listOf(tiles), labelled = true, modifier = GlanceModifier.height(84.dp))
                Spacer(GlanceModifier.height(14.dp))
                Recents(context, data, GlanceModifier.defaultWeight())
            }
        }
    }
}

/** 4×1: picture, name and status, then round icon buttons. */
@Composable
private fun Slim(context: Context, data: WidgetData, tiles: List<Tile>, openApp: Action) {
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Row(GlanceModifier.defaultWeight().clickable(openApp), verticalAlignment = Alignment.CenterVertically) {
            DeviceArt(data, GlanceModifier.size(42.dp, 28.dp))
            Spacer(GlanceModifier.width(10.dp))
            Column {
                Text(
                    data.name ?: context.getString(R.string.app_name), maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
                StatusLine(context, data, 12.sp)
            }
        }
        tiles.forEach { tile ->
            Spacer(GlanceModifier.width(6.dp))
            val (background, tint) = tileColors(tile.look)
            Box(
                GlanceModifier.size(40.dp).background(background).cornerRadius(20.dp).clickable(tile.action),
                contentAlignment = Alignment.Center,
            ) { TileIcon(tile, tint, 20.dp) }
        }
    }
}

@Composable
private fun Header(context: Context, data: WidgetData, showArt: Boolean, openApp: Action) {
    Row(GlanceModifier.fillMaxWidth().clickable(openApp), verticalAlignment = Alignment.CenterVertically) {
        if (showArt) {
            DeviceArt(data, GlanceModifier.size(60.dp, 40.dp))
            Spacer(GlanceModifier.width(12.dp))
        }
        Column(GlanceModifier.defaultWeight()) {
            Text(
                data.name ?: context.getString(R.string.app_name), maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Medium),
            )
            Spacer(GlanceModifier.height(2.dp))
            StatusLine(context, data, 13.sp)
        }
    }
}

@Composable
private fun DeviceArt(data: WidgetData, modifier: GlanceModifier) {
    val art = data.art
    if (art != null) {
        Image(ImageProvider(art), null, modifier)
    } else {
        Box(modifier.background(GlanceTheme.colors.primaryContainer).cornerRadius(14.dp), contentAlignment = Alignment.Center) {
            Image(
                ImageProvider(data.icon), null, GlanceModifier.size(20.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
            )
        }
    }
}

@Composable
private fun StatusLine(context: Context, data: WidgetData, fontSize: androidx.compose.ui.unit.TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            GlanceModifier.size(8.dp).cornerRadius(4.dp)
                .background(if (data.connected) ColorProvider(Color(0xFF3DBE58)) else GlanceTheme.colors.outline),
        ) {}
        Spacer(GlanceModifier.width(6.dp))
        Text(status(context, data), maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = fontSize))
    }
}

private fun status(context: Context, data: WidgetData): String {
    if (data.deviceId == null) return context.getString(R.string.widget_set_up)
    if (!data.connected) return context.getString(R.string.home_not_connected)
    val parts = mutableListOf(context.getString(if (data.mac.locked) R.string.mac_locked else R.string.home_connected))
    data.battery?.let { parts += if (data.charging) "$it% ⚡" else "$it%" }
    return parts.joinToString(" · ")
}

@Composable
private fun TileGrid(rows: List<List<Tile>>, labelled: Boolean, modifier: GlanceModifier) {
    Column(modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, row ->
            if (index > 0) Spacer(GlanceModifier.height(8.dp))
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                row.forEachIndexed { i, tile ->
                    if (i > 0) Spacer(GlanceModifier.width(8.dp))
                    val (background, tint) = tileColors(tile.look)
                    Column(
                        GlanceModifier.defaultWeight().fillMaxHeight().background(background).cornerRadius(20.dp).clickable(tile.action),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        TileIcon(tile, tint, 24.dp)
                        if (labelled) {
                            Spacer(GlanceModifier.height(6.dp))
                            Text(tile.label, maxLines = 1, style = TextStyle(color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TileIcon(tile: Tile, tint: ColorProvider, size: androidx.compose.ui.unit.Dp) {
    if (tile.look == Tile.Look.Working) {
        CircularProgressIndicator(GlanceModifier.size(size - 2.dp), color = tint)
        return
    }
    Image(
        ImageProvider(
            when (tile.look) {
                Tile.Look.Done -> R.drawable.ms_check_circle_filled
                Tile.Look.Failed -> R.drawable.ms_warning_filled
                Tile.Look.Alert -> R.drawable.ms_close
                else -> tile.icon
            }
        ),
        tile.label,
        GlanceModifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

@Composable
private fun tileColors(look: Tile.Look): Pair<ColorProvider, ColorProvider> {
    val colors = GlanceTheme.colors
    return when (look) {
        Tile.Look.On -> colors.primary to colors.onPrimary
        Tile.Look.Alert -> ColorProvider(Color(0xFFFF9F43)) to ColorProvider(Color(0xFF3A1D00))
        Tile.Look.Failed -> colors.errorContainer to colors.onErrorContainer
        Tile.Look.Disabled -> colors.surfaceVariant to colors.outline
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
}

/** 4×3+: the latest transfers as thumbnails (tap to open), like the app's Recent strip. */
@Composable
private fun Recents(context: Context, data: WidgetData, modifier: GlanceModifier) {
    Column(modifier.fillMaxWidth()) {
        Row(GlanceModifier.fillMaxWidth().clickable(actionStartActivity(Intent(context, TransfersActivity::class.java)))) {
            Text(
                context.getString(R.string.widget_recent), GlanceModifier.defaultWeight(),
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
            Text(context.getString(R.string.transfers_see_all), style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp))
        }
        Spacer(GlanceModifier.height(8.dp))
        if (data.recents.isEmpty()) {
            Text(
                context.getString(R.string.widget_recent_empty),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
            )
            return@Column
        }
        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
            (0 until 4).forEach { index ->
                if (index > 0) Spacer(GlanceModifier.width(8.dp))
                val item = data.recents.getOrNull(index)
                if (item == null) {
                    Spacer(GlanceModifier.defaultWeight())
                } else {
                    RecentThumb(context, item, GlanceModifier.defaultWeight().fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun RecentThumb(context: Context, item: Transfers.Item, modifier: GlanceModifier) {
    val bitmap = remember(item.thumb) { item.thumb?.let { loadThumb(it) } }
    val open = actionStartActivity(OpenTransferActivity.intent(context, item.id))
    Box(modifier.background(GlanceTheme.colors.secondaryContainer).cornerRadius(16.dp).clickable(open), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(ImageProvider(bitmap), item.name, GlanceModifier.fillMaxSize().cornerRadius(16.dp), contentScale = ContentScale.Crop)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = GlanceModifier.padding(6.dp)) {
                Image(
                    ImageProvider(
                        when (item.kind) {
                            Transfers.Kind.LINK -> R.drawable.ms_link
                            Transfers.Kind.TEXT -> R.drawable.ms_notes
                            Transfers.Kind.FILE -> R.drawable.ms_description
                        }
                    ),
                    null, GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
                )
                Text(item.name, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 10.sp))
            }
        }
    }
}

/** Widget bitmaps travel in the RemoteViews, so thumbnails are kept small. */
private fun loadThumb(path: String): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val sample = generateSequence(1) { it * 2 }.first { maxOf(bounds.outWidth, bounds.outHeight) / it <= 240 }
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

// Tiles --------------------------------------------------------------------------------------------------------------

private fun tile(context: Context, data: WidgetData, action: WidgetAction, openApp: Action): Tile {
    val deviceId = data.deviceId
    val live = data.connected && deviceId != null
    val label = context.getString(action.label)
    fun disabled() = Tile(label, action.icon, openApp, Tile.Look.Disabled)
    fun open(intent: Intent) = Tile(label, action.icon, actionStartActivity(intent))
    val command = action.command
    if (command != null) {
        if (!live || command !in data.commands) return disabled()
        val progress = data.mac.progress[command.removePrefix("ge-")]
        val (on, onLabel, onLook) = when (action) {
            WidgetAction.LOCK -> Triple(data.mac.locked, R.string.mac_locked, Tile.Look.On)
            WidgetAction.FIND_MAC -> Triple(data.mac.finding, R.string.mac_stop_sound, Tile.Look.Alert)
            WidgetAction.MUTE -> Triple(data.mac.muted == MacState.Muted.ON, R.string.mac_unmute, Tile.Look.On)
            else -> Triple(false, action.label, Tile.Look.Normal)
        }
        if (action == WidgetAction.MUTE && data.mac.muted == MacState.Muted.NA) {
            return Tile(context.getString(R.string.mac_cant_mute), action.icon, openApp, Tile.Look.Disabled)
        }
        val look = when (progress) {
            MacState.Progress.WORKING -> Tile.Look.Working
            MacState.Progress.DONE -> Tile.Look.Done
            MacState.Progress.FAILED -> Tile.Look.Failed
            null -> if (on) onLook else Tile.Look.Normal
        }
        return Tile(
            context.getString(if (on) onLabel else action.label), action.icon,
            actionRunCallback<MacCommandAction>(actionParametersOf(MacWidget.KEY to command, MacWidget.KEY_DEVICE to deviceId!!)), look,
        )
    }
    if (!live) return disabled()
    if (action.forPhone == true) {
        if (!data.isPhone) return disabled()
        val run = actionRunCallback<PhoneAction>(actionParametersOf(MacWidget.KEY to action.id, MacWidget.KEY_DEVICE to deviceId!!))
        val peer = data.peer
        return when (action) {
            WidgetAction.SOUND -> when (peer.ringer ?: PeerState.Ringer.NORMAL) {
                PeerState.Ringer.NORMAL -> Tile(context.getString(R.string.peer_ringer_sound), R.drawable.ms_volume_up, run)
                PeerState.Ringer.VIBRATE -> Tile(context.getString(R.string.peer_ringer_vibrate), R.drawable.ms_vibration, run, Tile.Look.On)
                PeerState.Ringer.SILENT -> Tile(context.getString(R.string.peer_ringer_silent), R.drawable.ms_volume_off, run, Tile.Look.On)
            }
            WidgetAction.DND -> Tile(label, action.icon, run, if (peer.dnd == true) Tile.Look.On else Tile.Look.Normal)
            WidgetAction.FLASHLIGHT -> if (peer.flashlight == true) Tile(label, R.drawable.ms_flashlight_on, run, Tile.Look.On)
                else Tile(label, R.drawable.ms_flashlight_off, run)
            else -> Tile(label, action.icon, run)
        }
    }
    return when (action) {
        WidgetAction.SEND_FILES -> open(ge.connect.share.SendSheetActivity.intent(context, deviceId!!))
        // Its own task (manifest), so reading the clipboard doesn't bring the app to the front; a toast confirms.
        WidgetAction.CLIPBOARD -> open(ClipboardFloatingActivity.getIntent(context, true))
        WidgetAction.REMOTE -> if ("mousepad" in data.plugins) open(Intent(context, MousePadActivity::class.java).putExtra("deviceId", deviceId)) else disabled()
        WidgetAction.PRESENTER -> if ("presenter" in data.plugins) open(Intent(context, PresenterActivity::class.java).putExtra("deviceId", deviceId)) else disabled()
        WidgetAction.PING -> if ("ping" in data.plugins) Tile(label, action.icon, actionRunCallback<PingAction>(actionParametersOf(MacWidget.KEY_DEVICE to deviceId!!))) else disabled()
        else -> disabled()
    }
}
