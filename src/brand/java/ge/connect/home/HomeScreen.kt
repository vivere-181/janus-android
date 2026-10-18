/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ge.connect.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import ge.connect.ui.components.IconBadge
import ge.connect.ui.theme.LocalAppSurfaces
import ge.connect.ui.components.ListGroup
import ge.connect.ui.components.NavRow
import ge.connect.ui.components.SectionHeader
import org.kde.kdeconnect.PairingHandler.PairState
import org.kde.kdeconnect.plugins.battery.BatteryPlugin
import org.kde.kdeconnect_tp.R

@Composable
fun HomeScreen(ui: DeviceUi?, refreshing: Boolean, pairError: String?, callbacks: HomeCallbacks) {
    var showMore by remember { mutableStateOf(false) }
    var showPermissions by remember { mutableStateOf(false) }
    val moreLabel = stringResource(R.string.home_section_more)
    val background = LocalAppSurfaces.current.background
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val transfers by ge.connect.transfers.Transfers.items.collectAsStateWithLifecycle()
    val snackbars = remember { androidx.compose.material3.SnackbarHostState() }
    MacActionSnackbars(ui?.id, snackbars)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbars) },
        // With a computer, its status card carries the name and settings, so there's no top bar.
        topBar = {
            if (ui == null) TopAppBar(
                title = { DeviceSwitcher(stringResource(R.string.app_name), callbacks::openDevices) },
                actions = {
                    IconButton(onClick = callbacks::openSettings) {
                        Icon(painterResource(R.drawable.ms_settings), stringResource(R.string.settings_title))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (ui == null) {
            EmptyHome(Modifier.padding(padding), callbacks::openDevices)
            return@Scaffold
        }
        // A TV: its remote, on one screen (and nothing else of the usual Home).
        if (ui.paired && ui.isTv) {
            ge.connect.tv.TvRemote(ui.id, ui.name, callbacks::openDevices, callbacks::openPreferences, Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            // Clipped below the status bar rather than scrolling under it.
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Not paired yet: one card about pairing and nothing else.
            if (!ui.paired) {
                item { PairingCard(ui, pairError, callbacks) }
                return@LazyColumn
            }
            item { StatusCard(ui, refreshing, callbacks) }
            ui.nowPlaying?.let { playing -> item { NowPlayingCard(ui, playing) } }
            if (ui.actions.isNotEmpty()) {
                val more = ui.actions.filter { it.secondary }
                val tiles = ui.actions.filterNot { it.secondary } + listOfNotNull(
                    DeviceAction(moreLabel, R.drawable.ms_widgets_filled) { showMore = true }.takeIf { more.isNotEmpty() },
                )
                item { ActionGrid(tiles, callbacks::runAction) }
            }
            // This device's transfers; until there are a few, placeholders show what the section is for.
            item {
                val send = ui.actions.firstOrNull { it.icon == R.drawable.ms_upload_filled }
                RecentTransfers(transfers.filter { it.deviceId == ui.id }, callbacks::openTransfers, onSend = send?.let { { callbacks.runAction(it) } })
            }
            if (ui.permissionRequests.isNotEmpty()) {
                item { PermissionsCard(ui.permissionRequests.size) { showPermissions = true } }
            }
        }
    }

    if (ui != null && ui.paired && !ui.ownershipAnswered) {
        val context = androidx.compose.ui.platform.LocalContext.current
        // Asked once after pairing; dismissing leaves it unanswered (treated as someone else's) and asks again later.
        ge.connect.devices.OwnershipSheet(ui.name, ui.icon, current = null, onDismiss = {}) { mine ->
            ge.connect.devices.DeviceOwnership.set(context, ui.id, mine)
            callbacks.refresh()
        }
    }
    if (showPermissions && ui != null) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showPermissions = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = LocalAppSurfaces.current.background,
        ) {
            Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(
                    stringResource(R.string.home_section_needs_permission),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    modifier = Modifier.padding(start = 8.dp, bottom = 16.dp),
                )
                ListGroup(ui.permissionRequests.map { request ->
                    { shapes ->
                        NavRow(
                            shapes, R.drawable.ms_warning_filled, request.featureName,
                            summary = stringResource(R.string.home_needs_permission_summary),
                            iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                            iconColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ) {
                            showPermissions = false
                            callbacks.requestPermission(request)
                        }
                    }
                })
            }
        }
    }
    if (showMore && ui != null) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showMore = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = LocalAppSurfaces.current.background,
        ) {
            Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(
                    moreLabel,
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    modifier = Modifier.padding(start = 8.dp, bottom = 16.dp),
                )
                ListGroup(ui.actions.filter { it.secondary }.map { action ->
                    { shapes ->
                        NavRow(shapes, action.icon, action.label, action.summary) {
                            showMore = false
                            callbacks.runAction(action)
                        }
                    }
                })
            }
        }
    }
}

