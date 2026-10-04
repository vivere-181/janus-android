/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ge.connect.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ge.connect.ui.theme.LocalAppSurfaces
import org.kde.kdeconnect_tp.R

/**
 * "Is this device yours?" — after pairing (current = null) and from Preferences → Belongs to. Two big choices;
 * tapping one answers right away.
 */
@Composable
fun OwnershipSheet(deviceName: String, deviceIcon: Int, current: Boolean?, onDismiss: () -> Unit, onChoose: (mine: Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LocalAppSurfaces.current.background,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(88.dp).background(colors.primaryContainer, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                Icon(painterResource(deviceIcon), null, tint = colors.onPrimaryContainer, modifier = Modifier.size(40.dp))
            }
            Text(
                stringResource(R.string.ownership_title, deviceName),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                stringResource(R.string.ownership_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Choice(R.drawable.ms_devices, R.string.ownership_mine, R.string.ownership_mine_summary, selected = current == true) { onChoose(true) }
                Choice(R.drawable.ms_group, R.string.ownership_other, R.string.ownership_other_summary, selected = current == false) { onChoose(false) }
            }
        }
    }
}

@Composable
private fun Choice(icon: Int, title: Int, summary: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = if (selected) colors.secondaryContainer else LocalAppSurfaces.current.card,
        modifier = Modifier.fillMaxWidth().then(
            if (selected) Modifier.border(2.dp, colors.primary, RoundedCornerShape(24.dp)) else Modifier,
        ),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(colors.primary.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, tint = colors.primary, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(summary), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            if (selected) Icon(painterResource(R.drawable.ms_check), null, tint = colors.primary)
        }
    }
}
