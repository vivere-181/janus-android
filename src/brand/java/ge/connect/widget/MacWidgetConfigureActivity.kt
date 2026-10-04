/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ge.connect.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import ge.connect.home.symbol
import ge.connect.ui.components.ListGroup
import ge.connect.ui.components.NavRow
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import kotlinx.coroutines.launch
import org.kde.kdeconnect_tp.R

/**
 * "Customize" for one widget (shown when it's added, and from the launcher's long-press menu): pick up to four
 * actions; they appear in the order they were picked.
 */
class MacWidgetConfigureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        // Backing out keeps the widget with its current (or default) actions.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))

        setContent {
            AppTheme {
                val devices = remember {
                    org.kde.kdeconnect.KdeConnect.getInstance().devices.values
                        .filter { it.isPaired && ge.connect.devices.DeviceOwnership.isMine(it) }.sortedBy { it.name.lowercase() }
                }
                var deviceId by remember {
                    mutableStateOf(WidgetAction.deviceForWidget(this, appWidgetId) ?: ge.connect.home.SelectedDevice.resolve(this)?.deviceId ?: devices.firstOrNull()?.deviceId)
                }
                val isPhone = devices.firstOrNull { it.deviceId == deviceId }?.let { ge.connect.sync.PeerState.isPhone(it) } ?: false
                var chosen by remember { mutableStateOf(WidgetAction.forWidget(this, appWidgetId, isPhone)) }
                val background = LocalAppSurfaces.current.background
                val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
                Scaffold(
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    containerColor = background,
                    topBar = {
                        LargeFlexibleTopAppBar(
                            title = { Text(stringResource(R.string.widget_customize)) },
                            subtitle = { Text(stringResource(R.string.widget_customize_summary, WidgetAction.MAX)) },
                            navigationIcon = {
                                IconButton(onClick = ::finish) { Icon(painterResource(R.drawable.ms_arrow_back), stringResource(R.string.navigate_back)) }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                            scrollBehavior = scrollBehavior,
                        )
                    },
                    bottomBar = {
                        Button(
                            onClick = {
                                WidgetAction.setForWidget(this, appWidgetId, chosen)
                                WidgetAction.setDeviceForWidget(this, appWidgetId, deviceId)
                                lifecycleScope.launch {
                                    MacWidgetUpdates.request()
                                    runCatching { MacWidget().updateAll(this@MacWidgetConfigureActivity) }
                                    finish()
                                }
                            },
                            enabled = chosen.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(52.dp),
                        ) { Text(stringResource(R.string.widget_customize_done)) }
                    },
                ) { padding ->
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp,
                            top = padding.calculateTopPadding(),
                            bottom = padding.calculateBottomPadding() + 16.dp,
                        ),
                    ) {
                        if (devices.size > 1) item {
                            ge.connect.ui.components.SectionHeader(stringResource(R.string.widget_customize_device))
                            ListGroup(devices.map { device ->
                                { s: ListItemShapes ->
                                    NavRow(
                                        s, device.symbol, device.name,
                                        trailing = { androidx.compose.material3.RadioButton(selected = device.deviceId == deviceId, onClick = null) },
                                    ) {
                                        if (device.deviceId != deviceId) {
                                            deviceId = device.deviceId
                                            val phone = ge.connect.sync.PeerState.isPhone(device)
                                            // Keep what still fits the new kind of device; otherwise start from its defaults.
                                            val fitting = chosen.filter { it.fits(phone) }
                                            chosen = fitting + WidgetAction.defaults(phone).filter { it !in fitting }.take(WidgetAction.MAX - fitting.size)
                                        }
                                    }
                                }
                            })
                            ge.connect.ui.components.SectionHeader(stringResource(R.string.widget_customize_actions))
                        }
                        item {
                            ListGroup(WidgetAction.entries.filter { it.fits(isPhone) }.map { action ->
                                { s: ListItemShapes ->
                                    val position = chosen.indexOf(action)
                                    val full = chosen.size >= WidgetAction.MAX && position < 0
                                    NavRow(
                                        s, action.icon, stringResource(action.label),
                                        enabled = !full,
                                        trailing = { Order(position) },
                                    ) {
                                        chosen = if (position >= 0) chosen - action else chosen + action
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}

/** The tile's position (1–4) when chosen, an empty circle otherwise. */
@androidx.compose.runtime.Composable
private fun Order(position: Int) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(28.dp)
            .background(if (position >= 0) colors.primary else colors.surfaceContainerHighest, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (position >= 0) Text("${position + 1}", color = colors.onPrimary, style = MaterialTheme.typography.labelLarge)
    }
}
