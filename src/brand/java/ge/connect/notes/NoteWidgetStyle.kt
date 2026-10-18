/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.notes

import androidx.compose.runtime.Composable
import androidx.glance.layout.height
import androidx.glance.layout.Box
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.LocalContext
import android.widget.RemoteViews
import android.text.style.UnderlineSpan
import android.text.style.TypefaceSpan
import android.text.style.StyleSpan
import android.text.style.StrikethroughSpan
import android.text.style.RelativeSizeSpan
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.graphics.Typeface
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import ge.connect.ui.theme.JanusDarkColors
import ge.connect.ui.theme.JanusLightColors
import org.kde.kdeconnect_tp.R

/**
 * The note widgets' look (One UI style, "card in card"): a frame with the header, the note on a lighter card inside.
 * Janus colours, light and dark.
 */
object NoteWidgetColors {
    val frame = ColorProvider(JanusLightColors.surfaceContainer, JanusDarkColors.surfaceContainer)
    val card = ColorProvider(JanusLightColors.surfaceBright, JanusDarkColors.surfaceContainerHigh)
    val text = ColorProvider(JanusLightColors.onSurface, JanusDarkColors.onSurface)
    val muted = ColorProvider(JanusLightColors.onSurfaceVariant, JanusDarkColors.onSurfaceVariant)
    val primary = ColorProvider(JanusLightColors.primary, JanusDarkColors.primary)
    val onPrimary = ColorProvider(JanusLightColors.onPrimary, JanusDarkColors.onPrimary)
    val track = ColorProvider(JanusLightColors.surfaceContainerHighest, JanusDarkColors.surfaceContainerHighest)
}

/** A note line on a widget: round checkboxes (tick on tap), bullets, numbers, headings, inline styles; [open] for the rest. */
@Composable
fun NoteWidgetLine(note: Note, line: NoteMarkdown.PreviewLine, open: Action, singleLine: Boolean = false) {
    val c = NoteWidgetColors
    val indent = (line.level * 14).dp
    if (line.kind == NoteMarkdown.Kind.BLANK) {
        Spacer(GlanceModifier.height(10.dp))
        return
    }
    if (line.kind == NoteMarkdown.Kind.CHECK) {
        Row(
            GlanceModifier.fillMaxWidth().padding(start = indent, top = 3.dp, bottom = 3.dp)
                .clickable(actionRunCallback<ToggleItemAction>(actionParametersOf(NotesWidget.KEY_NOTE to note.id, NotesWidget.KEY_LINE to line.index))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                ImageProvider(if (line.checked) R.drawable.ms_check_circle_widget else R.drawable.ms_radio_button_unchecked), null,
                colorFilter = ColorFilter.tint(if (line.checked) c.primary else c.muted), modifier = GlanceModifier.size(20.dp),
            )
            Spacer(GlanceModifier.width(10.dp))
            RichText(spanned(line), muted = line.checked, singleLine = singleLine, modifier = GlanceModifier.defaultWeight())
        }
        return
    }
    val heading = line.kind == NoteMarkdown.Kind.TITLE || line.kind == NoteMarkdown.Kind.HEADING || line.kind == NoteMarkdown.Kind.SUBHEADING
    Box(GlanceModifier.fillMaxWidth().padding(start = indent, top = if (heading) 8.dp else 2.dp, bottom = 2.dp).clickable(open)) {
        RichText(
            spanned(line), singleLine = singleLine,
            muted = line.kind == NoteMarkdown.Kind.QUOTE || line.kind == NoteMarkdown.Kind.ATTACHMENT,
        )
    }
}

/** Text with inline styles: Glance's Text has one style per text, so a TextView (RemoteViews) shows the spans. */
@Composable
private fun RichText(text: CharSequence, muted: Boolean, singleLine: Boolean, modifier: GlanceModifier = GlanceModifier) {
    val context = LocalContext.current
    val layout = when {
        singleLine && muted -> R.layout.widget_note_text_single_muted
        singleLine -> R.layout.widget_note_text_single
        muted -> R.layout.widget_note_text_muted
        else -> R.layout.widget_note_text
    }
    val views = RemoteViews(context.packageName, layout).apply { setTextViewText(R.id.note_text, text) }
    AndroidRemoteViews(views, modifier)
}

/** The line's text with its prefix (bullet, number…) and Markdown styles as spans. */
private fun spanned(line: NoteMarkdown.PreviewLine): CharSequence {
    val prefix = when (line.kind) {
        NoteMarkdown.Kind.BULLET -> "•  "
        NoteMarkdown.Kind.NUMBER -> "${line.number}.  "
        NoteMarkdown.Kind.QUOTE -> "▍ "
        NoteMarkdown.Kind.ATTACHMENT -> "📎 "
        else -> ""
    }
    val out = SpannableStringBuilder(prefix + line.text)
    val all = prefix.length until out.length
    fun span(what: Any, from: Int, to: Int) { if (to > from) out.setSpan(what, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    for (r in line.styles) {
        val from = prefix.length + r.start
        val to = prefix.length + r.end
        when (r.style) {
            NoteMarkdown.Style.BOLD -> span(StyleSpan(Typeface.BOLD), from, to)
            NoteMarkdown.Style.ITALIC -> span(StyleSpan(Typeface.ITALIC), from, to)
            NoteMarkdown.Style.STRIKE -> span(StrikethroughSpan(), from, to)
            NoteMarkdown.Style.CODE -> span(TypefaceSpan("monospace"), from, to)
            NoteMarkdown.Style.LINK -> span(UnderlineSpan(), from, to)
            NoteMarkdown.Style.ESCAPE -> {}
        }
    }
    when (line.kind) {
        NoteMarkdown.Kind.TITLE -> { span(StyleSpan(Typeface.BOLD), all.first, out.length); span(RelativeSizeSpan(1.15f), all.first, out.length) }
        NoteMarkdown.Kind.HEADING -> { span(StyleSpan(Typeface.BOLD), all.first, out.length); span(RelativeSizeSpan(1.08f), all.first, out.length) }
        NoteMarkdown.Kind.SUBHEADING -> span(StyleSpan(Typeface.BOLD), all.first, out.length)
        NoteMarkdown.Kind.CODE -> span(TypefaceSpan("monospace"), 0, out.length)
        NoteMarkdown.Kind.QUOTE -> span(StyleSpan(Typeface.ITALIC), all.first, out.length)
        NoteMarkdown.Kind.CHECK -> if (line.checked) span(StrikethroughSpan(), 0, out.length)
        else -> {}
    }
    return out
}
