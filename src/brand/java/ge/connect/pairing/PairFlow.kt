/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ge.connect.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ge.connect.ui.theme.LocalAppSurfaces
import org.kde.kdeconnect_tp.R

private sealed interface Stage {
    data object Scanning : Stage
    data class Confirm(val code: QrPairing.Code) : Stage
    data class Pairing(val code: QrPairing.Code) : Stage
    data class Failed(val message: String) : Stage
}

/**
 * Connecting to a Mac from its QR code (R41): the camera, then pairing. A code from a link (opened from the camera app
 * or elsewhere, [link]) is confirmed first, since anything can open a link; a scanned one pairs at once.
 */
@Composable
fun PairFlow(link: QrPairing.Code?, onPaired: (deviceId: String, name: String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var stage by remember { mutableStateOf<Stage>(if (link != null) Stage.Confirm(link) else Stage.Scanning) }

    LaunchedEffect(stage) {
        val pairing = stage as? Stage.Pairing ?: return@LaunchedEffect
        when (val result = QrPairing.pair(context, pairing.code)) {
            is QrPairing.Result.Paired -> onPaired(result.deviceId, result.name)
            is QrPairing.Result.Failed -> stage = Stage.Failed(result.message)
        }
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (val s = stage) {
            Stage.Scanning -> QrScanner(onCode = { stage = Stage.Pairing(it) }, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            is Stage.Confirm -> Surface(shape = RoundedCornerShape(28.dp), color = LocalAppSurfaces.current.card, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ms_laptop_mac), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text(stringResource(R.string.qr_pair_confirm_title, s.code.name), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.qr_pair_confirm_text), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { stage = Stage.Scanning }) { Text(stringResource(R.string.qr_pair_cancel)) }
                        Button(onClick = { stage = Stage.Pairing(s.code) }) { Text(stringResource(R.string.qr_pair_confirm)) }
                    }
                }
            }
            is Stage.Pairing -> Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LoadingIndicator(Modifier.size(64.dp))
                    Text(stringResource(R.string.qr_pairing, s.code.name), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }
            }
            is Stage.Failed -> Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(painterResource(R.drawable.ms_error), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                    Text(s.message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Button(onClick = { stage = Stage.Scanning }) { Text(stringResource(R.string.qr_pair_try_again)) }
                }
            }
        }
    }
}
