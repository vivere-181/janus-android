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
            GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(26.dp)
                .background(NoteWidgetColors.frame).padding(12.dp),
        ) {
            Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    context.getString(R.string.notes_title),
                    style = TextStyle(color = NoteWidgetColors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(NotesActivity.intent(context))),
                )
                Box(
                    GlanceModifier.size(38.dp).cornerRadius(19.dp).background(NoteWidgetColors.primary)
                        .clickable(actionStartActivity(NoteEditorActivity.newNoteExternalIntent(context))),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        ImageProvider(R.drawable.ms_add), context.getString(R.string.notes_new),
                        colorFilter = ColorFilter.tint(NoteWidgetColors.onPrimary), modifier = GlanceModifier.size(22.dp),
                    )
                }
            }
            if (notes.isEmpty()) {
                Box(GlanceModifier.fillMaxSize().clickable(actionStartActivity(NoteEditorActivity.newNoteExternalIntent(context))), contentAlignment = Alignment.Center) {
                    Text(context.getString(R.string.notes_empty_widget), style = TextStyle(color = NoteWidgetColors.muted, fontSize = 14.sp))
                }
            } else {
                LazyColumn {
                    items(notes, itemId = { it.id.hashCode().toLong() }) { note ->
                        Column(GlanceModifier.fillMaxWidth().padding(bottom = 8.dp)) { NoteCard(context, note, lines) }
                    }
                }
            }
        }
    }

    @Composable
    private fun NoteCard(context: Context, note: Note, lines: Int) {
        val c = NoteWidgetColors
        val open = actionStartActivity(NoteEditorActivity.externalIntent(context, note.id))
        Column(
            GlanceModifier.fillMaxWidth().cornerRadius(20.dp).background(c.card)
                .padding(horizontal = 14.dp, vertical = 12.dp).clickable(open),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    note.title.ifEmpty { context.getString(R.string.notes_untitled) }, maxLines = 1,
                    style = TextStyle(color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.defaultWeight(),
                )
                if (note.pinned) Image(
                    ImageProvider(R.drawable.ms_push_pin_filled), null,
                    colorFilter = ColorFilter.tint(c.primary), modifier = GlanceModifier.size(14.dp),
                )
            }
            // The lines after the title (ticked items left out); checklist items tick right here.
            NoteMarkdown.preview(note.text).filterNot { it.kind == NoteMarkdown.Kind.CHECK && it.checked }.take(lines).forEach { line ->
                NoteWidgetLine(note, line, open, singleLine = true)
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

        private var pending: kotlinx.coroutines.Job? = null

        /**
         * The notes changed: redraw the widgets (Glance also picks it up by itself while its session is alive). Skipped when
         * no Notes widget is on the home screen; bursts (typing, a sync batch) make one update (R34).
         */
        fun requestUpdate() {
            val context = appContext ?: return
            fun placed(receiver: Class<*>) = runCatching {
                android.appwidget.AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(android.content.ComponentName(context, receiver)).isNotEmpty()
            }.getOrDefault(false)
            val list = placed(NotesWidgetReceiver::class.java)
            val single = placed(NoteWidgetReceiver::class.java)
            if (!list && !single) return
            synchronized(this) {
                pending?.cancel()
                pending = scope.launch {
                    kotlinx.coroutines.delay(500)
                    if (list) runCatching { NotesWidget().updateAll(context) }
                    if (single) runCatching { NoteWidget().updateAll(context) }
                }
            }
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
