/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ge.connect.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ge.connect.cloud.CloudPeer
import ge.connect.cloud.JanusCloud
import ge.connect.ui.components.ListGroup
import ge.connect.ui.components.NavRow
import ge.connect.ui.components.SectionHeader
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import org.kde.kdeconnect_tp.R

/** Settings → Janus Cloud (R36): how the cloud link is doing, the linked Macs, and a switch for testing. */
class CloudActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AppTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        val peers by JanusCloud.peers.collectAsState()
        val link by JanusCloud.link.collectAsState()
        var force by remember { mutableStateOf(JanusCloud.force) }
        var confirmOff by remember { mutableStateOf<CloudPeer?>(null) }
        val background = LocalAppSurfaces.current.background
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = background,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.cloud_title)) },
                    navigationIcon = {
                        IconButton(onClick = ::finish) { Icon(painterResource(R.drawable.ms_arrow_back), stringResource(R.string.navigate_back)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            LazyColumn(
                // Below the title bar rather than under it: overlapping, a TV remote's focus can't leave the bar.
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = 0.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                item {
                    val connected = link == JanusCloud.Link.CONNECTED
                    ListGroup(listOf { s ->
                        NavRow(
                            s, if (peers.isEmpty() || link == JanusCloud.Link.RECONNECT) R.drawable.ms_cloud_off else R.drawable.ms_cloud,
                            stringResource(statusTitle(peers, link)), stringResource(statusSummary(peers, link)),
                            iconContainer = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                            iconColor = if (connected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {}
                    })
                    Text(
                        stringResource(R.string.cloud_privacy),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                if (peers.isNotEmpty()) item {
                    SectionHeader(stringResource(R.string.cloud_linked))
                    ListGroup(peers.map { peer ->
                        { s ->
                            NavRow(
                                s, R.drawable.ms_laptop_mac, peer.name,
                                stringResource(if (peer.ready) R.string.cloud_peer_ready else R.string.cloud_peer_waiting),
                                trailing = { Text(stringResource(R.string.cloud_turn_off), color = MaterialTheme.colorScheme.primary) },
                            ) { confirmOff = peer }
                        }
                    })
                }
                item {
                    SectionHeader(stringResource(R.string.cloud_testing))
                    ListGroup(listOf { s ->
                        NavRow(
                            s, R.drawable.ms_sync_alt, stringResource(R.string.cloud_force), stringResource(R.string.cloud_force_summary),
                            trailing = { Switch(checked = force, onCheckedChange = null) },
                        ) {
                            force = !force
                            JanusCloud.force = force
                        }
                    })
                }
            }
        }

        confirmOff?.let { peer ->
            AlertDialog(
                onDismissRequest = { confirmOff = null },
                title = { Text(stringResource(R.string.cloud_turn_off_title, peer.name)) },
                text = { Text(stringResource(R.string.cloud_turn_off_text)) },
                confirmButton = {
                    TextButton(onClick = { JanusCloud.turnOff(peer.kdeId); confirmOff = null }) { Text(stringResource(R.string.cloud_turn_off)) }
                },
                dismissButton = { TextButton(onClick = { confirmOff = null }) { Text(stringResource(R.string.settings_cancel)) } },
            )
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, CloudActivity::class.java)

        @StringRes
        fun statusTitle(peers: List<CloudPeer>, link: JanusCloud.Link): Int = when {
            peers.isEmpty() -> R.string.cloud_status_off
            link == JanusCloud.Link.RECONNECT || peers.none { it.ready } -> R.string.cloud_status_reconnect
            link == JanusCloud.Link.CONNECTED -> R.string.cloud_status_connected
            link == JanusCloud.Link.CONNECTING -> R.string.cloud_status_connecting
            link == JanusCloud.Link.CLOCK -> R.string.cloud_status_clock
            else -> R.string.cloud_status_ready
        }

        @StringRes
        fun statusSummary(peers: List<CloudPeer>, link: JanusCloud.Link): Int = when {
            peers.isEmpty() -> R.string.cloud_status_off_summary
            link == JanusCloud.Link.RECONNECT || peers.none { it.ready } -> R.string.cloud_status_reconnect_summary
            link == JanusCloud.Link.CONNECTED -> R.string.cloud_status_connected_summary
            link == JanusCloud.Link.CONNECTING -> R.string.cloud_status_connecting_summary
            link == JanusCloud.Link.CLOCK -> R.string.cloud_status_clock_summary
            else -> R.string.cloud_status_ready_summary
        }
    }
}
