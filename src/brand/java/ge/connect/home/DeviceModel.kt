/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.core.content.edit
import ge.connect.GePing
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.PairingHandler.PairState
import org.kde.kdeconnect.helpers.security.SslHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.battery.DeviceBatteryInfo
import org.kde.kdeconnect.ui.MainActivity
import org.kde.kdeconnect_tp.R

/** Which device Home shows. Same store upstream's MainActivity used, so the choice carries over. */
object SelectedDevice {
    private const val PREFS = "stored_menu_selection"
    private const val KEY = "selected_device"

    fun set(context: Context, deviceId: String?) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, deviceId) }

    /** The stored device if it's still known, else the first paired one (connected first). */
    fun resolve(context: Context): Device? {
        val devices = KdeConnect.getInstance().devices
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        stored?.let(devices::get)?.takeIf { it.isPaired || it.isReachable }?.let { return it }
        return devices.values.filter { it.isPaired }.sortedByDescending { it.isReachable }.firstOrNull()
    }
}

fun Context.openDevice(deviceId: String) {
    SelectedDevice.set(this, deviceId)
    startActivity(
        Intent(this, HomeActivity::class.java)
            .putExtra(MainActivity.EXTRA_DEVICE_ID, deviceId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    )
}

/**
 * Icon for a device. The Mac engine always announces "desktop" (it can't detect laptops on macOS, R3), so our Mac app
 * says what it is in `macstate` (`kind`, R28); before that, MacBooks are recognized by name.
 */
@get:DrawableRes
val Device.symbol: Int
    get() {
        if (deviceType != DeviceType.DESKTOP) return deviceType.symbol
        return when (ge.connect.sync.MacInfo.kind(KdeConnect.getInstance(), deviceId)) {
            "laptop" -> DeviceType.LAPTOP.symbol
            "desktop" -> DeviceType.DESKTOP.symbol
            else -> if (name.contains("MacBook", ignoreCase = true)) DeviceType.LAPTOP.symbol else DeviceType.DESKTOP.symbol
        }
    }

@get:DrawableRes
val DeviceType.symbol: Int
    get() = when (this) {
        DeviceType.LAPTOP -> R.drawable.ms_laptop_mac
        DeviceType.DESKTOP -> R.drawable.ms_desktop_windows
        DeviceType.PHONE -> R.drawable.ms_smartphone
        DeviceType.TABLET -> R.drawable.ms_tablet_android
        DeviceType.TV -> R.drawable.ms_tv
    }

class DeviceAction(val label: String, @DrawableRes val icon: Int, val summary: String? = null, val secondary: Boolean = false, val run: (Activity) -> Unit)
class PermissionRequest(val featureName: String, val plugin: Plugin, val optional: Boolean)

/** What's playing on the device (its media players, through MprisPlugin), with controls. */
class NowPlaying(
    val title: String,
    val artist: String,
    val playing: Boolean,
    val art: android.graphics.Bitmap?,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val playPause: () -> Unit,
    val previous: () -> Unit,
    val next: () -> Unit,
)

/** A command the computer offers through run-command (R14): the Mac's own ge-* keys get proper icons. */
class MacControl(val key: String, val label: String, @DrawableRes val icon: Int)

/** Snapshot of a device for the Home screen; rebuilt whenever the device reports a change. */
data class DeviceUi(
    val id: String,
    val name: String,
    @DrawableRes val icon: Int,
    val reachable: Boolean,
    /** When a paired computer that's now unreachable was last connected (ConnectionHistory), if known. */
    val lastSeen: Long? = null,
    val pairState: PairState,
    val verificationKey: String?,
    val battery: DeviceBatteryInfo?,
    val actions: List<DeviceAction>,
    val menuEntries: List<Plugin.PluginUiMenuEntry>,
    val permissionRequests: List<PermissionRequest>,
    val appearance: DeviceAppearance = DeviceAppearance.GENERIC,
    val macControls: List<MacControl> = emptyList(),
    /** Another phone (or tablet): shows its ringer/DND/flashlight/signal and phone controls instead of Mac ones. */
    val isPhone: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    /** The owner's own device (everything) or someone else's (sharing only); see DeviceOwnership. */
    val mine: Boolean = true,
    val ownershipAnswered: Boolean = true,
    /** Not on the same network, but reachable through Janus Cloud (R36): files can still be sent. */
    val cloud: Boolean = false,
    /** The relay connection is up (else sends wait and retry). */
    val cloudConnected: Boolean = false,
    /** A TV: Home is its remote (and screen sharing) instead of the usual features. */
    val isTv: Boolean = false,
) {
    val paired get() = pairState == PairState.Paired
}

fun Device.toHomeUi(context: Context): DeviceUi {
    val live = isPaired && isReachable
    val cloud = !live && ge.connect.cloud.JanusCloud.canSend(this)
    val plugins = loadedPlugins.values.sortedBy { ACTION_ORDER.indexOf(it.pluginKey).let { i -> if (i < 0) 99 else i } }
    return DeviceUi(
        id = deviceId,
        name = name,
        icon = symbol,
        reachable = isReachable,
        lastSeen = if (isPaired && !isReachable) ge.connect.connection.ConnectionHistory.events(context, deviceId)
            .firstOrNull { !it.connected }?.time else null,
        pairState = pairStatus,
        verificationKey = verificationKey,
        battery = if (live) ge.connect.sync.MacState.battery(this) else null,
        actions = if (live) plugins.flatMap { plugin ->
            plugin.getUiButtons().mapIndexed { index, button ->
                if (plugin.pluginKey == "SharePlugin") {
                    // Our Blip-style send sheet (photos, files, camera, audio) instead of a plain file picker.
                    DeviceAction(context.getString(R.string.send_action), R.drawable.ms_upload_filled) { activity ->
                        activity.startActivity(ge.connect.share.SendSheetActivity.intent(activity, deviceId))
                    }
                } else {
                    // Less-used tools go to the "More" list instead of the big tiles.
                    val secondary = plugin.pluginKey in SECONDARY_PLUGINS
                    DeviceAction(
                        button.name, actionSymbol(plugin.pluginKey, index) ?: button.iconRes,
                        summary = actionSummary(context, plugin.pluginKey, index), secondary = secondary, run = button.onClick,
                    )
                }
            }
        } + listOfNotNull(
            // Someone else's device: send a piece of text or a link on purpose (no clipboard sync with them).
            DeviceAction(context.getString(R.string.send_text_action), R.drawable.ms_notes) { activity ->
                activity.startActivity(ge.connect.share.SendTextActivity.intent(activity, deviceId))
            }.takeIf { !ge.connect.devices.DeviceOwnership.isMine(this) && getPlugin(org.kde.kdeconnect.plugins.share.SharePlugin::class.java) != null },
            // Phone ↔ phone: that phone's camera as a remote (both run our app).
            DeviceAction(
                context.getString(R.string.camera_action), R.drawable.ms_photo_camera,
                summary = context.getString(R.string.camera_action_summary), secondary = true,
            ) { activity -> activity.startActivity(ge.connect.camera.CameraRemoteActivity.intent(activity, deviceId)) }
                .takeIf { getPlugin(ge.connect.camera.GeCameraPlugin::class.java) != null },
            // Notes synced with the owner's Mac (R29): a big tile, where Remote input was.
            DeviceAction(
                context.getString(R.string.notes_title), R.drawable.ms_sticky_note_2_filled,
                summary = context.getString(R.string.notes_action_summary),
            ) { activity -> activity.startActivity(ge.connect.notes.NotesActivity.intent(activity)) }
                .takeIf { ge.connect.devices.DeviceOwnership.isMine(this) && !ge.connect.sync.PeerState.isPhone(this) && !ge.connect.tv.TvDevices.isTv(this) },
        ) + menuActions(context, this, plugins) else if (cloud) listOfNotNull(
            // Away from the local network: files (R36), the clipboard as text and notes (R37) go through Janus Cloud.
            DeviceAction(context.getString(R.string.send_action), R.drawable.ms_upload_filled) { activity ->
                activity.startActivity(ge.connect.share.SendSheetActivity.intent(activity, deviceId))
            },
            DeviceAction(context.getString(R.string.send_clipboard), R.drawable.ms_content_paste_filled) { activity ->
                val text = activity.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
                    ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
                if (text.isBlank()) {
                    android.widget.Toast.makeText(activity, R.string.cloud_clipboard_empty, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    ge.connect.cloud.CloudCompanion.sendText(this, text, link = false)
                    android.widget.Toast.makeText(activity, activity.getString(R.string.share_sent, name), android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            DeviceAction(
                context.getString(R.string.notes_title), R.drawable.ms_sticky_note_2,
                summary = context.getString(R.string.notes_action_summary), secondary = true,
            ) { activity -> activity.startActivity(ge.connect.notes.NotesActivity.intent(activity)) },
        ) else emptyList(),
        // Clipboard's menu entry duplicates its tile.
        menuEntries = if (live) plugins.filter { it.pluginKey != "ClipboardPlugin" }.flatMap { plugin ->
            if (plugin.pluginKey == "PingPlugin") {
                // Our ping, which the Mac app can show (ge.connect.ping, see GePing).
                plugin.getUiMenuEntries().map { entry -> Plugin.PluginUiMenuEntry(entry.name) { activity -> GePing.send(activity, this) } }
            } else plugin.getUiMenuEntries()
        } else emptyList(),
        permissionRequests = if (live) {
            pluginsWithoutPermissions.values.filter { isPluginEnabled(it.pluginKey) }.map { PermissionRequest(it.displayName, it, false) } +
                pluginsWithoutOptionalPermissions.values.filter { isPluginEnabled(it.pluginKey) }.map { PermissionRequest(it.displayName, it, true) }
        } else emptyList(),
        appearance = DeviceAppearance.of(context, this),
        macControls = if (live || cloud) macControls(context, this) else emptyList(),
        isPhone = ge.connect.sync.PeerState.isPhone(this),
        nowPlaying = if (live) nowPlaying(this) else null,
        mine = ge.connect.devices.DeviceOwnership.isMine(this),
        ownershipAnswered = ge.connect.devices.DeviceOwnership.isAnswered(context, deviceId),
        cloud = cloud,
        cloudConnected = cloud && ge.connect.cloud.JanusCloud.link.value == ge.connect.cloud.JanusCloud.Link.CONNECTED,
        isTv = ge.connect.tv.TvDevices.isTv(this),
    )
}

/** Published by the Mac but not shown (owner's choice): Lock covers both. */
private val HIDDEN_MAC_CONTROLS = setOf("ge-displaysleep", "ge-sleep")
private val MAC_CONTROL_ORDER = listOf("ge-lock", "ge-mute", "ge-findmac", "ge-screenshot")

private fun macControls(context: Context, device: Device): List<MacControl> {
    val prefs = context.getSharedPreferences("ge_connect_mac_controls", Context.MODE_PRIVATE)
    val plugin = device.getPlugin(org.kde.kdeconnect.plugins.runcommand.RunCommandPlugin::class.java)
    val commands = if (plugin != null) {
        org.kde.kdeconnect.plugins.runcommand.GeRunCommands.commands(plugin).also { list ->
            // Remembered, so the Mac's actions can be run through Janus Cloud while it's away (R37).
            val json = org.json.JSONArray(list.filter { it.first.startsWith("ge-") }.map { org.json.JSONArray(listOf(it.first, it.second)) }).toString()
            if (prefs.getString(device.deviceId, null) != json) prefs.edit { putString(device.deviceId, json) }
        }
    } else {
        runCatching {
            val array = org.json.JSONArray(prefs.getString(device.deviceId, "[]"))
            (0 until array.length()).map { array.getJSONArray(it).let { pair -> pair.getString(0) to pair.getString(1) } }
        }.getOrDefault(emptyList())
    }
    return commands
        // The owner's own commands stay under More → Run Command; Display off was dropped (R19).
        .filter { (key, _) -> key.startsWith("ge-") && key !in HIDDEN_MAC_CONTROLS }
        .sortedBy { (key, _) -> MAC_CONTROL_ORDER.indexOf(key).let { if (it < 0) 99 else it } }
        .map { (key, name) ->
            when (key) {
                "ge-lock" -> MacControl(key, context.getString(R.string.mac_lock), R.drawable.ms_lock_filled)
                "ge-mute" -> MacControl(key, context.getString(R.string.mac_mute), R.drawable.ms_volume_off)
                "ge-screenshot" -> MacControl(key, context.getString(R.string.mac_screenshot), R.drawable.ms_screenshot_monitor)
                "ge-findmac" -> MacControl(key, context.getString(R.string.mac_find), R.drawable.ms_ring_volume)
                else -> MacControl(key, name, R.drawable.ms_terminal)
            }
        }
}

fun Device.encryptionInfo(context: Context): String = buildString {
    appendLine(context.getString(R.string.my_device_fingerprint))
    appendLine(SslHelper.getCertificateHash(SslHelper.certificate))
    appendLine()
    appendLine(context.getString(R.string.remote_device_fingerprint))
    appendLine(SslHelper.getCertificateHash(certificate))
    appendLine()
    append(context.getString(R.string.protocol_version)).append(' ').append(protocolVersion)
}

// Remote input is under More too (owner, 2026-10-16): Notes takes its tile.
private val SECONDARY_PLUGINS = setOf("RunCommandPlugin", "PresenterPlugin", "MousePadPlugin")

private fun actionSummary(context: Context, pluginKey: String, buttonIndex: Int): String? = when {
    pluginKey == "RunCommandPlugin" -> context.getString(R.string.more_commands_summary)
    pluginKey == "PresenterPlugin" -> context.getString(R.string.more_presenter_summary)
    pluginKey == "MousePadPlugin" && buttonIndex > 0 -> context.getString(R.string.more_tv_summary)
    pluginKey == "MousePadPlugin" -> context.getString(R.string.more_remote_summary)
    else -> null
}

// Most-used first.
private val ACTION_ORDER = listOf(
    "SharePlugin", "ClipboardPlugin", "MousePadPlugin", "MprisPlugin", "RunCommandPlugin", "PresenterPlugin",
)

@DrawableRes
private fun actionSymbol(pluginKey: String, buttonIndex: Int): Int? = when (pluginKey) {
    "SharePlugin" -> R.drawable.ms_upload_filled
    "ClipboardPlugin" -> R.drawable.ms_content_paste_filled
    "MousePadPlugin" -> if (buttonIndex == 0) R.drawable.ms_mouse_filled else R.drawable.ms_tv_filled
    "MprisPlugin" -> R.drawable.ms_play_circle_filled
    "RunCommandPlugin" -> R.drawable.ms_terminal_filled
    "PresenterPlugin" -> R.drawable.ms_co_present_filled
    else -> null
}

private fun nowPlaying(device: Device): NowPlaying? {
    val plugin = device.getPlugin(org.kde.kdeconnect.plugins.mpris.MprisPlugin::class.java) ?: return null
    val player = plugin.playingPlayer
        ?: plugin.playerList.mapNotNull { plugin.getPlayerStatus(it) }.firstOrNull { it.title.isNotBlank() }
        ?: return null
    if (player.title.isBlank()) return null
    return NowPlaying(
        title = player.title,
        artist = listOf(player.artist, player.playerName).filter { it.isNotBlank() }.joinToString(" · "),
        playing = player.isPlaying,
        art = runCatching { player.getAlbumArt() }.getOrNull(),
        canPrevious = player.isGoPreviousAllowed,
        canNext = player.isGoNextAllowed,
        playPause = { player.sendPlayPause() },
        previous = { player.sendPrevious() },
        next = { player.sendNext() },
    )
}

/** Filled device-type icons (the widget's picture for devices without artwork). */
object DeviceModelIcons {
    val DeviceType.filledIcon: Int
        get() = when (this) {
            DeviceType.LAPTOP -> R.drawable.ms_laptop_mac_filled
            DeviceType.PHONE -> R.drawable.ms_smartphone_filled
            else -> R.drawable.ms_computer_filled
        }
}

/** The plugins' menu entries (Ring, Send ping…) as "More" actions on Home — they're actions, not settings. */
private fun menuActions(context: Context, device: Device, plugins: List<Plugin>): List<DeviceAction> =
    plugins.filter { it.pluginKey != "ClipboardPlugin" }.flatMap { plugin ->
        plugin.getUiMenuEntries().map { entry ->
            val (icon, summary) = when (plugin.pluginKey) {
                "FindRemoteDevicePlugin" -> R.drawable.ms_notifications_active to context.getString(R.string.more_ring_summary)
                "PingPlugin" -> R.drawable.ms_notifications to context.getString(R.string.more_ping_summary)
                else -> R.drawable.ms_sync_alt to null
            }
            DeviceAction(entry.name, icon, summary = summary, secondary = true) { activity ->
                // Our ping, which the Mac app can show (ge.connect.ping, see GePing).
                if (plugin.pluginKey == "PingPlugin") GePing.send(activity, device) else entry.onClick(activity)
            }
        }
    }
