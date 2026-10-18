/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ge.connect.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import ge.connect.setup.SetupActivity
import ge.connect.ui.components.ListGroup
import ge.connect.ui.components.NavRow
import ge.connect.ui.components.SectionHeader
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.helpers.DeviceHelper
import org.kde.kdeconnect.ui.CustomDevicesActivity
import org.kde.kdeconnect.ui.SettingsFragment
import org.kde.kdeconnect.ui.ThemeUtil
import org.kde.kdeconnect.ui.about.LicensesActivity
import org.kde.kdeconnect_tp.BuildConfig
import org.kde.kdeconnect_tp.R

/** App settings. Same preference keys as upstream's SettingsFragment, which this replaces. */
class SettingsActivity : AppCompatActivity() {
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private var resumeCount by mutableIntStateOf(0) // re-read summaries after returning from other screens

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AppTheme { SettingsScreen() } }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }

    @Composable
    private fun SettingsScreen() {
        @Suppress("UNUSED_EXPRESSION") resumeCount // read so summaries refresh on resume
        val context = LocalContext.current
        var editName by remember { mutableStateOf(false) }
        var pickTheme by remember { mutableStateOf(false) }
        var addTiles by remember { mutableStateOf(false) }
        var dndSync by remember { mutableStateOf(ge.connect.sync.DndSync.isEnabled(context)) }
        var screenshots by remember { mutableStateOf(ge.connect.sync.ScreenshotSync.isEnabled(context) && ge.connect.sync.ScreenshotSync.hasPermission(context)) }
        val photoPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) { ge.connect.sync.ScreenshotSync.setEnabled(context, true); screenshots = true }
        }
        var keepWirelessDebugging by remember { mutableStateOf(ge.connect.sync.Mirroring.keepOn(context)) }
        val cloudPeers by ge.connect.cloud.JanusCloud.peers.collectAsState()
        val cloudLink by ge.connect.cloud.JanusCloud.link.collectAsState()
        var bluetooth by remember { mutableStateOf(prefs.getBoolean(SettingsFragment.KEY_BLUETOOTH_ENABLED, false)) }
        var phoneName by remember { mutableStateOf(DeviceHelper.getDeviceName(context)) }
        var theme by remember { mutableStateOf(prefs.getString(SettingsFragment.KEY_APP_THEME, ThemeUtil.DEFAULT_MODE)!!) }

        val themeLabels = stringArrayResource(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) R.array.theme_list_v28 else R.array.theme_list)
        val themeValues = stringArrayResource(R.array.theme_list_values)

        fun setBluetooth(on: Boolean) {
            bluetooth = on
            prefs.edit { putBoolean(SettingsFragment.KEY_BLUETOOTH_ENABLED, on) }
        }
        val bluetoothPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) setBluetooth(true)
        }
        val trustedNetworks = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            BackgroundService.instance?.onNetworkChange(null)
        }
        val exportLogs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write("${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}\n".toByteArray())
                    out.write("Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})\n".toByteArray())
                    Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream.use { it.copyTo(out) }
                }
            }
        }

        val background = LocalAppSurfaces.current.background
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = background,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
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
                // This phone
                item {
                    SectionHeader(stringResource(R.string.settings_section_phone), Modifier.padding(top = 8.dp))
                    ListGroup(listOfNotNull(
                        { s -> NavRow(s, R.drawable.ms_badge, stringResource(R.string.settings_phone_name), phoneName) { editName = true } },
                        { s -> NavRow(s, R.drawable.ms_palette, stringResource(R.string.settings_theme), themeLabels[themeValues.indexOf(theme).coerceAtLeast(0)]) { pickTheme = true } },
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { s ->
                            val shown = ge.connect.ConnectionNotification.isShown(context)
                            NavRow(
                                s, R.drawable.ms_notifications, stringResource(R.string.settings_notification),
                                stringResource(if (shown) R.string.settings_notification_on else R.string.settings_notification_off),
                                trailing = { Switch(checked = shown, onCheckedChange = null) },
                            ) {
                                startActivity(ge.connect.ConnectionNotification.settingsIntent(context))
                            }
                        } else null,
                    ))
                }
                // Sync with my devices (every device marked "Mine")
                item {
                    SectionHeader(stringResource(R.string.settings_section_sync))
                    ListGroup(listOf(
                        { s -> NavRow(s, R.drawable.ms_notifications_active, stringResource(R.string.settings_sync_notifications), stringResource(R.string.settings_sync_notifications_summary)) {
                            startActivity(
                                Intent(context, org.kde.kdeconnect.plugins.notifications.GeNotificationFilterActivity::class.java)
                                    .putExtra(org.kde.kdeconnect.plugins.notifications.NotificationsPlugin.PREFERENCE_KEY, "NotificationsPlugin_preferences"),
                            )
                        } },
                        { s -> NavRow(
                            s, R.drawable.ms_do_not_disturb_on, stringResource(R.string.settings_dnd_sync),
                            stringResource(
                                if (!dndSync) R.string.settings_dnd_sync_summary
                                else if (ge.connect.sync.DndSync.hasAccess(context)) R.string.settings_dnd_sync_summary
                                else R.string.settings_dnd_sync_no_access
                            ),
                            trailing = { Switch(checked = dndSync, onCheckedChange = null) },
                        ) {
                            dndSync = !dndSync
                            ge.connect.sync.DndSync.setEnabled(context, dndSync)
                        } },
                        { s -> NavRow(
                            s, R.drawable.ms_screenshot_monitor, stringResource(R.string.settings_screenshots), stringResource(R.string.settings_screenshots_summary),
                            trailing = { Switch(checked = screenshots, onCheckedChange = null) },
                        ) {
                            when {
                                screenshots -> { ge.connect.sync.ScreenshotSync.setEnabled(context, false); screenshots = false }
                                ge.connect.sync.ScreenshotSync.hasPermission(context) -> { ge.connect.sync.ScreenshotSync.setEnabled(context, true); screenshots = true }
                                else -> photoPermission.launch(ge.connect.sync.ScreenshotSync.permission)
                            }
                        } },
                        { s -> NavRow(s, R.drawable.ms_sensors, stringResource(R.string.live_title), stringResource(R.string.live_summary)) {
                            startActivity(LiveActivitiesActivity.intent(context))
                        } },
                        { s -> NavRow(
                            s, R.drawable.ms_cast, stringResource(R.string.mirroring_keep_on), stringResource(R.string.mirroring_keep_on_summary),
                            trailing = { Switch(checked = keepWirelessDebugging, onCheckedChange = null) },
                        ) {
                            keepWirelessDebugging = !keepWirelessDebugging
                            ge.connect.sync.Mirroring.setKeepOn(context, keepWirelessDebugging)
                        } },
                    ))
                }
                // Shortcuts
                item {
                    SectionHeader(stringResource(R.string.settings_section_shortcuts))
                    ListGroup(listOfNotNull(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { s ->
                            NavRow(s, R.drawable.ms_tune, stringResource(R.string.tiles_title), stringResource(R.string.tiles_summary)) { addTiles = true }
                        } else null,
                        { s -> NavRow(s, R.drawable.ms_widgets_filled, stringResource(R.string.widget_add), stringResource(R.string.widget_add_summary)) {
                            ge.connect.widget.MacWidgetUpdates.requestPin(context)
                        } },
                    ))
                }
                // Permissions
                item {
                    SectionHeader(stringResource(R.string.settings_section_permissions))
                    ListGroup(listOf { s ->
                        NavRow(s, R.drawable.ms_checklist, stringResource(R.string.setup_guide_pref_title), stringResource(R.string.setup_guide_pref_summary)) {
                            startActivity(Intent(context, SetupActivity::class.java))
                        }
                    })
                }
                // Network (Add by IP lives in Devices, where you look for devices)
                item {
                    SectionHeader(stringResource(R.string.settings_section_network))
                    ListGroup(listOf(
                        { s -> NavRow(
                            s, R.drawable.ms_cloud, stringResource(R.string.cloud_title),
                            stringResource(CloudActivity.statusTitle(cloudPeers, cloudLink)),
                        ) { startActivity(CloudActivity.intent(context)) } },
                        { s -> NavRow(s, R.drawable.ms_wifi, stringResource(R.string.trusted_networks), stringResource(R.string.trusted_networks_desc)) {
                            trustedNetworks.launch(Intent(context, TrustedNetworksActivity::class.java))
                        } },
                        { s -> NavRow(
                            s, R.drawable.ms_bluetooth, stringResource(R.string.enable_bluetooth), stringResource(R.string.settings_bluetooth_summary),
                            trailing = { Switch(checked = bluetooth, onCheckedChange = null) },
                        ) {
                            val missing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                                    .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                            } else emptyList()
                            when {
                                bluetooth -> setBluetooth(false)
                                missing.isEmpty() -> setBluetooth(true)
                                else -> bluetoothPermissions.launch(missing.toTypedArray())
                            }
                        } },
                    ))
                }
                item {
                    SectionHeader(stringResource(R.string.settings_section_about))
                    ListGroup(listOf(
                        { s -> NavRow(s, R.drawable.ms_info, stringResource(R.string.settings_about_app, stringResource(R.string.app_name)), BuildConfig.VERSION_NAME) {
                            startActivity(Intent(context, AboutActivity::class.java))
                        } },
                        { s -> NavRow(s, R.drawable.ms_description, stringResource(R.string.settings_licenses)) {
                            startActivity(Intent(context, LicensesActivity::class.java))
                        } },
                        { s -> NavRow(s, R.drawable.ms_help, stringResource(R.string.settings_export_logs), stringResource(R.string.settings_logs_summary)) {
                            exportLogs.launch("log.txt")
                        } },
                    ))
                }
            }
        }

        if (editName) {
            PhoneNameDialog(phoneName, onDismiss = { editName = false }) { name ->
                prefs.edit { putString(DeviceHelper.KEY_DEVICE_NAME_PREFERENCE, name) }
                phoneName = name
                editName = false
                BackgroundService.ForceRefreshConnections(context) // re-send our identity with the new name
            }
        }
        if (addTiles && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ge.connect.tiles.AddTilesDialog(onDismiss = { addTiles = false })
        }
        if (pickTheme) {
            ChoiceDialog(
                title = stringResource(R.string.settings_theme),
                labels = themeLabels.toList(),
                selected = themeValues.indexOf(theme),
                onDismiss = { pickTheme = false },
            ) { index ->
                pickTheme = false
                theme = themeValues[index]
                prefs.edit { putString(SettingsFragment.KEY_APP_THEME, theme) }
                ThemeUtil.applyTheme(theme)
            }
        }
    }
}

@Composable
private fun PhoneNameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    val cleaned = DeviceHelper.filterInvalidCharactersFromDeviceNameAndLimitLength(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_phone_name)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = DeviceHelper.filterInvalidCharactersFromDeviceName(it).take(DeviceHelper.MAX_DEVICE_NAME_LENGTH) },
                singleLine = true,
                isError = cleaned.isBlank(),
                supportingText = if (cleaned.isBlank()) {
                    { Text(stringResource(R.string.invalid_device_name)) }
                } else null,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(cleaned) }, enabled = cleaned.isNotBlank()) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
private fun ChoiceDialog(title: String, labels: List<String>, selected: Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                labels.forEachIndexed { index, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = index == selected, role = Role.RadioButton) { onSelect(index) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = index == selected, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
