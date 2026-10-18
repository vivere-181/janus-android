/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ge.connect.settings

import android.Manifest
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.helpers.TrustedNetworkHelper
import org.kde.kdeconnect_tp.R

/** Wi-Fi networks on which the app looks for devices. Replaces upstream's TrustedNetworksActivity. */
class TrustedNetworksActivity : AppCompatActivity() {
    private val helper by lazy { TrustedNetworkHelper(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                var allAllowed by remember { mutableStateOf(helper.allNetworksAllowed) }
                var networks by remember { mutableStateOf(helper.trustedNetworks) }
                var confirmRemove by remember { mutableStateOf<String?>(null) }
                val current = remember(allAllowed, networks) { helper.getCurrentSSID() }

                fun setAllAllowed(value: Boolean) {
                    helper.allNetworksAllowed = value
                    allAllowed = helper.allNetworksAllowed
                    BackgroundService.instance?.onNetworkChange(null) // start or stop discovery right away
                }
                fun setNetworks(value: List<String>) {
                    helper.trustedNetworks = value
                    networks = value
                    BackgroundService.instance?.onNetworkChange(null)
                }
                // Reading the Wi-Fi name needs location permission.
                val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) setAllAllowed(false)
                }

                val background = LocalAppSurfaces.current.background
                val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
                Scaffold(
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    containerColor = background,
                    topBar = {
                        LargeFlexibleTopAppBar(
                            title = { Text(stringResource(R.string.trusted_networks)) },
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
                            ListGroup(listOf { s ->
                                NavRow(
                                    s, null, stringResource(R.string.trusted_all_title), stringResource(R.string.trusted_all_summary),
                                    trailing = { Switch(checked = allAllowed, onCheckedChange = null) },
                                ) {
                                    when {
                                        !allAllowed -> setAllAllowed(true)
                                        helper.hasPermissions -> setAllAllowed(false)
                                        else -> locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                    }
                                }
                            }, Modifier.padding(top = 8.dp))
                        }
                        if (!allAllowed) {
                            item {
                                SectionHeader(stringResource(R.string.trusted_networks))
                                if (networks.isEmpty()) {
                                    Text(
                                        stringResource(R.string.empty_trusted_networks_list_text),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
                                    )
                                } else {
                                    ListGroup(networks.map { ssid ->
                                        { s ->
                                            NavRow(
                                                s, R.drawable.ms_wifi, ssid.removeSurrounding("\""),
                                                trailing = {
                                                    IconButton(onClick = { confirmRemove = ssid }) {
                                                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.trusted_remove))
                                                    }
                                                },
                                            ) { confirmRemove = ssid }
                                        }
                                    })
                                }
                                if (current != null && current !in networks) {
                                    FilledTonalButton(onClick = { setNetworks(networks + current) }, modifier = Modifier.padding(top = 16.dp)) {
                                        Icon(painterResource(R.drawable.ms_add), null, Modifier.padding(end = 8.dp))
                                        Text(stringResource(R.string.trusted_add_current, current.removeSurrounding("\"")))
                                    }
                                }
                            }
                        }
                    }
                }

                confirmRemove?.let { ssid ->
                    AlertDialog(
                        onDismissRequest = { confirmRemove = null },
                        title = { Text(stringResource(R.string.trusted_remove_title, ssid.removeSurrounding("\""))) },
                        confirmButton = {
                            TextButton(onClick = { setNetworks(networks - ssid); confirmRemove = null }) { Text(stringResource(R.string.trusted_remove)) }
                        },
                        dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.settings_cancel)) } },
                    )
                }
            }
        }
    }
}