/** Google Home-style title: current device with a dropdown affordance, opens the device list. */
@Composable
private fun DeviceSwitcher(name: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = LocalAppSurfaces.current.background) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(
                painterResource(R.drawable.ms_keyboard_arrow_down),
                contentDescription = stringResource(R.string.home_switch_device),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun StatusCard(ui: DeviceUi, refreshing: Boolean, callbacks: HomeCallbacks) {
    // A TV's card is just its name: the remote below has its own connection (Janus on the TV needn't run).
    val live = ui.paired && (ui.reachable || ui.isTv)
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = if (live) colors.primaryContainer else LocalAppSurfaces.current.card,
        contentColor = if (live) colors.onPrimaryContainer else colors.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            // Computer name (tap to switch computers) and app settings.
            Row(
                Modifier.fillMaxWidth().offset(y = (-4).dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f, fill = false)
                        .offset(x = (-8).dp)
                        .clip(CircleShape)
                        .clickable(onClick = callbacks::openDevices)
                        .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        ui.name,
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        painterResource(R.drawable.ms_keyboard_arrow_down),
                        contentDescription = stringResource(R.string.home_switch_device),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                IconButton(onClick = callbacks::openPreferences, modifier = Modifier.offset(x = 8.dp)) {
                    Icon(painterResource(R.drawable.ms_settings), stringResource(R.string.device_settings_title))
                }
            }
            // Smaller picture of the computer with a status pill next to it.
            if (!ui.isTv) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val art = ui.appearance.art
                if (art != null) {
                    androidx.compose.foundation.Image(
                        painterResource(art), null,
                        Modifier.size(84.dp, 56.dp).alpha(if (live) 1f else 0.5f),
                        colorFilter = if (live) null else androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                            androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) },
                        ),
                    )
                } else Box(
                    Modifier
                        .size(56.dp)
                        .background(if (live) colors.primary else colors.surfaceContainerHighest, MaterialShapes.Cookie9Sided.toShape()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(ui.icon), null,
                        tint = if (live) colors.onPrimary else colors.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    StatusPill(ui, live)
                    ui.lastSeen?.let { time ->
                        Text(
                            lastSeenText(time),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 6.dp),
                        )
                    }
                    if (ui.isPhone && live && ui.mine) PhoneInfoLine(ui)
                    if (ui.paired && !ui.mine) {
                        Text(
                            stringResource(R.string.ownership_others_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 6.dp),
                        )
                    }
                }
            }
            if (ui.paired && !ui.reachable && !ui.isTv) {
                var showHelp by remember { mutableStateOf(false) }
                // Where the controls sit when connected: try again, or see what to check.
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { if (!refreshing) callbacks.refresh() },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        if (refreshing) LoadingIndicator(Modifier.size(22.dp), color = colors.onPrimary)
                        else Icon(painterResource(R.drawable.ms_refresh), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (refreshing) R.string.home_searching else R.string.home_try_again), maxLines = 1)
                    }
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { showHelp = true },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(painterResource(R.drawable.ms_help), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_troubleshoot), maxLines = 1)
                    }
                }
                if (showHelp) TroubleshootSheet(ui, refreshing, onRefresh = callbacks::refresh) { showHelp = false }
            }
            if (ui.macControls.isNotEmpty()) {
                MacControls(ui, callbacks::runMacControl, Modifier.padding(top = 20.dp))
            }
            if (ui.isPhone && live && ui.mine) PhoneControls(ui, Modifier.padding(top = 20.dp))
        }
    }
}

