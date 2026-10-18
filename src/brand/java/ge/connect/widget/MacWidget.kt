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
 * Customize). One widget that adapts to its size: 2×1 the Mac's card (battery ring), 4×1 that and the actions,
 * 2×2 grid, 4×2 tiles, 4×3+ with recent transfers.
 * Material You colours; updated by events only (MacWidgetUpdates).
 */
class MacWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SLIM_HALF, SLIM, SMALL, WIDE, TALL))

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
        private val SLIM_HALF = DpSize(110.dp, 48.dp)
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
        if (!device.isReachable && ge.connect.cloud.CloudCompanion.away(device)) {
            MacState.started(device.deviceId, key)
            ge.connect.cloud.CloudCompanion.action(device, key) // through Janus Cloud while the Mac is away (R37)
            return
        }
        val plugin = device.getPlugin(RunCommandPlugin::class.java) ?: return
        MacState.started(device.deviceId, key)
        plugin.runCommand(key)
    }
}

/**
 * Send files: the sheet opened by the app, without a transition, rather than by the launcher (One UI zooms the whole
 * home screen away for launches from its widgets, as for an app). The widget click lets the app start it.
 */
class SendFilesAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val deviceId = parameters[MacWidget.KEY_DEVICE] ?: return
        val intent = ge.connect.share.SendSheetActivity.intent(context, deviceId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = android.app.ActivityOptions.makeCustomAnimation(context, 0, 0)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            options.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        context.startActivity(intent, options.toBundle())
    }
}

/** "Try again" on an offline widget: look for the computer now. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        org.kde.kdeconnect.BackgroundService.ForceRefreshConnections(context)
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
    /** When it was last connected, if it isn't now. */
    val lastSeen: Long? = null,
    /** What the Mac said it is ("MacBook Pro", R28): shorter than its name for the small layouts. */
    val model: String? = null,
    /** Not connected, but reachable through Janus Cloud. */
    val cloud: Boolean = false,
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
            val battery = if (connected) LastBattery.of(context, device.deviceId, ge.connect.sync.MacState.battery(device)) else null
            // Away from the local network but linked through Janus Cloud: files, the clipboard and the Mac's actions work.
            val cloud = !connected && ge.connect.cloud.CloudCompanion.away(device) &&
                ge.connect.cloud.JanusCloud.link.value == ge.connect.cloud.JanusCloud.Link.CONNECTED
            val commands = if (connected) device.getPlugin(RunCommandPlugin::class.java)
                ?.let { plugin -> GeRunCommands.commands(plugin).map { it.first }.toSet() }.orEmpty()
            else if (cloud) rememberedCommands(context, device.deviceId) else emptySet()
            val plugins = if (connected) listOfNotNull(
                "mousepad".takeIf { device.getPlugin(MousePadPlugin::class.java) != null },
                "presenter".takeIf { device.getPlugin(PresenterPlugin::class.java) != null },
                "ping".takeIf { device.getPlugin(PingPlugin::class.java) != null },
            ).toSet() else emptySet()
            return WidgetData(
                deviceId = device.deviceId,
                name = device.name,
                connected = connected,
                battery = battery?.first,
                charging = battery?.second == true,
                art = DeviceAppearance.of(context, device).art,
                commands = commands,
                plugins = plugins,
                mac = MacState.of(device.deviceId),
                actions = actions,
                recents = recents.filter { it.deviceId == device.deviceId }.ifEmpty { recents },
                isPhone = isPhone,
                peer = PeerState.of(device.deviceId),
                icon = with(ge.connect.home.DeviceModelIcons) { device.deviceType.filledIcon },
                model = ge.connect.sync.MacInfo.market(context, device.deviceId),
                cloud = cloud,
                lastSeen = if (connected) null else ge.connect.connection.ConnectionHistory.events(context, device.deviceId)
                    .firstOrNull { !it.connected }?.time,
            )
        }
    }
}

/**
 * The computer's battery as last reported. It reports only when the level changes, and after a reconnection Janus
 * starts without it (the plugins are reloaded), so a Mac at 100 % on its charger could stay unknown for hours.
 */
private object LastBattery {
    private const val PREFS = "ge_connect_widget_battery"

