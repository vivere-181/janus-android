/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

// ge.connect: lives in upstream's package to use AppDatabase's package-private API.
package org.kde.kdeconnect.plugins.notifications

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import ge.connect.ui.components.ListGroup
import ge.connect.ui.components.SectionHeader
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kde.kdeconnect_tp.R

/** Choose which apps' notifications are sent to the computer. Replaces NotificationFilterActivity. */
class GeNotificationFilterActivity : AppCompatActivity() {

    private class App(val pkg: String, val name: String, val icon: ImageBitmap, val isSystem: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(intent.getStringExtra(NotificationsPlugin.PREFERENCE_KEY), MODE_PRIVATE)
        val db = AppDatabase.getInstance(this)
        setContent { AppTheme { FilterScreen(db, prefs) } }
    }

    @Composable
    private fun FilterScreen(db: AppDatabase, prefs: android.content.SharedPreferences) {
        var apps by remember { mutableStateOf<List<App>?>(null) }
        var enabled by remember { mutableStateOf(emptyMap<String, Boolean>()) }
        var allEnabled by remember { mutableStateOf(db.allEnabled) }
        var screenOffOnly by remember { mutableStateOf(prefs.getBoolean(NotificationsPlugin.PREF_NOTIFICATION_SCREEN_OFF, false)) }
        var query by remember { mutableStateOf<String?>(null) } // null = search closed
        var privacyFor by remember { mutableStateOf<App?>(null) }
        var showSystem by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            val loaded = withContext(Dispatchers.IO) { loadApps(this@GeNotificationFilterActivity) }
            enabled = loaded.associate { it.pkg to db.isEnabled(it.pkg) }
            apps = loaded
        }

