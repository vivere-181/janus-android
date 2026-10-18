/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ge.connect.devices

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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ge.connect.home.openDevice
import ge.connect.home.symbol
import ge.connect.ui.components.ListGroup
import ge.connect.ui.components.NavRow
import ge.connect.ui.components.SectionHeader
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.DeviceHelper
import org.kde.kdeconnect.helpers.TrustedNetworkHelper.Companion.isTrustedNetwork
import org.kde.kdeconnect.ui.CustomDevicesActivity
import ge.connect.settings.TrustedNetworksActivity
import org.kde.kdeconnect.ui.compose.screen.pairing.PairingUiState
import org.kde.kdeconnect.ui.compose.screen.pairing.PairingViewModel
import org.kde.kdeconnect_tp.R

/** Paired and nearby devices; pairing starts here. Reuses upstream's PairingViewModel. */
class DevicesActivity : AppCompatActivity() {
    private val viewModel by viewModels<PairingViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        BackgroundService.instance?.isConnectedToNonCellularNetwork?.observe(this) { updateStatus(it) }
        setContent {
            AppTheme {
                val state by viewModel.pairingUiState.collectAsStateWithLifecycle()
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    updateStatus(null)
                    BackgroundService.ForceRefreshConnections(this)
                }
                DevicesScreen(
                    state = state,
                    phoneName = DeviceHelper.getDeviceName(this),
                    onBack = ::finish,
                    onRefresh = viewModel::onRefresh,
                    onDevice = ::onDevice,
                    onAddByIp = { startActivity(Intent(this, CustomDevicesActivity::class.java)) },
                    onTrustedNetworks = { startActivity(Intent(this, TrustedNetworksActivity::class.java)) },
                    onWifiSettings = { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                    onAllowLocalNetwork = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                            permissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                        }
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        KdeConnect.getInstance().addDeviceListChangedCallback(TAG) { runOnUiThread { updateDevices() } }
        BackgroundService.ForceRefreshConnections(this) // re-discover while the list is open
        updateStatus(null)
        updateDevices()
    }

    override fun onStop() {
        KdeConnect.getInstance().removeDeviceListChangedCallback(TAG)
        super.onStop()
    }

    private fun updateDevices() {
        viewModel.updateDevices(KdeConnect.getInstance().devices.values.filter { it.isReachable || it.isPaired })
    }

    private fun updateStatus(onWifi: Boolean?) {
        viewModel.updateConnectivity(
            isWifiAvailable = onWifi ?: BackgroundService.instance?.isConnectedToNonCellularNetwork?.value ?: true,
            isTrustedNetwork = isTrustedNetwork(this),
        )
        viewModel.updatePermissions(
            hasNotificationsPermission = granted(Manifest.permission.POST_NOTIFICATIONS, Build.VERSION_CODES.TIRAMISU),
            hasLocalNetworkPermission = granted(Manifest.permission.ACCESS_LOCAL_NETWORK, Build.VERSION_CODES.CINNAMON_BUN),
        )
    }

    private fun granted(permission: String, since: Int) =
        Build.VERSION.SDK_INT < since || ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Opening a device shows it on Home; for a new one, pairing starts right away. */
    private fun onDevice(deviceId: String) {
        val device = viewModel.getDeviceById(deviceId) ?: return
        if (!device.isPaired && device.isReachable) device.requestPairing()
        openDevice(deviceId)
        finish()
    }

    private companion object {
        const val TAG = "DevicesActivity"
    }
}

@Composable
private fun DevicesScreen(
    state: PairingUiState,
    phoneName: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDevice: (String) -> Unit,
    onAddByIp: () -> Unit,
    onTrustedNetworks: () -> Unit,
    onWifiSettings: () -> Unit,
    onAllowLocalNetwork: () -> Unit,
) {
    val ownershipContext = androidx.compose.ui.platform.LocalContext.current
    val background = LocalAppSurfaces.current.background
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.devices_title)) },
                subtitle = { Text(stringResource(R.string.devices_this_phone, phoneName)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ms_arrow_back), stringResource(R.string.navigate_back)) }
                },
                actions = {
                    if (state.isRefreshing) {
                        LoadingIndicator(Modifier.padding(end = 8.dp).size(40.dp))
                    } else {
                        IconButton(onClick = onRefresh) { Icon(painterResource(R.drawable.ms_refresh), stringResource(R.string.devices_refresh)) }
                    }
                    // App settings live here (the ⚙ on Home's card is that device's settings).
                    val context = androidx.compose.ui.platform.LocalContext.current
                    IconButton(onClick = { context.startActivity(android.content.Intent(context, ge.connect.settings.SettingsActivity::class.java)) }) {
                        Icon(painterResource(R.drawable.ms_settings), stringResource(R.string.settings_title))
                    }
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
            // Why nothing may show up, most blocking first.
            val warning: Triple<Int, Int?, (() -> Unit)?>? = when {
                !state.hasLocalNetworkPermission -> Triple(R.string.devices_missing_permission, R.string.devices_allow, onAllowLocalNetwork)
                !state.isWifiAvailable -> Triple(R.string.devices_no_wifi, R.string.devices_wifi_settings, onWifiSettings)
                !state.isTrustedNetwork -> Triple(R.string.devices_untrusted_network, R.string.trusted_networks, onTrustedNetworks)
                else -> null
            }
            warning?.let { (text, action, onAction) ->
                item { WarningCard(stringResource(text), action?.let { stringResource(it) }, onAction) }
            }

            val paired = state.connected + state.remembered
            // My devices get everything; other people's devices only sharing (DeviceOwnership).
            val (mine, others) = paired.partition { DeviceOwnership.isMine(ownershipContext, it.id) }
            listOf(R.string.devices_section_mine to mine, R.string.devices_section_others to others).filter { it.second.isNotEmpty() }.forEach { (title, group) ->
                item {
                    SectionHeader(stringResource(title))
                    ListGroup(group.map { device ->
                        { shapes ->
                            NavRow(
                                shapes, device.symbolOrIcon(), device.name,
                                summary = stringResource(if (device.isReachable) R.string.home_connected else R.string.home_not_connected),
                                leading = ge.connect.home.DeviceArtOrNull(KdeConnect.getInstance().getDevice(device.id), 60.dp),
                                iconContainer = if (device.isReachable) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                iconColor = if (device.isReachable) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            ) { onDevice(device.id) }
                        }
                    })
                }
            }

            item {
                SectionHeader(stringResource(R.string.devices_section_available))
                if (state.available.isEmpty()) {
                    Surface(shape = RoundedCornerShape(24.dp), color = LocalAppSurfaces.current.card, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            LoadingIndicator(Modifier.size(40.dp))
                            Text(
                                stringResource(R.string.devices_searching),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                } else {
                    ListGroup(state.available.map { device ->
                        { shapes ->
                            NavRow(
                                shapes, device.symbolOrIcon(), device.name,
                                trailing = {
                                    FilledTonalButton(onClick = { onDevice(device.id) }) { Text(stringResource(R.string.pair_action_pair)) }
                                },
                            ) { onDevice(device.id) }
                        }
                    })
                }
            }

            item {
                SectionHeader(stringResource(R.string.devices_section_more))
                ListGroup(listOf(
                    { shapes ->
                        NavRow(shapes, R.drawable.ms_add, stringResource(R.string.devices_add_by_ip), stringResource(R.string.devices_add_by_ip_summary), onClick = onAddByIp)
                    },
                    { shapes ->
                        NavRow(shapes, R.drawable.ms_wifi, stringResource(R.string.trusted_networks), stringResource(R.string.trusted_networks_desc), onClick = onTrustedNetworks)
                    },
                ))
            }
        }
    }
}

@Composable
private fun WarningCard(text: String, action: String?, onAction: (() -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp)) {
            Row {
                Icon(painterResource(R.drawable.ms_warning_filled), null, modifier = Modifier.padding(end = 12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            }
            if (action != null && onAction != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onAction) { Text(action) }
                }
            }
        }
    }
}

/** Upstream's model carries a drawable for the device type; prefer our Material Symbol. */
private fun org.kde.kdeconnect.ui.compose.model.device.DeviceUiModel.symbolOrIcon(): Int =
    KdeConnect.getInstance().getDevice(id)?.symbol ?: icon