@Composable
private fun lastSeenText(time: Long): String {
    val now = System.currentTimeMillis()
    return if (now - time < android.text.format.DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.home_last_seen_now)
    else stringResource(
        R.string.home_last_seen,
        android.text.format.DateUtils.getRelativeTimeSpanString(time, now, android.text.format.DateUtils.MINUTE_IN_MILLIS),
    )
}

/** What to check when the computer can't be reached, with Wi-Fi settings and Try again. */
@Composable
private fun TroubleshootSheet(ui: DeviceUi, refreshing: Boolean, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LocalAppSurfaces.current.background,
    ) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.troubleshoot_title, ui.name),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.padding(start = 8.dp, bottom = 16.dp),
            )
            val tips = listOf(
                Triple(R.drawable.ms_power_settings_new, R.string.troubleshoot_awake, R.string.troubleshoot_awake_summary),
                Triple(R.drawable.ms_wifi, R.string.troubleshoot_wifi, R.string.troubleshoot_wifi_summary),
                Triple(R.drawable.ms_laptop_mac, R.string.troubleshoot_app, R.string.troubleshoot_app_summary),
                Triple(R.drawable.ms_vpn_lock, R.string.troubleshoot_vpn, R.string.troubleshoot_vpn_summary),
            )
            ListGroup(tips.map { (icon, title, summary) ->
                { s: androidx.compose.material3.ListItemShapes -> ge.connect.ui.components.InfoRow(s, icon, stringResource(title), stringResource(summary)) }
            })
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) },
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text(stringResource(R.string.troubleshoot_wifi_settings), maxLines = 1) }
                Button(
                    onClick = {
                        if (!refreshing) onRefresh()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text(stringResource(R.string.home_try_again), maxLines = 1) }
            }
        }
    }
}

