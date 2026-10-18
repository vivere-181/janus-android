/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package ge.connect.transfers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ge.connect.ui.components.EmptyState
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import org.kde.kdeconnect_tp.R

/** Everything sent and received, newest first, as big preview cards (like Blip). */
class TransfersActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AppTheme { TransfersScreen(onBack = ::finish) } }
    }

    companion object {
        fun intent(context: Context) = Intent(context, TransfersActivity::class.java)
    }
}

@Composable
private fun TransfersScreen(onBack: () -> Unit) {
    val items by Transfers.items.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<Transfers.Direction?>(null) }
    val shown = items.filter { filter == null || it.direction == filter }
    val background = LocalAppSurfaces.current.background
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.transfers_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ms_arrow_back), stringResource(R.string.navigate_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyState(
                R.drawable.ms_sync_alt, stringResource(R.string.transfers_empty_title), stringResource(R.string.transfers_empty_text),
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            // Below the title bar rather than under it: overlapping, a TV remote's focus can't leave the bar.
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = 24.dp, end = 24.dp,
                top = 0.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                val options = listOf(null to R.string.transfers_all, Transfers.Direction.IN to R.string.transfers_received, Transfers.Direction.OUT to R.string.transfers_sent)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    options.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = filter == value,
                            onClick = { filter = value },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) { Text(stringResource(label)) }
                    }
                }
            }
            items(shown, key = { it.id }) { item ->
                FeedCard(item, Modifier.animateItem().widthIn(max = 420.dp).fillMaxWidth().padding(bottom = 28.dp))
            }
        }
    }
}

@Composable
private fun FeedCard(item: Transfers.Item, modifier: Modifier) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        TransferCard(
            item,
            Modifier
                .clip(RoundedCornerShape(28.dp))
                .combinedClickable(
                    onClick = { if (!item.active) openTransfer(context, item) },
                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                ),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.transfers_share)) },
                leadingIcon = { Icon(painterResource(R.drawable.ms_share), null) },
                onClick = { menu = false; shareTransfer(context, item) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.transfers_remove)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) },
                onClick = { menu = false; Transfers.remove(item.id) },
            )
        }
    }
}