    /** (level, charging): [current] if there is one (remembered), else the last one known. */
    fun of(context: Context, deviceId: String, current: org.kde.kdeconnect.plugins.battery.DeviceBatteryInfo?): Pair<Int, Boolean>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val level = current?.currentCharge?.takeIf { it in 0..100 }
        if (level != null) {
            if (prefs.getInt("$deviceId/level", -1) != level || prefs.getBoolean("$deviceId/charging", false) != current.isCharging) {
                prefs.edit().putInt("$deviceId/level", level).putBoolean("$deviceId/charging", current.isCharging).apply()
            }
            return level to current.isCharging
        }
        val last = prefs.getInt("$deviceId/level", -1).takeIf { it >= 0 } ?: return null
        return last to prefs.getBoolean("$deviceId/charging", false)
    }
}

/** The Mac's actions as Home remembered them from the last connection (for Janus Cloud while it's away). */
private fun rememberedCommands(context: Context, deviceId: String): Set<String> = runCatching {
    val array = org.json.JSONArray(context.getSharedPreferences("ge_connect_mac_controls", Context.MODE_PRIVATE).getString(deviceId, "[]"))
    (0 until array.length()).map { array.getJSONArray(it).getString(0) }.toSet()
}.getOrDefault(emptySet())

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
            .background(if (size.height < 110.dp) WIDGET else GlanceTheme.colors.widgetBackground)
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .padding(if (size.height < 110.dp) 8.dp else 14.dp),
    ) {
        when {
            size.height < 110.dp && size.width < 240.dp -> SlimHalf(context, data, openApp)
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

/**
 * 4×1: the Mac in an inner card ("well": its tile in a battery ring, level and state; tap: the app), then its actions.
 * Through Janus Cloud: a blue ring and the cloud; offline: a dashed ring and Try again instead of the actions.
 */
@Composable
private fun Slim(context: Context, data: WidgetData, tiles: List<Tile>, openApp: Action) {
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        MacWell(context, data, GlanceModifier.fillMaxHeight().clickable(openApp))
        Spacer(GlanceModifier.width(8.dp))
        Row(GlanceModifier.defaultWeight().height(56.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!data.connected && !data.cloud) {
                Row(
                    GlanceModifier.fillMaxSize().background(BUTTON).cornerRadius(16.dp).clickable(
                        if (data.deviceId == null) openApp else actionRunCallback<RefreshAction>()
                    ),
                    verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(ImageProvider(R.drawable.ms_refresh), null, GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(ON_BUTTON))
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        context.getString(if (data.deviceId == null) R.string.widget_set_up else R.string.widget_try_again), maxLines = 1,
                        style = TextStyle(color = ON_BUTTON, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    )
                }
                return@Row
            }
            tiles.forEachIndexed { i, tile ->
                if (i > 0) Spacer(GlanceModifier.width(6.dp))
                val (background, tint) = buttonColors(tile.look)
                Box(
                    GlanceModifier.defaultWeight().fillMaxHeight().background(background).cornerRadius(16.dp).clickable(tile.action),
                    contentAlignment = Alignment.Center,
                ) { TileIcon(tile, tint, 22.dp) }
            }
        }
    }
}

/** 2×1: just the Mac's card; tap: the app. */
@Composable
private fun SlimHalf(context: Context, data: WidgetData, openApp: Action) {
    MacWell(context, data, GlanceModifier.fillMaxSize().clickable(openApp))
}

/** The Mac's card: its tile in the battery ring, then the level (or Via Cloud / Offline) and what it's doing. */
@Composable
private fun MacWell(context: Context, data: WidgetData, modifier: GlanceModifier) {
    Row(modifier.background(WELL).cornerRadius(22.dp).padding(start = 8.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        val level = data.battery?.takeIf { data.connected }
        val state = when { data.connected -> RingState.LIVE; data.cloud -> RingState.CLOUD; else -> RingState.OFFLINE }
        val ring = remember(level, state) { ringBitmap(context, level, state) }
        val dim = state == RingState.OFFLINE
        Box(GlanceModifier.size(50.dp), contentAlignment = Alignment.Center) {
            Image(ImageProvider(ring), null, GlanceModifier.fillMaxSize())
            Box(GlanceModifier.size(38.dp).background(if (dim) TILE_OFF else TILE).cornerRadius(12.dp), contentAlignment = Alignment.Center) {
                Image(
                    ImageProvider(when {
                        state == RingState.CLOUD -> R.drawable.ms_cloud
                        data.icon == R.drawable.ms_desktop_windows_filled -> R.drawable.ms_desktop_windows
                        else -> R.drawable.ms_laptop_mac
                    }),
                    data.name, GlanceModifier.size(21.dp), colorFilter = ColorFilter.tint(if (dim) ON_TILE_OFF else ON_TILE),
                )
            }
        }
        Spacer(GlanceModifier.width(10.dp))
        Column {
            Text(
                when {
                    level != null -> "$level%"
                    state == RingState.CLOUD -> context.getString(R.string.widget_via_cloud)
                    data.connected -> "–%" // not reported yet: the same width as a level, so the buttons don't move
                    else -> context.getString(R.string.widget_offline)
                },
                maxLines = 1,
                style = TextStyle(
                    color = if (dim) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                    fontSize = if (data.connected) 21.sp else 16.sp, fontWeight = FontWeight.Medium,
                ),
            )
            Text(macState(context, data), maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp))
        }
    }
}

/** Under the level: Locked / Charging / Connected; through the cloud, why; offline, when it was last seen. */
private fun macState(context: Context, data: WidgetData): String {
    if (data.deviceId == null) return context.getString(R.string.widget_set_up)
    if (data.cloud) return context.getString(R.string.widget_not_on_wifi)
    if (!data.connected) {
        val seen = data.lastSeen ?: return data.model ?: data.name.orEmpty()
        val now = System.currentTimeMillis()
        val ago = if (now - seen < android.text.format.DateUtils.MINUTE_IN_MILLIS) context.getString(R.string.widget_just_now)
        else android.text.format.DateUtils.getRelativeTimeSpanString(seen, now, android.text.format.DateUtils.MINUTE_IN_MILLIS,
            android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE).toString()
        return context.getString(R.string.home_last_seen, ago)
    }
    return context.getString(when {
        data.mac.locked -> R.string.mac_locked
        data.charging -> R.string.widget_charging
        else -> R.string.home_connected
    })
}

private enum class RingState { LIVE, CLOUD, OFFLINE }

/**
 * The battery ring around the tile, as a bitmap (widgets can't draw it): a rounded square from the top centre,
 * clockwise, as long as the level; green, amber at 30 %, red at 15 %; a full blue ring through the cloud (no level
 * there); dashed grey when offline.
 */
private fun ringBitmap(context: Context, level: Int?, state: RingState): Bitmap {
    val density = context.resources.displayMetrics.density.coerceAtMost(3f)
    val px = (50 * density).toInt()
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val stroke = 3 * density
    val inset = stroke / 2
    val path = android.graphics.Path().apply {
        // Starts at the top centre so the level grows clockwise from there.
        val r = 18 * density - inset
        val rect = android.graphics.RectF(inset, inset, px - inset, px - inset)
        moveTo(rect.centerX(), rect.top)
        lineTo(rect.right - r, rect.top); arcTo(rect.right - 2 * r, rect.top, rect.right, rect.top + 2 * r, -90f, 90f, false)
        lineTo(rect.right, rect.bottom - r); arcTo(rect.right - 2 * r, rect.bottom - 2 * r, rect.right, rect.bottom, 0f, 90f, false)
        lineTo(rect.left + r, rect.bottom); arcTo(rect.left, rect.bottom - 2 * r, rect.left + 2 * r, rect.bottom, 90f, 90f, false)
        lineTo(rect.left, rect.top + r); arcTo(rect.left, rect.top, rect.left + 2 * r, rect.top + 2 * r, 180f, 90f, false)
        close()
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    when (state) {
        RingState.OFFLINE -> {
            paint.color = 0xFF8A93A8.toInt()
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(stroke * 1.8f, stroke * 1.6f), 0f)
            canvas.drawPath(path, paint)
        }
        RingState.CLOUD -> {
            paint.color = 0xFF5FA8FF.toInt()
            canvas.drawPath(path, paint)
        }
        RingState.LIVE -> {
            paint.color = 0x33808A9E
            canvas.drawPath(path, paint)
            val value = level?.coerceIn(0, 100) ?: return bitmap // no level yet: just the track
            paint.color = when {
                value <= 15 -> 0xFFE5484D.toInt()
                value <= 30 -> 0xFFE8A317.toInt()
                else -> 0xFF2EB85C.toInt()
            }
            val measure = android.graphics.PathMeasure(path, false)
            val done = android.graphics.Path()
            measure.getSegment(0f, measure.length * value / 100f, done, true)
            canvas.drawPath(done, paint)
        }
    }
    return bitmap
}

// The 4×1 and 2×1 look (Material You, Janus's colours), light and dark: the widget, its inner cards, the tile, buttons.
private val WIDGET = androidx.glance.color.ColorProvider(day = Color(0xFFD3E4FF), night = Color(0xFF1F2A3D))
private val WELL = androidx.glance.color.ColorProvider(day = Color(0xFFE9F0FF), night = Color(0xFF172133))
private val TILE = androidx.glance.color.ColorProvider(day = Color(0xFF1B4684), night = Color(0xFFA8C8FC))
private val ON_TILE = androidx.glance.color.ColorProvider(day = Color(0xFFD3E4FF), night = Color(0xFF023063))
private val TILE_OFF = androidx.glance.color.ColorProvider(day = Color(0xFFC9D5EA), night = Color(0xFF2E3A50))
private val ON_TILE_OFF = androidx.glance.color.ColorProvider(day = Color(0xFF5B6B85), night = Color(0xFF8A93A8))
private val BUTTON = androidx.glance.color.ColorProvider(day = Color(0xFFA8C8FC), night = Color(0xFF38475F))
private val ON_BUTTON = androidx.glance.color.ColorProvider(day = Color(0xFF001B3D), night = Color(0xFFD5E3FF))
private val BUTTON_ON = androidx.glance.color.ColorProvider(day = Color(0xFF1B4684), night = Color(0xFFA8C8FC))
private val ON_BUTTON_ON = androidx.glance.color.ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF023063))
private val BUTTON_OFF = androidx.glance.color.ColorProvider(day = Color(0x66A8C8FC), night = Color(0x6638475F))

