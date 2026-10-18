/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.notes

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import ge.connect.ui.theme.JanusGlanceColors
import org.kde.kdeconnect_tp.R

/**
 * One chosen note on the home screen, like Samsung Notes' note widget: the whole note (scrollable), checklist items
 * tickable, tap opens it. The note is picked when the widget is placed (long-press → Reconfigure to change it).
 */
class NoteWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            val all by NotesStore.all.collectAsState()
            // Re-read when a widget is pointed at another note (Reconfigure), not only when notes change.
            val choices by NoteWidgets.changes.collectAsState()
            val note = remember(choices, all) { NoteWidgets.noteFor(context, appWidgetId)?.let { noteId -> all.firstOrNull { it.id == noteId } } }
            GlanceTheme(colors = JanusGlanceColors) {
                if (note == null || note.deleted != null) Missing(context, appWidgetId) else Content(context, note)
            }
        }
    }

    @Composable
    private fun Content(context: Context, note: Note) {
        val c = NoteWidgetColors
        val lines = NoteMarkdown.preview(note.text, keepBlank = true)
        val tasks = lines.filter { it.kind == NoteMarkdown.Kind.CHECK }
        val done = tasks.count { it.checked }
        // Ticked items fold into one line at the end, so what's left to do stays in front.
        val shown = lines.filterNot { it.kind == NoteMarkdown.Kind.CHECK && it.checked }
        val open = actionStartActivity(NoteEditorActivity.externalIntent(context, note.id))
        val edit = actionStartActivity(NoteEditorActivity.externalIntent(context, note.id, edit = true))
        Column(GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(26.dp).background(c.frame).padding(12.dp)) {
            // Header: the title; for notes with tasks also how many are done. The + opens the note ready to type.
            Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 10.dp), verticalAlignment = androidx.glance.layout.Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight().clickable(open)) {
                    Text(
                        note.title.ifEmpty { context.getString(R.string.notes_untitled) }, maxLines = 2,
                        style = TextStyle(color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    )
                    if (tasks.isNotEmpty()) {
                        Text(
                            context.getString(R.string.note_widget_progress, done, tasks.size),
                            style = TextStyle(color = c.muted, fontSize = 12.sp),
                            modifier = GlanceModifier.padding(top = 2.dp, bottom = 6.dp),
                        )
                        LinearProgressIndicator(
                            progress = done.toFloat() / tasks.size, color = c.primary, backgroundColor = c.track,
                            modifier = GlanceModifier.fillMaxWidth().height(4.dp).cornerRadius(2.dp),
                        )
                    }
                }
                Spacer(GlanceModifier.width(10.dp))
                Box(
                    GlanceModifier.size(38.dp).cornerRadius(19.dp).background(c.primary).clickable(edit),
                    contentAlignment = androidx.glance.layout.Alignment.Center,
                ) {
                    Image(
                        ImageProvider(R.drawable.ms_add), context.getString(R.string.note_widget_edit),
                        colorFilter = ColorFilter.tint(c.onPrimary), modifier = GlanceModifier.size(22.dp),
                    )
                }
            }
            // The note, on a lighter card; a fade at the bottom when it's longer than the widget.
            Box(GlanceModifier.fillMaxSize().cornerRadius(20.dp).background(c.card), contentAlignment = androidx.glance.layout.Alignment.BottomCenter) {
                LazyColumn(GlanceModifier.fillMaxSize().padding(horizontal = 14.dp)) {
                    item { Spacer(GlanceModifier.height(10.dp)) }
                    items(shown, itemId = { it.index.toLong() }) { line -> NoteWidgetLine(note, line, open) }
                    if (done > 0) item {
                        Text(
                            context.resources.getQuantityString(R.plurals.note_widget_ticked, done, done),
                            style = TextStyle(color = c.muted, fontSize = 13.sp),
                            modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp).clickable(open),
                        )
                    }
                    item { Spacer(GlanceModifier.height(24.dp)) }
                }
                Image(
                    ImageProvider(R.drawable.widget_fade), null, colorFilter = ColorFilter.tint(c.card),
                    contentScale = androidx.glance.layout.ContentScale.FillBounds,
                    modifier = GlanceModifier.fillMaxWidth().height(28.dp),
                )
            }
        }
    }

    /** The note was deleted (or never chosen): tap to pick one. */
    @Composable
    private fun Missing(context: Context, appWidgetId: Int) {
        Column(
            GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(24.dp).background(NoteWidgetColors.frame)
                .padding(16.dp).clickable(actionStartActivity(NoteWidgetConfigureActivity.intent(context, appWidgetId))),
            verticalAlignment = androidx.glance.layout.Alignment.CenterVertically,
            horizontalAlignment = androidx.glance.layout.Alignment.CenterHorizontally,
        ) {
            Image(
                ImageProvider(R.drawable.ms_sticky_note_2), null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant), modifier = GlanceModifier.size(28.dp),
            )
            Spacer(GlanceModifier.height(8.dp))
            Text(
                context.getString(R.string.note_widget_choose), maxLines = 2,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
            )
        }
    }
}

class NoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NoteWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { NoteWidgets.forget(context, it) }
    }
}

/** Which note each note widget shows. */
object NoteWidgets {
    private const val PREFS = "ge_connect_note_widgets"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun noteFor(context: Context, appWidgetId: Int): String? = prefs(context).getString("w$appWidgetId", null)
    private val _changes = kotlinx.coroutines.flow.MutableStateFlow(0)
    val changes: kotlinx.coroutines.flow.StateFlow<Int> = _changes

    fun set(context: Context, appWidgetId: Int, noteId: String) {
        prefs(context).edit(commit = true) { putString("w$appWidgetId", noteId) }
        _changes.value++
    }
    fun forget(context: Context, appWidgetId: Int) = prefs(context).edit { remove("w$appWidgetId") }
}
