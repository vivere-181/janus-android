/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ge.connect.notes

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import kotlinx.coroutines.launch
import org.kde.kdeconnect_tp.R

/** Picks the note a note widget shows (when it's placed, and from Reconfigure). */
class NoteWidgetConfigureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        setContent { AppTheme { Picker(appWidgetId) } }
    }

    @Composable
    private fun Picker(appWidgetId: Int) {
        val all by NotesStore.all.collectAsState()
        val notes = NotesStore.visible(all)
        val background = LocalAppSurfaces.current.background
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = background,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.note_widget_pick)) },
                    navigationIcon = {
                        IconButton(onClick = ::finish) { Icon(painterResource(R.drawable.ms_arrow_back), stringResource(R.string.navigate_back)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            if (notes.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.notes_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Scaffold
            }
            LazyVerticalStaggeredGrid(
                // Below the title bar rather than under it: overlapping, a TV remote's focus can't leave the bar.
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
                columns = StaggeredGridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalItemSpacing = 10.dp,
            ) {
                items(notes, key = { it.id }) { note ->
                    Card(
                        onClick = { choose(appWidgetId, note.id) },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = LocalAppSurfaces.current.card),
                    ) { NotePreview(note, maxLines = 6) }
                }
            }
        }
    }

    private fun choose(appWidgetId: Int, noteId: String) {
        NoteWidgets.set(this, appWidgetId, noteId)
        lifecycleScope.launch {
            runCatching { NoteWidget().updateAll(this@NoteWidgetConfigureActivity) }
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }

    companion object {
        fun intent(context: Context, appWidgetId: Int) = Intent(context, NoteWidgetConfigureActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
