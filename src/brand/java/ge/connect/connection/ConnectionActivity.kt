/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ge.connect.connection

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ge.connect.home.symbol
import ge.connect.ui.components.ListGroup
import ge.connect.ui.components.NavRow
import ge.connect.ui.components.SectionHeader
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.DeviceStats
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.backends.lan.LanLink
import org.kde.kdeconnect.helpers.security.SslHelper
import org.kde.kdeconnect.ui.MainActivity
import org.kde.kdeconnect_tp.R

/** How the phone and a computer are connected: status, address, activity, history, keys. */
class ConnectionActivity : AppCompatActivity() {
    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val device = intent.getStringExtra(MainActivity.EXTRA_DEVICE_ID)?.let { KdeConnect.getInstance().getDevice(it) }
            ?: run { finish(); return }

        setContent {
            AppTheme {
                // Live values: refresh every 2 s while the screen is shown (not while it's in the background).
                val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                LaunchedEffect(lifecycle) {
                    lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { while (true) { delay(2000); tick++ } }
                }
                @Suppress("UNUSED_EXPRESSION") tick

                val background = LocalAppSurfaces.current.background
                val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
                Scaffold(
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    containerColor = background,
                    topBar = {
                        LargeFlexibleTopAppBar(
                            title = { Text(stringResource(R.string.connection_title)) },
                            subtitle = { Text(device.name) },
                            navigationIcon = {
                                IconButton(onClick = ::finish) { Icon(painterResource(R.drawable.ms_arrow_back), stringResource(R.string.navigate_back)) }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                            scrollBehavior = scrollBehavior,
                        )
                    },
                ) { padding ->
                    val context = this@ConnectionActivity
                    val history = ConnectionHistory.events(context, device.deviceId)
                    val activity = featureActivity(device)
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp,
                            top = padding.calculateTopPadding(),
                            bottom = padding.calculateBottomPadding() + 24.dp,
                        ),
                    ) {
                        item {
                            SectionHeader(stringResource(R.string.connection_status), Modifier.padding(top = 0.dp))
                            ListGroup(buildList {
                                val lastChange = history.firstOrNull()
                                add { s ->
                                    NavRow(
                                        s, if (device.isReachable) R.drawable.ms_wifi else R.drawable.ms_wifi_off,
                                        stringResource(if (device.isReachable) R.string.home_connected else R.string.home_not_connected),
                                        lastChange?.let {
                                            stringResource(
                                                if (it.connected) R.string.connection_since else R.string.connection_last_seen,
                                                formatTime(context, it.time),
                                            )
                                        },
                                        iconContainer = if (device.isReachable) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        iconColor = if (device.isReachable) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    ) {}
                                }
                                linkDescriptions(context, device).forEach { (title, detail) ->
                                    add { s -> NavRow(s, R.drawable.ms_sync_alt, title, detail) {} }
                                }
                                add { s ->
                                    NavRow(s, device.symbol, stringResource(R.string.connection_protocol), stringResource(R.string.connection_protocol_value, device.protocolVersion)) {}
                                }
                            })
                        }

                        item {
                            SectionHeader(stringResource(R.string.connection_activity))
                            if (activity.isEmpty()) {
                                EmptyNote(stringResource(R.string.connection_activity_empty))
                            } else {
                                ListGroup(activity.map { (name, counts) ->
                                    { s ->
                                        NavRow(s, null, name, describeCounts(context, counts)) {}
                                    }
                                })
                            }
                        }

                        item {
                            SectionHeader(stringResource(R.string.connection_history))
                            if (history.isEmpty()) {
                                EmptyNote(stringResource(R.string.connection_history_empty))
                            } else {
                                ListGroup(history.take(10).map { event ->
                                    { s ->
                                        NavRow(
                                            s, if (event.connected) R.drawable.ms_wifi else R.drawable.ms_wifi_off,
                                            stringResource(if (event.connected) R.string.connection_event_connected else R.string.connection_event_disconnected),
                                            formatTime(context, event.time),
                                            iconContainer = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ) {}
                                    }
                                })
                            }
                        }

                        item {
                            SectionHeader(stringResource(R.string.connection_security))
                            ListGroup(buildList {
                                add { s -> NavRow(s, R.drawable.ms_key, stringResource(R.string.connection_key_phone), SslHelper.getCertificateHash(SslHelper.certificate)) {} }
                                runCatching { SslHelper.getCertificateHash(device.certificate) }.getOrNull()?.let { hash ->
                                    add { s -> NavRow(s, R.drawable.ms_key, stringResource(R.string.connection_key_computer), hash) {} }
                                }
                                add { s -> NavRow(s, R.drawable.ms_badge, stringResource(R.string.connection_device_id), device.deviceId) {} }
                            })
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun EmptyNote(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
        )
    }

    companion object {
        fun intent(context: Context, deviceId: String): Intent =
            Intent(context, ConnectionActivity::class.java).putExtra(MainActivity.EXTRA_DEVICE_ID, deviceId)

        private fun formatTime(context: Context, time: Long): String =
            if (DateUtils.isToday(time)) DateFormat.getTimeFormat(context).format(time)
            else DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)

        /** One row per active link: how, and where to (for Wi-Fi). */
        private fun linkDescriptions(context: Context, device: Device): List<Pair<String, String?>> =
            device.activeLinks.map { link ->
                when (link) {
                    is LanLink -> context.getString(R.string.connection_via_wifi) to link.remoteAddress?.hostAddress
                    else -> context.getString(
                        if (link.name.contains("Bluetooth", ignoreCase = true)) R.string.connection_via_bluetooth else R.string.connection_via_other,
                    ) to null
                }
            }

        class Counts(var sent: Int = 0, var received: Int = 0, var failed: Int = 0)

        /** Last 24 h of packets, grouped by feature, busiest first. */
        private fun featureActivity(device: Device): List<Pair<String, Counts>> {
            val byFeature = LinkedHashMap<String, Counts>()
            for (summary in DeviceStats.getSummariesForDevice(device.deviceId)) {
                val counts = byFeature.getOrPut(featureName(summary.packetType)) { Counts() }
                counts.sent += summary.sentSuccessful
                counts.failed += summary.sentFailed
                counts.received += summary.received
            }
            return byFeature.entries.sortedByDescending { it.value.sent + it.value.received + it.value.failed }.map { it.key to it.value }
        }

        private fun featureName(packetType: String): String {
            val type = packetType.removePrefix("kdeconnect.")
            return when (type.substringBefore('.')) {
                "ping" -> "Ping"
                "battery" -> "Battery"
                "clipboard" -> "Clipboard"
                "notification" -> "Notifications"
                "share" -> "Sharing"
                "mousepad" -> "Remote input"
                "mpris" -> "Media"
                "telephony" -> "Calls"
                "sms" -> "Messages"
                "contacts" -> "Contacts"
                "runcommand" -> "Commands"
                "systemvolume" -> "Volume"
                "connectivity_report" -> "Signal"
                "presenter" -> "Presenter"
                "findmyphone" -> "Find my phone"
                else -> type
            }
        }

        private fun describeCounts(context: Context, c: Counts): String = buildList {
            add(context.getString(R.string.connection_sent, c.sent))
            add(context.getString(R.string.connection_received, c.received))
            if (c.failed > 0) add(context.getString(R.string.connection_failed, c.failed))
        }.joinToString(" · ")
    }
}
