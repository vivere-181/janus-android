/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

// ge.connect: lives in upstream's package to reach its package-private plugin API.
package org.kde.kdeconnect.plugins.runcommand

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ge.connect.ui.components.EmptyState
import ge.connect.ui.components.IconBadge
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.kde.kdeconnect.Device
import org.kde.kdeconnect_tp.R

/** Our version of upstream's RunCommandScreen (same parameters; RunCommandActivity calls this one). */
@Composable
fun GeRunCommandScreen(
    plugin: RunCommandPlugin,
    device: Device,
    commandList: SnapshotStateList<CommandEntry>,
    onBackPressedDispatcher: OnBackPressedDispatcher,
    onCopyUrlToClipboard: (CommandEntry) -> Unit,
    onUpdate: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }

    DisposableEffect(plugin) {
        val callback = RunCommandPlugin.CommandsChangedCallback { scope.launch(Dispatchers.Main.immediate) { onUpdate() } }
        plugin.addCommandsUpdatedCallback(callback)
        onDispose { plugin.removeCommandsUpdatedCallback(callback) }
    }

    val showOutput = commandList.isNotEmpty() && device.canSendPacketType(RunCommandPlugin.PACKET_TYPE_RUNCOMMAND_OUTPUT)

    AppTheme {
        val background = LocalAppSurfaces.current.background
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = background,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.runcommand_title)) },
                    subtitle = { Text(device.name) },
                    navigationIcon = {
                        IconButton(onClick = { onBackPressedDispatcher.onBackPressed() }) {
                            Icon(painterResource(R.drawable.ms_arrow_back), stringResource(R.string.navigate_back))
                        }
                    },
                    actions = {
                        if (showOutput) {
                            IconButton(onClick = { plugin.clearOutput() }) {
                                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.runcommand_clear_output))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                if (plugin.canAddCommand()) {
                    ExtendedFloatingActionButton(
                        onClick = { plugin.sendSetupPacket(); showAddDialog = true },
                        icon = { Icon(painterResource(R.drawable.ms_add), null) },
                        text = { Text(stringResource(R.string.runcommand_add)) },
                    )
                }
            },
        ) { padding ->
            if (commandList.isEmpty()) {
                var text = stringResource(R.string.addcommand_explanation)
                if (!plugin.canAddCommand()) text += "\n" + stringResource(R.string.addcommand_explanation2)
                EmptyState(R.drawable.ms_terminal, stringResource(R.string.runcommand_empty_title), text, Modifier.padding(padding))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 96.dp, // room for the FAB
                    ),
                ) {
                    if (showOutput) {
                        item { OutputCard(plugin.output, plugin) }
                    }
                    items(commandList.size, key = { commandList[it].key }) { index ->
                        CommandRow(commandList[index], index, commandList.size, plugin, onCopyUrlToClipboard)
                    }
                }
            }
        }

        if (showAddDialog) {
            val close = { showAddDialog = false; onUpdate() }
            AlertDialog(
                onDismissRequest = close,
                title = { Text(stringResource(R.string.add_command)) },
                text = { Text(stringResource(R.string.add_command_description)) },
                confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.ok)) } },
            )
        }
    }
}

@Composable
private fun CommandRow(
    command: CommandEntry,
    index: Int,
    count: Int,
    plugin: RunCommandPlugin,
    onCopyUrlToClipboard: (CommandEntry) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.padding(bottom = ListItemDefaults.SegmentedGap)) {
        SegmentedListItem(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                plugin.runCommand(command.key)
            },
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                menu = true
            },
            shapes = ListItemDefaults.segmentedShapes(index, count),
            colors = ListItemDefaults.segmentedColors(containerColor = LocalAppSurfaces.current.card),
            leadingContent = { IconBadge(R.drawable.ms_terminal) },
            supportingContent = {
                Text(command.command, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
            },
            trailingContent = { Icon(painterResource(R.drawable.ms_play_arrow_filled), null) },
        ) {
            Text(command.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.copy_url_to_clipboard)) },
                onClick = { menu = false; onCopyUrlToClipboard(command) },
            )
        }
    }
}
