/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ge.connect.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ge.connect.ui.components.ListGroup
import ge.connect.ui.components.NavRow
import ge.connect.ui.components.SectionHeader
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.ui.MainActivity
import org.kde.kdeconnect_tp.R

/** Per-computer preferences: ping and other actions, features, connection, appearance, unpair. */
class DevicePreferencesActivity : AppCompatActivity() {
    private var ui by mutableStateOf<DeviceUi?>(null)
    private lateinit var device: Device

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        device = intent.getStringExtra(MainActivity.EXTRA_DEVICE_ID)?.let { KdeConnect.getInstance().getDevice(it) }
            ?: run { finish(); return }
        setContent { AppTheme { ui?.let { Screen(it) } } }
    }

    override fun onResume() {
        super.onResume()
        ui = device.toHomeUi(this)
    }

    @Composable
    private fun Screen(ui: DeviceUi) {
        var confirmUnpair by remember { mutableStateOf(false) }
        var pickAppearance by remember { mutableStateOf(false) }
        var pickOwner by remember { mutableStateOf(false) }
        val background = LocalAppSurfaces.current.background
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = background,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.device_settings_title)) },
                    subtitle = { Text(ui.name) },
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
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                item {
                    SectionHeader(stringResource(R.string.device_settings_section_device), Modifier.padding(top = 0.dp))
                    ListGroup(listOf(
                        { s ->
                            NavRow(
                                s, R.drawable.ms_person, stringResource(R.string.ownership_row),
                                stringResource(if (ui.mine) R.string.ownership_row_mine else R.string.ownership_row_other),
                            ) { pickOwner = true }
                        },
                        { s ->
                            NavRow(s, R.drawable.ms_palette, stringResource(R.string.appearance_title), stringResource(ui.appearance.label)) {
                                pickAppearance = true
                            }
                        },
                        { s ->
                            NavRow(s, R.drawable.ms_wifi, stringResource(R.string.connection_title), stringResource(R.string.connection_summary)) {
                                startActivity(ge.connect.connection.ConnectionActivity.intent(this@DevicePreferencesActivity, ui.id))
                            }
                        },
                    ))
                    SectionHeader(stringResource(R.string.device_settings_section_features))
                    ListGroup(listOf { s ->
                        NavRow(s, R.drawable.ms_tune, stringResource(R.string.home_features), stringResource(R.string.home_features_summary)) {
                            startActivity(ge.connect.features.FeaturesActivity.intent(this@DevicePreferencesActivity, ui.id))
                        }
                    })
                    Spacer(Modifier.padding(top = 16.dp))
                    ListGroup(listOf { s ->
                        NavRow(
                            s, R.drawable.ms_link_off, stringResource(R.string.home_unpair), stringResource(R.string.home_unpair_summary),
                            iconContainer = MaterialTheme.colorScheme.errorContainer,
                            iconColor = MaterialTheme.colorScheme.onErrorContainer,
                        ) { confirmUnpair = true }
                    })
                }
            }
        }

        if (confirmUnpair) {
            AlertDialog(
                onDismissRequest = { confirmUnpair = false },
                icon = { Icon(painterResource(R.drawable.ms_link_off), null) },
                title = { Text(stringResource(R.string.home_unpair_confirm_title, ui.name)) },
                text = { Text(stringResource(R.string.home_unpair_confirm_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmUnpair = false
                        device.unpair()
                        ge.connect.devices.DeviceOwnership.forget(this@DevicePreferencesActivity, device.deviceId)
                        SelectedDevice.set(this@DevicePreferencesActivity, null)
                        finish() // Home picks the next computer, or offers pairing
                    }) { Text(stringResource(R.string.home_unpair)) }
                },
                dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.settings_cancel)) } },
            )
        }
        if (pickOwner) {
            ge.connect.devices.OwnershipSheet(ui.name, ui.icon, current = ui.mine, onDismiss = { pickOwner = false }) { mine ->
                pickOwner = false
                ge.connect.devices.DeviceOwnership.set(this@DevicePreferencesActivity, ui.id, mine)
                this@DevicePreferencesActivity.ui = device.toHomeUi(this@DevicePreferencesActivity)
            }
        }
        if (pickAppearance) {
            AlertDialog(
                onDismissRequest = { pickAppearance = false },
                title = { Text(stringResource(R.string.appearance_title)) },
                text = {
                    Column {
                        DeviceAppearance.entries.forEach { option ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        pickAppearance = false
                                        DeviceAppearance.set(this@DevicePreferencesActivity, ui.id, option)
                                        this@DevicePreferencesActivity.ui = device.toHomeUi(this@DevicePreferencesActivity)
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = option == ui.appearance, onClick = null)
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(option.label), Modifier.weight(1f))
                                option.art?.let { Image(painterResource(it), null, Modifier.size(60.dp, 40.dp)) }
                                    ?: Icon(painterResource(R.drawable.ms_laptop_mac), null, Modifier.size(60.dp, 28.dp))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { pickAppearance = false }) { Text(stringResource(R.string.settings_cancel)) } },
            )
        }
    }

    companion object {
        fun intent(context: Context, deviceId: String): Intent =
            Intent(context, DevicePreferencesActivity::class.java).putExtra(MainActivity.EXTRA_DEVICE_ID, deviceId)
    }
}