        val background = LocalAppSurfaces.current.background
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = background,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.pref_plugin_notifications)) },
                    navigationIcon = {
                        IconButton(onClick = ::finish) { Icon(painterResource(R.drawable.ms_arrow_back), stringResource(R.string.navigate_back)) }
                    },
                    actions = {
                        IconButton(onClick = { query = if (query == null) "" else null }) {
                            Icon(painterResource(if (query == null) R.drawable.ms_search else R.drawable.ms_close), stringResource(R.string.notif_filter_search))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            val list = apps
            val shown = list?.filter { (showSystem || !it.isSystem) && (query.isNullOrBlank() || it.name.contains(query!!.trim(), ignoreCase = true)) }
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                if (query != null) {
                    item {
                        OutlinedTextField(
                            value = query!!, onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.notif_filter_search)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ms_search), null) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                    }
                } else {
                    item {
                        ListGroup(listOf(
                            { s ->
                                SwitchRow(
                                    s, stringResource(R.string.notif_filter_screen_off),
                                    stringResource(if (screenOffOnly) R.string.notif_filter_screen_off_on else R.string.notif_filter_screen_off_summary),
                                    screenOffOnly,
                                ) {
                                    screenOffOnly = it
                                    prefs.edit { putBoolean(NotificationsPlugin.PREF_NOTIFICATION_SCREEN_OFF, it) }
                                }
                            },
                            { s ->
                                SwitchRow(s, stringResource(R.string.notif_filter_all_apps), null, allEnabled) { on ->
                                    allEnabled = on
                                    db.setAllEnabled(on)
                                    enabled = enabled.mapValues { on }
                                }
                            },
                            { s ->
                                SwitchRow(s, stringResource(R.string.notif_filter_show_system), null, showSystem) { showSystem = it }
                            },
                        ), Modifier.padding(top = 8.dp))
                        ge.connect.ui.components.ListGroup(listOf { s ->
                            ge.connect.ui.components.NavRow(
                                s, R.drawable.ms_notifications_active, stringResource(R.string.test_notification_action),
                                stringResource(R.string.test_notification_action_summary),
                            ) {
                                val posted = ge.connect.sync.TestNotification.post(this@GeNotificationFilterActivity)
                                android.widget.Toast.makeText(
                                    this@GeNotificationFilterActivity,
                                    if (posted) R.string.test_notification_posted else R.string.test_notification_blocked,
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }, Modifier.padding(top = 16.dp))
                    }
                    item {
                        SectionHeader(stringResource(R.string.notif_filter_apps))
                        Text(
                            stringResource(R.string.notif_filter_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        )
                    }
                }
                if (shown == null) {
                    item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
                } else {
                    itemsIndexed(shown, key = { _, app -> app.pkg }) { index, app ->
                        val on = enabled[app.pkg] == true
                        val blocked = listOfNotNull(
                            R.string.notif_filter_contents_hidden.takeIf { db.getPrivacy(app.pkg, AppDatabase.PrivacyOptions.BLOCK_CONTENTS) },
                            R.string.notif_filter_images_hidden.takeIf { db.getPrivacy(app.pkg, AppDatabase.PrivacyOptions.BLOCK_IMAGES) },
                        ).map { stringResource(it) }
                        Box(Modifier.padding(bottom = ListItemDefaults.SegmentedGap)) {
                            SegmentedListItem(
                                onClick = {
                                    db.setEnabled(app.pkg, !on)
                                    enabled = enabled + (app.pkg to !on)
                                },
                                onLongClick = { privacyFor = app },
                                shapes = ListItemDefaults.segmentedShapes(index, shown.size),
                                colors = ListItemDefaults.segmentedColors(containerColor = LocalAppSurfaces.current.card),
                                leadingContent = { Image(app.icon, null, Modifier.size(40.dp).clip(CircleShape)) },
                                supportingContent = if (blocked.isEmpty()) null else { { Text(blocked.joinToString(" · ")) } },
                                trailingContent = { Switch(checked = on, onCheckedChange = null) },
                            ) { Text(app.name) }
                        }
                    }
                }
            }
        }

        privacyFor?.let { app ->
            var contents by remember(app) { mutableStateOf(db.getPrivacy(app.pkg, AppDatabase.PrivacyOptions.BLOCK_CONTENTS)) }
            var images by remember(app) { mutableStateOf(db.getPrivacy(app.pkg, AppDatabase.PrivacyOptions.BLOCK_IMAGES)) }
            AlertDialog(
                onDismissRequest = { privacyFor = null },
                icon = { Image(app.icon, null, Modifier.size(40.dp).clip(CircleShape)) },
                title = { Text(app.name) },
                text = {
                    Column {
                        Text(stringResource(R.string.set_privacy_options), modifier = Modifier.padding(bottom = 8.dp))
                        CheckRow(stringResource(R.string.block_contents), contents) {
                            contents = it; db.setPrivacy(app.pkg, AppDatabase.PrivacyOptions.BLOCK_CONTENTS, it)
                        }
                        CheckRow(stringResource(R.string.block_images), images) {
                            images = it; db.setPrivacy(app.pkg, AppDatabase.PrivacyOptions.BLOCK_IMAGES, it)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { privacyFor = null }) { Text(stringResource(R.string.ok)) } },
            )
        }
    }

    @Composable
    private fun SwitchRow(
        shapes: androidx.compose.material3.ListItemShapes, title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit,
    ) {
        SegmentedListItem(
            onClick = { onChange(!checked) },
            shapes = shapes,
            colors = ListItemDefaults.segmentedColors(containerColor = LocalAppSurfaces.current.card),
            supportingContent = summary?.let { { Text(it) } },
            trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        ) { Text(title) }
    }

    @Composable
    private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Text(label, modifier = Modifier.padding(start = 12.dp))
        }
    }

    private companion object {
        /**
         * Apps that can post notifications (same rule as upstream), sorted by name. Apps without a
         * launcher icon count as system apps and are hidden unless asked for.
         */
        fun loadApps(context: Context): List<App> {
            val pm = context.packageManager
            val iconPx = (40 * context.resources.displayMetrics.density).toInt()
            val launchable = pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0,
            ).map { it.activityInfo.packageName }.toSet()
            return pm.getInstalledApplications(0)
                .filter { canPostNotifications(pm, it) }
                .parallelStream()
                .map { info ->
                    App(
                        pkg = info.packageName,
                        name = info.loadLabel(pm).toString(),
                        icon = info.loadIcon(pm).toBitmap(iconPx, iconPx).asImageBitmap(),
                        isSystem = info.packageName !in launchable,
                    )
                }
                .toList()
                .sortedBy { it.name.lowercase() }
        }

        fun canPostNotifications(pm: PackageManager, info: ApplicationInfo): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || info.targetSdkVersion < Build.VERSION_CODES.TIRAMISU) return true
            return try {
                pm.getPackageInfo(info.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
                    ?.contains(Manifest.permission.POST_NOTIFICATIONS) == true
            } catch (_: PackageManager.NameNotFoundException) {
                true
            }
        }
    }
}