/** "● Connected · 🔋 100%": green dot while connected, grey otherwise. */
@Composable
private fun StatusPill(ui: DeviceUi, live: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (live) colors.surfaceContainerHigh else colors.surfaceContainerHighest,
        contentColor = colors.onSurface,
    ) {
        Row(Modifier.padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.sp)
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        when {
                            live -> androidx.compose.ui.graphics.Color(0xFF5BD46F)
                            ui.cloudConnected -> colors.primary
                            else -> colors.outline
                        },
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    when {
                        !ui.paired -> R.string.pair_title_not_paired
                        ui.reachable -> R.string.home_connected
                        ui.cloud -> R.string.home_via_cloud
                        else -> R.string.home_not_connected
                    }
                ),
                style = style, maxLines = 1, softWrap = false,
            )
            ui.battery?.takeIf { live }?.let { battery ->
                Text(" · ", style = style, maxLines = 1, color = colors.onSurfaceVariant)
                Icon(
                    painterResource(
                        when {
                            battery.isCharging -> R.drawable.ms_battery_charging_full
                            BatteryPlugin.isLowBattery(battery) -> R.drawable.ms_battery_alert
                            else -> R.drawable.ms_battery_full
                        }
                    ),
                    null, modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(2.dp))
                Text(stringResource(R.string.home_battery, battery.currentCharge), style = style, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Not paired yet: one centered card — the device, where pairing stands, the code to compare, and the next step. */
@Composable
private fun PairingCard(ui: DeviceUi, pairError: String?, callbacks: HomeCallbacks) {
    val colors = MaterialTheme.colorScheme
    val incoming = ui.pairState == PairState.RequestedByPeer
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = if (incoming) colors.primaryContainer else LocalAppSurfaces.current.card,
        contentColor = if (incoming) colors.onPrimaryContainer else colors.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            // Same header as a paired device: name (switch devices) and settings.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f, fill = false).offset(x = (-8).dp).clip(CircleShape).clickable(onClick = callbacks::openDevices)
                        .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ui.name, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(painterResource(R.drawable.ms_keyboard_arrow_down), stringResource(R.string.home_switch_device), modifier = Modifier.padding(start = 4.dp))
                }
                IconButton(onClick = callbacks::openSettings, modifier = Modifier.offset(x = 8.dp)) {
                    Icon(painterResource(R.drawable.ms_settings), stringResource(R.string.settings_title))
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val art = ui.appearance.art
                if (art != null) {
                    androidx.compose.foundation.Image(painterResource(art), null, Modifier.size(150.dp, 100.dp))
                } else {
                    Box(
                        Modifier.size(100.dp).background(if (incoming) colors.primary else colors.secondaryContainer, MaterialShapes.Cookie9Sided.toShape()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(ui.icon), null, tint = if (incoming) colors.onPrimary else colors.onSecondaryContainer, modifier = Modifier.size(46.dp))
                    }
                }
                Text(
                    when (ui.pairState) {
                        PairState.Requested -> stringResource(R.string.pair_title_requested, ui.name)
                        PairState.RequestedByPeer -> stringResource(R.string.pair_title_incoming, ui.name)
                        else -> stringResource(R.string.pair_title_not_paired_yet)
                    },
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp),
                )
                Text(
                    when (ui.pairState) {
                        PairState.Requested -> stringResource(R.string.pair_text_requested, ui.name)
                        PairState.RequestedByPeer -> stringResource(R.string.pair_text_incoming)
                        else -> stringResource(if (ui.reachable) R.string.pair_text_not_paired else R.string.pair_text_not_reachable)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (incoming) colors.onPrimaryContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (ui.pairState != PairState.NotPaired && !ui.verificationKey.isNullOrEmpty()) {
                    Surface(
                        shape = CircleShape,
                        color = if (incoming) colors.surface.copy(alpha = 0.35f) else colors.secondaryContainer,
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.pair_key_label), style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(12.dp))
                            Text(ui.verificationKey, style = MaterialTheme.typography.titleLargeEmphasized, letterSpacing = 3.sp)
                        }
                    }
                }
                pairError?.let {
                    Text(
                        stringResource(R.string.pair_failed, it), color = colors.error, style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp),
                    )
                }
                val buttons = Modifier.fillMaxWidth().padding(top = 20.dp)
                when (ui.pairState) {
                    PairState.Requested -> Row(buttons, verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(40.dp))
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = callbacks::cancelPairing, modifier = Modifier.weight(1f).height(52.dp)) {
                            Text(stringResource(R.string.pair_action_cancel))
                        }
                    }
                    PairState.RequestedByPeer -> Row(buttons, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = callbacks::cancelPairing, modifier = Modifier.weight(1f).height(52.dp)) {
                            Text(stringResource(R.string.pair_action_decline))
                        }
                        Button(onClick = callbacks::acceptPairing, modifier = Modifier.weight(1f).height(52.dp)) {
                            Text(stringResource(R.string.pair_action_accept))
                        }
                    }
                    else -> Button(onClick = callbacks::requestPairing, enabled = ui.reachable, modifier = buttons.height(52.dp)) {
                        Text(stringResource(R.string.pair_action_pair))
                    }
                }
            }
        }
    }
}

/** Confirmations and errors for the Mac actions (R19), for the computer on screen. */
@Composable
private fun MacActionSnackbars(deviceId: String?, host: androidx.compose.material3.SnackbarHostState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(deviceId) {
        if (deviceId == null) return@LaunchedEffect
        ge.connect.sync.MacState.events.collect { event ->
            if (event.deviceId != deviceId) return@collect
            val message = when {
                !event.ok -> event.reason?.takeIf { it.isNotBlank() } ?: context.getString(R.string.mac_no_response)
                event.action == "lock" -> context.getString(R.string.mac_done_lock)
                event.action == "mute" -> context.getString(if (event.wasOn) R.string.mac_done_unmute else R.string.mac_done_mute)
                event.action == "findmac" -> context.getString(if (event.wasOn) R.string.mac_done_find_stop else R.string.mac_done_find)
                event.action == "screenshot" -> context.getString(R.string.mac_done_screenshot)
                else -> return@collect
            }
            host.currentSnackbarData?.dismiss()
            launch { host.showSnackbar(message) }
        }
    }
}

