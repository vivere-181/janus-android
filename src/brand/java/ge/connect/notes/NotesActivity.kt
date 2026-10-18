/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ge.connect.notes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import org.kde.kdeconnect_tp.R

/** Notes (R29): pinned first, then by last edit; search; Recently Deleted. Synced with the owner's Macs. */
class NotesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AppTheme { NotesScreen() } }
    }

    @Composable
    private fun NotesScreen() {
        val all by NotesStore.all.collectAsState()
        var deletedView by rememberSaveable { mutableStateOf(false) }
        var searching by rememberSaveable { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }
        var menu by remember { mutableStateOf(false) }
        var confirmEmpty by remember { mutableStateOf(false) }
        BackHandler(deletedView || searching) {
            if (searching) { searching = false; query = "" } else deletedView = false
        }

        val notes = if (deletedView) NotesStore.recentlyDeleted(all) else NotesStore.visible(all).filter {
            query.isBlank() || it.text.contains(query.trim(), ignoreCase = true)
        }
        val background = LocalAppSurfaces.current.background
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = background,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = {
                        if (searching) {
                            TextField(
                                query, { query = it },
                                placeholder = { Text(stringResource(R.string.notes_search)) },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = background, unfocusedContainerColor = background,
                                    focusedIndicatorColor = background, unfocusedIndicatorColor = background,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(stringResource(if (deletedView) R.string.notes_recently_deleted else R.string.notes_title))
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            when {
                                searching -> { searching = false; query = "" }
                                deletedView -> deletedView = false
                                else -> finish()
                            }
                        }) { Icon(painterResource(R.drawable.ms_arrow_back), stringResource(R.string.navigate_back)) }
                    },
                    actions = {
                        if (!deletedView && !searching) {
                            IconButton(onClick = { searching = true }) { Icon(painterResource(R.drawable.ms_search), stringResource(R.string.notes_search)) }
                        }
                        if (deletedView && notes.isNotEmpty()) {
                            TextButton(onClick = { confirmEmpty = true }) { Text(stringResource(R.string.notes_empty_bin)) }
                        }
                        if (!deletedView) Box {
                            IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ms_more_vert), null) }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.notes_recently_deleted)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ms_delete), null) },
                                    onClick = { menu = false; deletedView = true },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                if (!deletedView) ExtendedFloatingActionButton(
                    onClick = { startActivity(NoteEditorActivity.newNoteIntent(this)) },
                    icon = { Icon(painterResource(R.drawable.ms_edit_note), null) },
                    text = { Text(stringResource(R.string.notes_new)) },
                )
            },
        ) { padding ->
            if (notes.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painterResource(if (deletedView) R.drawable.ms_delete else R.drawable.ms_sticky_note_2), null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(
                                when {
                                    deletedView -> R.string.notes_none_deleted
                                    query.isNotBlank() -> R.string.notes_no_results
                                    else -> R.string.notes_none
                                },
                            ),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                return@Scaffold
            }
            LazyVerticalStaggeredGrid(
                // Below the title bar rather than under it: overlapping, a TV remote's focus can't leave the bar.
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
                columns = StaggeredGridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = padding.calculateBottomPadding() + 96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalItemSpacing = 10.dp,
            ) {
                if (deletedView) item(span = StaggeredGridItemSpan.FullLine) {
                    Text(
                        stringResource(R.string.notes_deleted_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
                items(notes, key = { it.id }) { note ->
                    if (deletedView) DeletedCard(note) else NoteCard(note)
                }
            }
        }

        if (confirmEmpty) AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text(stringResource(R.string.notes_empty_bin_title)) },
            text = { Text(stringResource(R.string.notes_empty_bin_text)) },
            confirmButton = {
                TextButton(onClick = { confirmEmpty = false; notes.forEach { NotesStore.deleteNow(it.id) } }) { Text(stringResource(R.string.notes_delete_now)) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    @Composable
    private fun NoteCard(note: Note) {
        Card(
            onClick = { startActivity(NoteEditorActivity.intent(this, note.id)) },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = LocalAppSurfaces.current.card),
        ) { NotePreview(note, maxLines = 8) }
    }

    @Composable
    private fun DeletedCard(note: Note) {
        var menu by remember { mutableStateOf(false) }
        Box {
            Card(
                onClick = { menu = true },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = LocalAppSurfaces.current.card),
            ) {
                NotePreview(note, maxLines = 4)
                Text(
                    (note.deleted ?: 0).let { deleted ->
                        if (System.currentTimeMillis() - deleted < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.transfers_just_now)
                        else DateUtils.getRelativeTimeSpanString(deleted).toString()
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                )
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.notes_restore)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ms_restore_from_trash), null) },
                    onClick = { menu = false; NotesStore.restore(note.id) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.notes_delete_now)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ms_delete_forever), null) },
                    onClick = { menu = false; NotesStore.deleteNow(note.id) },
                )
            }
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, NotesActivity::class.java)
    }
}

/** Title and the first lines of a note, checklist items with a small box. */
@Composable
fun NotePreview(note: Note, maxLines: Int) {
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                note.title.ifEmpty { stringResource(R.string.notes_untitled) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (note.pinned) Icon(
                painterResource(R.drawable.ms_push_pin_filled), stringResource(R.string.notes_pinned),
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp).size(16.dp),
            )
        }
        NoteMarkdown.preview(note.text).take(maxLines).forEach { line ->
            Spacer(Modifier.height(4.dp))
            val indent = (line.level * 14).dp
            val color = MaterialTheme.colorScheme.onSurfaceVariant
            Row(Modifier.padding(start = indent), verticalAlignment = Alignment.CenterVertically) {
                when (line.kind) {
                    NoteMarkdown.Kind.CHECK -> {
                        Icon(
                            painterResource(if (line.checked) R.drawable.ms_check_box else R.drawable.ms_check_box_outline_blank), null,
                            tint = if (line.checked) MaterialTheme.colorScheme.primary else color, modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    NoteMarkdown.Kind.BULLET -> Text("•  ", style = MaterialTheme.typography.bodyMedium, color = color)
                    NoteMarkdown.Kind.NUMBER -> Text("${line.number}.  ", style = MaterialTheme.typography.bodyMedium, color = color)
                    NoteMarkdown.Kind.QUOTE -> Text("▍ ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    else -> {}
                }
                Text(
                    line.text,
                    style = when (line.kind) {
                        NoteMarkdown.Kind.TITLE, NoteMarkdown.Kind.HEADING, NoteMarkdown.Kind.SUBHEADING -> MaterialTheme.typography.titleSmall
                        NoteMarkdown.Kind.CODE -> MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        else -> MaterialTheme.typography.bodyMedium
                    },
                    color = if (line.kind == NoteMarkdown.Kind.TITLE || line.kind == NoteMarkdown.Kind.HEADING || line.kind == NoteMarkdown.Kind.SUBHEADING) MaterialTheme.colorScheme.onSurface else color,
                    textDecoration = if (line.kind == NoteMarkdown.Kind.CHECK && line.checked) TextDecoration.LineThrough else null,
                    maxLines = if (line.kind == NoteMarkdown.Kind.BODY) 2 else 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
