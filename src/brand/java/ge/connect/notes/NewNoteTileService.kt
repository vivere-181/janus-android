/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.notes

import android.app.PendingIntent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat

/** Quick Settings tile: a new note (R29). */
class NewNoteTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
    }

    override fun onClick() {
        TileServiceCompat.startActivityAndCollapse(
            this, PendingIntentActivityWrapper(this, 0, NoteEditorActivity.newNoteExternalIntent(this), PendingIntent.FLAG_UPDATE_CURRENT, false),
        )
    }
}