/**
 * "Control your Mac" (R14/R19): round buttons for the actions the computer publishes, showing what the
 * Mac reports: working → check/error, Unmute while muted, Stop sound while finding, Locked, Asleep.
 */
@Composable
private fun MacControls(ui: DeviceUi, onRun: (MacControl) -> Unit, modifier: Modifier = Modifier) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val states by ge.connect.sync.MacState.states.collectAsStateWithLifecycle()
    val state = states[ui.id] ?: ge.connect.sync.MacState.State()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ui.macControls.forEach { control ->
            MacControlButton(control, state, Modifier.weight(1f)) {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
                onRun(control)
            }
        }
    }
}

@Composable
private fun MacControlButton(control: MacControl, state: ge.connect.sync.MacState.State, modifier: Modifier, onClick: () -> Unit) {
    val action = control.key.removePrefix("ge-")
    val colors = MaterialTheme.colorScheme
    val finding = action == "findmac" && state.finding
    val active = (action == "mute" && state.muted == ge.connect.sync.MacState.Muted.ON) || (action == "lock" && state.locked) || finding
    val unavailable = action == "mute" && state.muted == ge.connect.sync.MacState.Muted.NA
    val label = when {
        action == "mute" && state.muted == ge.connect.sync.MacState.Muted.ON -> stringResource(R.string.mac_unmute)
        unavailable -> stringResource(R.string.mac_cant_mute)
        finding -> stringResource(R.string.mac_stop_sound)
        action == "lock" && state.locked -> stringResource(R.string.mac_locked)
        else -> control.label
    }
    val orange = androidx.compose.ui.graphics.Color(0xFFFF9F43)
    // On the status card (primaryContainer): tonal circles, filled primary when on.
    val container = when {
        finding -> orange
        active -> colors.primary
        else -> colors.surfaceContainerHigh
    }
    val content = when {
        finding -> androidx.compose.ui.graphics.Color(0xFF3A1D00)
        active -> colors.onPrimary
        else -> colors.primary
    }
    Column(
        modifier
            .alpha(if (unavailable) 0.45f else 1f)
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = !unavailable, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(52.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedContent(state.progress[action], label = "mac control") { progress ->
                when (progress) {
                    ge.connect.sync.MacState.Progress.WORKING -> androidx.compose.material3.LoadingIndicator(Modifier.size(40.dp), color = content)
                    ge.connect.sync.MacState.Progress.DONE -> Icon(painterResource(R.drawable.ms_check_circle_filled), null, tint = content, modifier = Modifier.size(28.dp))
                    ge.connect.sync.MacState.Progress.FAILED -> Icon(painterResource(R.drawable.ms_warning_filled), null, tint = colors.error, modifier = Modifier.size(26.dp))
                    null -> Icon(painterResource(if (finding) R.drawable.ms_close else control.icon), null, tint = content, modifier = Modifier.size(26.dp))
                }
            }
        }
        // One line, shrinking a little for long labels ("Screenshot", "Can't mute") in the narrow columns.
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp),
            textAlign = TextAlign.Center,
            maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Blip-style strip of the latest sent and received items, with a link to the full feed. */
@Composable
private fun RecentTransfers(items: List<ge.connect.transfers.Transfers.Item>, onSeeAll: () -> Unit, onSend: (() -> Unit)?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(stringResource(R.string.transfers_recent), Modifier.weight(1f).padding(top = 0.dp))
            TextButton(onClick = onSeeAll) { Text(stringResource(R.string.transfers_see_all)) }
        }
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.transfers_placeholder_hint),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
        }
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items.take(12).size, key = { items[it].id }) { index ->
                val item = items[index]
                ge.connect.transfers.TransferCard(
                    item,
                    Modifier
                        .animateItem()
                        .width(148.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .clickable { if (!item.active) ge.connect.transfers.openTransfer(context, item) },
                    compact = true,
                )
            }
            // Fill the row with placeholders until real transfers take their place.
            val placeholders = (PLACEHOLDER_COUNT - items.size).coerceAtLeast(0)
            items(placeholders, key = { "placeholder-$it" }) { index ->
                ge.connect.transfers.TransferPlaceholder(
                    PLACEHOLDER_ICONS[(items.size + index) % PLACEHOLDER_ICONS.size],
                    Modifier
                        .animateItem()
                        .width(148.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .then(if (onSend != null) Modifier.clickable(onClick = onSend) else Modifier),
                )
            }
        }
    }
}

