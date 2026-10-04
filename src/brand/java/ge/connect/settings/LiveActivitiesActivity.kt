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
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import ge.connect.sync.LiveActivities
import ge.connect.ui.components.ListGroup
import ge.connect.ui.components.NavRow
import ge.connect.ui.components.SectionHeader
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import org.kde.kdeconnect_tp.R

/** "Live Activities on Mac" (R21): the master switch and a switch per app seen with live notifications. */
class LiveActivitiesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AppTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        val context = this
        var enabled by remember { mutableStateOf(LiveActivities.isEnabled(context)) }
        val apps = remember {
            LiveActivities.seenApps(context)
                .filter { runCatching { packageManager.getApplicationInfo(it, 0) }.isSuccess }
                .sortedBy { LiveActivities.appLabel(it).lowercase() }
        }
        var appStates by remember { mutableStateOf(apps.associateWith { LiveActivities.isAppEnabled(context, it) }) }
        val background = LocalAppSurfaces.current.background
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = background,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.live_title)) },
                    navigationIcon = {
                        IconButton(onClick = ::finish) { Icon(painterResource(R.drawable.ms_arrow_back), stringResource(R.string.navigate_back)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                item {
                    ListGroup(listOf { s ->
                        NavRow(
                            s, R.drawable.ms_sensors, stringResource(R.string.live_switch), stringResource(R.string.live_summary),
                            trailing = { Switch(checked = enabled, onCheckedChange = null) },
                        ) {
                            enabled = !enabled
                            LiveActivities.setEnabled(context, enabled)
                        }
                    })
                }
                item {
                    SectionHeader(stringResource(R.string.live_apps))
                    if (apps.isEmpty()) {
                        Text(
                            stringResource(R.string.live_apps_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    } else {
                        ListGroup(apps.map { pkg ->
                            { s: ListItemShapes ->
                                val on = appStates[pkg] ?: true
                                NavRow(
                                    s, null, LiveActivities.appLabel(pkg),
                                    enabled = enabled,
                                    leading = { AppIcon(pkg) },
                                    trailing = { Switch(checked = on && enabled, onCheckedChange = null, enabled = enabled) },
                                ) {
                                    LiveActivities.setAppEnabled(context, pkg, !on)
                                    appStates = appStates + (pkg to !on)
                                }
                            }
                        })
                    }
                }
            }
        }
    }

    @Composable
    private fun AppIcon(packageName: String) {
        val bitmap = remember(packageName) {
            runCatching { packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
        if (bitmap != null) Image(bitmap, null, Modifier.size(40.dp))
    }

    companion object {
        fun intent(context: Context) = Intent(context, LiveActivitiesActivity::class.java)
    }
}
