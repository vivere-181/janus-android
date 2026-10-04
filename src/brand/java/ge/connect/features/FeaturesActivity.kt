/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ge.connect.features

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ge.connect.ui.components.ListGroup
import ge.connect.ui.components.NavRow
import ge.connect.ui.components.SectionHeader
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.plugins.PluginFactory
import org.kde.kdeconnect.ui.MainActivity
import org.kde.kdeconnect.ui.PluginSettingsActivity
import org.kde.kdeconnect_tp.R

/** Which features a device may use, grouped by what they do. Replaces upstream's plugin list. */
class FeaturesActivity : AppCompatActivity() {
    private var version by mutableIntStateOf(0) // bumped to re-read enabled states

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val deviceId = intent.getStringExtra(MainActivity.EXTRA_DEVICE_ID)
        val device = deviceId?.let { KdeConnect.getInstance().getDevice(it) } ?: run { finish(); return }

        setContent {
            AppTheme {
                @Suppress("UNUSED_EXPRESSION") version
                val background = LocalAppSurfaces.current.background
                val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
                val groups = CATEGORIES.map { category ->
                    category to device.supportedPlugins.filter { categoryOf(it) == category }
                        .sortedBy { PluginFactory.getPluginInfo(it).displayName }
                }.filter { it.second.isNotEmpty() }

                Scaffold(
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    containerColor = background,
                    topBar = {
                        LargeFlexibleTopAppBar(
                            title = { Text(stringResource(R.string.home_features)) },
                            subtitle = { Text(device.name) },
                            navigationIcon = {
                                IconButton(onClick = ::finish) {
                                    Icon(painterResource(R.drawable.ms_arrow_back), stringResource(R.string.navigate_back))
                                }
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
                        groups.forEach { (category, keys) ->
                            item(category.title) {
                                SectionHeader(stringResource(category.title))
                                ListGroup(keys.map { key ->
                                    { shapes ->
                                        val info = PluginFactory.getPluginInfo(key)
                                        val enabled = device.isPluginEnabled(key)
                                        // Options only make sense for a running plugin on a connected device.
                                        val canOpenSettings = info.hasSettings && enabled && device.isReachable && device.isPaired
                                        val toggle = {
                                            device.setPluginEnabled(key, !enabled)
                                            version++
                                        }
                                        NavRow(
                                            shapes, symbolFor(key), info.displayName, info.description,
                                            iconContainer = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                            iconColor = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            trailing = { Switch(checked = enabled, onCheckedChange = { toggle() }) },
                                        ) {
                                            if (canOpenSettings) openSettings(this@FeaturesActivity, device.deviceId, key) else toggle()
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

    override fun onResume() {
        super.onResume()
        version++
    }

    private fun openSettings(context: Context, deviceId: String, pluginKey: String) =
        startActivity(PluginSettingsActivity.createIntent(context, deviceId, pluginKey))

    private enum class Category(@StringRes val title: Int) {
        Sync(R.string.features_section_sync),
        ControlComputer(R.string.features_section_control_computer),
        ControlPhone(R.string.features_section_control_phone),
        Other(R.string.features_section_other),
    }

    companion object {
        private val CATEGORIES = Category.entries

        fun intent(context: Context, deviceId: String): Intent =
            Intent(context, FeaturesActivity::class.java).putExtra(MainActivity.EXTRA_DEVICE_ID, deviceId)

        private fun categoryOf(key: String): Category = when (key) {
            "NotificationsPlugin", "ReceiveNotificationsPlugin", "SMSPlugin", "TelephonyPlugin", "ContactsPlugin",
            "ClipboardPlugin", "SharePlugin", "BatteryPlugin", "ConnectivityReportPlugin", "PingPlugin" -> Category.Sync
            "MousePadPlugin", "PresenterPlugin", "RunCommandPlugin", "MprisPlugin", "SystemVolumePlugin",
            "DigitizerPlugin", "FindRemoteDevicePlugin" -> Category.ControlComputer
            "RemoteKeyboardPlugin", "RemoteKeyboardIMEPlugin", "MouseReceiverPlugin", "InputDevicesReceiverPlugin",
            "MprisReceiverPlugin", "FindMyPhonePlugin", "SftpPlugin" -> Category.ControlPhone
            else -> Category.Other
        }

        @DrawableRes
        private fun symbolFor(key: String): Int = when (key) {
            "NotificationsPlugin" -> R.drawable.ms_notifications
            "ReceiveNotificationsPlugin" -> R.drawable.ms_notifications_active
            "SMSPlugin" -> R.drawable.ms_sms
            "TelephonyPlugin" -> R.drawable.ms_call
            "ContactsPlugin" -> R.drawable.ms_contacts
            "ClipboardPlugin" -> R.drawable.ms_content_paste
            "SharePlugin" -> R.drawable.ms_share
            "BatteryPlugin" -> R.drawable.ms_battery_full
            "ConnectivityReportPlugin" -> R.drawable.ms_signal_cellular_alt
            "PingPlugin" -> R.drawable.ms_sync_alt
            "MousePadPlugin" -> R.drawable.ms_trackpad_input
            "PresenterPlugin" -> R.drawable.ms_co_present
            "RunCommandPlugin" -> R.drawable.ms_terminal
            "MprisPlugin" -> R.drawable.ms_play_circle
            "SystemVolumePlugin" -> R.drawable.ms_volume_up
            "DigitizerPlugin" -> R.drawable.ms_draw
            "FindRemoteDevicePlugin" -> R.drawable.ms_ring_volume
            "RemoteKeyboardPlugin", "RemoteKeyboardIMEPlugin" -> R.drawable.ms_keyboard
            "MouseReceiverPlugin" -> R.drawable.ms_mouse
            "InputDevicesReceiverPlugin" -> R.drawable.ms_stadia_controller
            "MprisReceiverPlugin" -> R.drawable.ms_music_note
            "FindMyPhonePlugin" -> R.drawable.ms_location_searching
            "SftpPlugin" -> R.drawable.ms_folder
            else -> R.drawable.ms_tune
        }
    }
}
