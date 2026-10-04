/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.tiles

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.kde.kdeconnect.plugins.clipboard.ClipboardTileService
import org.kde.kdeconnect_tp.R

private class TileInfo(val service: Class<*>, @StringRes val label: Int, @DrawableRes val icon: Int)

private val TILES = listOf(
    TileInfo(StatusTileService::class.java, R.string.app_name, R.drawable.ms_laptop_mac),
    TileInfo(ClipboardTileService::class.java, R.string.tile_clipboard, R.drawable.ms_content_paste),
    TileInfo(TouchpadTileService::class.java, R.string.tile_touchpad, R.drawable.ms_trackpad_input),
    TileInfo(SendFilesTileService::class.java, R.string.tile_send_files, R.drawable.ms_upload),
    TileInfo(LockMacTileService::class.java, R.string.tile_lock_mac, R.drawable.ms_lock_filled),
    TileInfo(ge.connect.notes.NewNoteTileService::class.java, R.string.notes_new, R.drawable.ms_edit_note),
)

/** Lists our Quick Settings tiles; "Add" asks the system to place one (Android 13+). */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun AddTilesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var added by remember { mutableStateOf(emptySet<Class<*>>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tiles_title)) },
        text = {
            Column {
                Text(stringResource(R.string.tiles_text), modifier = Modifier.padding(bottom = 8.dp))
                TILES.forEach { tile ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(tile.icon), null, Modifier.padding(end = 16.dp))
                        Text(stringResource(tile.label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        FilledTonalButton(onClick = { requestAdd(context, tile) { added = added + tile.service } }, enabled = tile.service !in added) {
                            Text(stringResource(if (tile.service in added) R.string.tiles_added else R.string.tiles_add))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun requestAdd(context: Context, tile: TileInfo, onAdded: () -> Unit) {
    context.getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(context, tile.service),
        context.getString(tile.label),
        Icon.createWithResource(context, tile.icon),
        context.mainExecutor,
    ) { result ->
        if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
            result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
        ) onAdded()
    }
}
