/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.notes

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.kde.kdeconnect_tp.R

/**
 * Notes on the home screen (R29): pinned notes first, then recent ones, checklist items tickable, tap opens the note,
 * + makes a new one. Material You colours; resizable from a small 2×2 to a tall list.
 */
class NotesWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Glance keeps the composition alive, so collect the notes instead of reading them once.
            val all by NotesStore.all.collectAsState()
            GlanceTheme(colors = ge.connect.ui.theme.JanusGlanceColors) { Content(context, NotesStore.visible(all)) }
        }
    }

    @Composable
    private fun Content(context: Context, notes: List<Note>) {
        val size = LocalSize.current
        val lines = if (size.height >= TALL.height) 4 else 2
        Column(
            GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(24.dp)
                .background(GlanceTheme.colors.widgetBackground).padding(12.dp),
        ) {
            Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    context.getString(R.string.notes_title),
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Medium),
                    modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(NotesActivity.intent(context))),
                )
                Box(
                    GlanceModifier.size(40.dp).cornerRadius(20.dp).background(GlanceTheme.colors.primaryContainer)
                        .clickable(actionStartActivity(NoteEditorActivity.newNoteIntent(context))),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        ImageProvider(R.drawable.ms_add), context.getString(R.string.notes_new),
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer), modifier = GlanceModifier.size(24.dp),
                    )
                }
            }
            if (notes.isEmpty()) {
                Box(GlanceModifier.fillMaxSize().clickable(actionStartActivity(NoteEditorActivity.newNoteIntent(context))), contentAlignment = Alignment.Center) {
                    Text(context.getString(R.string.notes_empty_widget), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
                }
            } else {
                LazyColumn {
                    items(notes, itemId = { it.id.hashCode().toLong() }) { note ->
                        Column(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) { NoteCard(context, note, lines) }
                    }
                }
            }
        }
    }

    @Composable
    private fun NoteCard(context: Context, note: Note, lines: Int) {
        val open = actionStartActivity(NoteEditorActivity.intent(context, note.id))
        Column(
            GlanceModifier.fillMaxWidth().cornerRadius(16.dp).background(GlanceTheme.colors.secondaryContainer)
                .padding(horizontal = 12.dp, vertical = 10.dp).clickable(open),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (note.pinned) {
                    Image(
                        ImageProvider(R.drawable.ms_push_pin_filled), null,
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.primary), modifier = GlanceModifier.size(14.dp),
                    )
                    Spacer(GlanceModifier.width(4.dp))
                }
                Text(
                    note.title.ifEmpty { context.getString(R.string.notes_untitled) }, maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                )
            }
            // The lines after the title; checklist items get a box that ticks right here.
            NoteMarkdown.preview(note.text).take(lines).forEach { line ->
                Spacer(GlanceModifier.height(2.dp))
                val indent = (line.level * 12).dp
                if (line.kind == NoteMarkdown.Kind.CHECK) {
                    CheckBox(
                        checked = line.checked,
                        onCheckedChange = actionRunCallback<ToggleItemAction>(actionParametersOf(KEY_NOTE to note.id, KEY_LINE to line.index)),
                        text = line.text,
                        style = TextStyle(
                            color = if (line.checked) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSecondaryContainer,
                            fontSize = 13.sp, textDecoration = if (line.checked) TextDecoration.LineThrough else null,
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.height(28.dp).padding(start = indent),
                    )
                } else {
                    val prefix = when (line.kind) {
                        NoteMarkdown.Kind.BULLET -> "•  "
                        NoteMarkdown.Kind.NUMBER -> "${line.number}.  "
                        NoteMarkdown.Kind.QUOTE -> "▍ "
                        else -> ""
                    }
                    val heading = line.kind == NoteMarkdown.Kind.TITLE || line.kind == NoteMarkdown.Kind.HEADING || line.kind == NoteMarkdown.Kind.SUBHEADING
                    Text(
                        prefix + line.text, maxLines = 1,
                        style = TextStyle(
                            color = if (heading) GlanceTheme.colors.onSecondaryContainer else GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 13.sp, fontWeight = if (heading) FontWeight.Medium else null,
                        ),
                        modifier = GlanceModifier.padding(start = indent),
                    )
                }
            }
        }
    }

    companion object {
        private val SMALL = DpSize(110.dp, 110.dp)
        private val WIDE = DpSize(240.dp, 110.dp)
        private val TALL = DpSize(240.dp, 250.dp)
        val KEY_NOTE = ActionParameters.Key<String>("note")
        val KEY_LINE = ActionParameters.Key<Int>("line")

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var appContext: Context? = null

        fun init(context: Context) { appContext = context.applicationContext }

        /** The notes changed: redraw the widgets (Glance also picks it up by itself while its session is alive). */
        fun requestUpdate() {
            val context = appContext ?: return
            scope.launch { runCatching { NotesWidget().updateAll(context) } }
        }
    }
}

class NotesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NotesWidget()
}

/** Ticks a checklist item from the widget. */
class ToggleItemAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[NotesWidget.KEY_NOTE] ?: return
        val line = parameters[NotesWidget.KEY_LINE] ?: return
        NotesStore.toggleItem(id, line)
    }
}