@Composable
private fun buttonColors(look: Tile.Look): Pair<ColorProvider, ColorProvider> = when (look) {
    Tile.Look.Normal, Tile.Look.Working, Tile.Look.Done -> BUTTON to ON_BUTTON
    Tile.Look.On -> BUTTON_ON to ON_BUTTON_ON
    Tile.Look.Disabled -> BUTTON_OFF to GlanceTheme.colors.outline
    else -> tileColors(look)
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
    fun sendFiles(deviceId: String) = Tile(
        context.getString(action.label), action.icon, actionRunCallback<SendFilesAction>(actionParametersOf(MacWidget.KEY_DEVICE to deviceId)),
    )
    val deviceId = data.deviceId
    val live = data.connected && deviceId != null
    val cloud = data.cloud && deviceId != null
    val label = context.getString(action.label)
    fun disabled() = Tile(label, action.icon, openApp, Tile.Look.Disabled)
    fun open(intent: Intent) = Tile(label, action.icon, actionStartActivity(intent))
    val command = action.command
    if (command != null) {
        if (!(live || cloud) || command !in data.commands) return disabled()
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
    if (cloud) return when (action) {
        WidgetAction.SEND_FILES -> sendFiles(deviceId!!)
        WidgetAction.CLIPBOARD -> open(ClipboardFloatingActivity.getCloudIntent(context, deviceId!!))
        else -> disabled()
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
        WidgetAction.SEND_FILES -> sendFiles(deviceId!!)
        // Its own task (manifest), so reading the clipboard doesn't bring the app to the front; a toast confirms.
        WidgetAction.CLIPBOARD -> open(ClipboardFloatingActivity.getIntent(context, true))
        WidgetAction.REMOTE -> if ("mousepad" in data.plugins) open(Intent(context, MousePadActivity::class.java).putExtra("deviceId", deviceId)) else disabled()
        WidgetAction.PRESENTER -> if ("presenter" in data.plugins) open(Intent(context, PresenterActivity::class.java).putExtra("deviceId", deviceId)) else disabled()
        WidgetAction.PING -> if ("ping" in data.plugins) Tile(label, action.icon, actionRunCallback<PingAction>(actionParametersOf(MacWidget.KEY_DEVICE to deviceId!!))) else disabled()
        else -> disabled()
    }
}