private const val PLACEHOLDER_COUNT = 3
private val PLACEHOLDER_ICONS = listOf(R.drawable.ms_image, R.drawable.ms_description, R.drawable.ms_link)

/** Big tonal tiles, two per row; an odd last tile spans the full width. */
@Composable
private fun ActionGrid(actions: List<DeviceAction>, onClick: (DeviceAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { action ->
                    ActionTile(action.label, action.icon, Modifier.weight(1f)) { onClick(action) }
                }
            }
        }
    }
}

@Composable
private fun ActionTile(label: String, @DrawableRes icon: Int, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = LocalAppSurfaces.current.card,
        modifier = modifier.heightIn(min = 112.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(
                icon,
                container = MaterialTheme.colorScheme.secondaryContainer,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                size = 44,
            )
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}


@Composable
private fun EmptyHome(modifier: Modifier, onFind: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(128.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie12Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ms_devices), null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(56.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.home_empty_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onFind, modifier = Modifier.height(52.dp)) { Text(stringResource(R.string.home_empty_action)) }
    }
}

// Another phone (phone ↔ phone) ------------------------------------------------------------------------------------

/** Signal icon (+ network type when on mobile data) · Vibrate · Do not disturb, under the status pill. */
@Composable
private fun PhoneInfoLine(ui: DeviceUi) {
    val peers by ge.connect.sync.PeerState.peers.collectAsStateWithLifecycle()
    val peer = peers[ui.id] ?: return
    val colors = MaterialTheme.colorScheme
    val details = listOfNotNull(
        peer.networkType,
        when (peer.ringer) {
            ge.connect.sync.PeerState.Ringer.VIBRATE -> stringResource(R.string.peer_ringer_vibrate)
            ge.connect.sync.PeerState.Ringer.SILENT -> stringResource(R.string.peer_ringer_silent)
            else -> null
        },
        stringResource(R.string.peer_dnd).takeIf { peer.dnd == true },
    )
    if (peer.signal == null && details.isEmpty()) return
    Row(Modifier.padding(start = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        peer.signal?.let { bars ->
            Icon(
                painterResource(
                    when {
                        bars <= 1 -> R.drawable.ms_signal_cellular_alt_1_bar
                        bars == 2 -> R.drawable.ms_signal_cellular_alt_2_bar
                        else -> R.drawable.ms_signal_cellular_alt
                    }
                ),
                stringResource(R.string.peer_signal), tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp),
            )
            if (details.isNotEmpty()) Spacer(Modifier.width(6.dp))
        }
        if (details.isNotEmpty()) {
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

/** Ring, Sound (ring → vibrate → silent), Do not disturb and Flashlight for the other phone. */
@Composable
private fun PhoneControls(ui: DeviceUi, modifier: Modifier = Modifier) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val peers by ge.connect.sync.PeerState.peers.collectAsStateWithLifecycle()
    val peer = peers[ui.id] ?: ge.connect.sync.PeerState.Peer()
    val device = org.kde.kdeconnect.KdeConnect.getInstance().getDevice(ui.id) ?: return
    fun tap(block: () -> Unit) {
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
        block()
    }
    val ringer = peer.ringer ?: ge.connect.sync.PeerState.Ringer.NORMAL
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CardControl(R.drawable.ms_notifications_active, stringResource(R.string.peer_ring), false, Modifier.weight(1f)) {
            tap { ge.connect.sync.PeerState.ring(device) }
        }
        CardControl(
            when (ringer) {
                ge.connect.sync.PeerState.Ringer.NORMAL -> R.drawable.ms_volume_up
                ge.connect.sync.PeerState.Ringer.VIBRATE -> R.drawable.ms_vibration
                ge.connect.sync.PeerState.Ringer.SILENT -> R.drawable.ms_volume_off
            },
            stringResource(
                when (ringer) {
                    ge.connect.sync.PeerState.Ringer.NORMAL -> R.string.peer_ringer_sound
                    ge.connect.sync.PeerState.Ringer.VIBRATE -> R.string.peer_ringer_vibrate
                    ge.connect.sync.PeerState.Ringer.SILENT -> R.string.peer_ringer_silent
                }
            ),
            ringer != ge.connect.sync.PeerState.Ringer.NORMAL, Modifier.weight(1f),
        ) {
            tap {
                val next = ge.connect.sync.PeerState.Ringer.entries[(ringer.ordinal + 1) % ge.connect.sync.PeerState.Ringer.entries.size]
                ge.connect.sync.PeerState.setRinger(device, next)
            }
        }
        CardControl(R.drawable.ms_do_not_disturb_on, stringResource(R.string.peer_dnd_short), peer.dnd == true, Modifier.weight(1f)) {
            tap { ge.connect.sync.PeerState.setDnd(device, peer.dnd != true) }
        }
        if (peer.flashlight != null) {
            CardControl(
                if (peer.flashlight) R.drawable.ms_flashlight_on else R.drawable.ms_flashlight_off,
                stringResource(R.string.peer_flashlight), peer.flashlight, Modifier.weight(1f),
            ) { tap { ge.connect.sync.PeerState.setFlashlight(device, !peer.flashlight) } }
        }
    }
}

/** A round control on the status card; filled when on (same look as the Mac controls). */
@Composable
private fun CardControl(icon: Int, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(52.dp).background(if (on) colors.primary else colors.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, tint = if (on) colors.onPrimary else colors.primary, modifier = Modifier.size(26.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp),
            textAlign = TextAlign.Center,
            maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** What's playing on the device, with previous / play-pause / next. */
@Composable
private fun NowPlayingCard(ui: DeviceUi, playing: NowPlaying) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = LocalAppSurfaces.current.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.now_playing_on, ui.name),
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val art = playing.art
                if (art != null) {
                    androidx.compose.foundation.Image(
                        art.asImageBitmap(), null,
                        Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.size(56.dp).background(colors.secondaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ms_music_note), null, tint = colors.onSecondaryContainer)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(playing.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (playing.artist.isNotEmpty()) {
                        Text(playing.artist, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = playing.previous, enabled = playing.canPrevious) {
                    Icon(painterResource(R.drawable.ms_skip_previous_filled), stringResource(R.string.now_playing_previous))
                }
                androidx.compose.material3.FilledIconButton(onClick = playing.playPause, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painterResource(if (playing.playing) R.drawable.ms_pause_filled else R.drawable.ms_play_arrow_filled),
                        stringResource(if (playing.playing) R.string.now_playing_pause else R.string.now_playing_play),
                    )
                }
                IconButton(onClick = playing.next, enabled = playing.canNext) {
                    Icon(painterResource(R.drawable.ms_skip_next_filled), stringResource(R.string.now_playing_next))
                }
            }
        }
    }
}

/** "2 features need permission · Review": one compact card instead of a list on Home. */
@Composable
private fun PermissionsCard(count: Int, onReview: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onReview, shape = RoundedCornerShape(24.dp), color = colors.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ms_warning_filled), null, tint = colors.onTertiaryContainer, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                if (count == 1) stringResource(R.string.home_permissions_one) else stringResource(R.string.home_permissions_title, count),
                style = MaterialTheme.typography.titleSmall, color = colors.onTertiaryContainer, modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.home_permissions_review), style = MaterialTheme.typography.labelLarge, color = colors.onTertiaryContainer)
        }
    }
}
