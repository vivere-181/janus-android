/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.notes

/**
 * The Markdown subset notes use (PROTOCOL.md (janus-mac docs) → ge.connect.notes, R30): per line a block kind (title, headings,
 * lists, checklist, quote, code fence) with its nesting and the syntax to hide, and inline spans (bold, italic,
 * strikethrough, code, links). Pure; offsets are within the whole text.
 */
object NoteMarkdown {
    enum class Kind { BODY, BLANK, TITLE, HEADING, SUBHEADING, BULLET, NUMBER, CHECK, QUOTE, FENCE, CODE, ATTACHMENT, TABLE_ROW, TABLE_RULE }

    /** `![name](attachments/<sha>.<ext>)` alone on a line (R30; name: `\` and `]` escaped). */
    val ATTACHMENT = Regex("""^!\[((?:\\.|[^\]\\])*)\]\(attachments/([0-9a-f]{64}\.[a-z0-9]{1,8})\)\s*$""")

    data class Line(
        val index: Int,
        /** Offsets of the line in the text (end excludes the newline). */
        val start: Int,
        val end: Int,
        val kind: Kind,
        /** Nesting level = leading tabs (lists, checklists, quotes). */
        val level: Int = 0,
        /** End of the leading syntax (tabs + marker), i.e. where the content starts. */
        val contentStart: Int = start,
        val checked: Boolean = false,
        /** For NUMBER: the number written. */
        val number: Int = 0,
    ) {
        val markerStart get() = start + level
        val isListLike get() = kind == Kind.BULLET || kind == Kind.NUMBER || kind == Kind.CHECK || kind == Kind.QUOTE
    }

    /** `| --- | :---: |` (GitHub table delimiter row). */
    private val TABLE_RULE_LINE = Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$""")

    private val CHECK = Regex("""^(\t*)[-*+] \[([ xX])] """)
    private val BULLET = Regex("""^(\t*)[-*+] """)
    private val NUMBER = Regex("""^(\t*)(\d{1,9})[.)] """)
    private val QUOTE = Regex("""^(\t*)> ?""")
    private val HEADING = Regex("""^(#{1,6}) """)

    fun lines(text: String): List<Line> {
        val result = ArrayList<Line>()
        var start = 0
        var inFence = false
        var index = 0
        while (true) {
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline
            val raw = text.substring(start, end)
            val line = when {
                raw.trimEnd().startsWith("```") && !raw.startsWith("\t") -> { inFence = !inFence; Line(index, start, end, Kind.FENCE, contentStart = end) }
                inFence -> Line(index, start, end, Kind.CODE)
                else -> parse(raw, index, start, end)
            }
            result += line
            if (newline < 0) break
            start = newline + 1
            index++
        }
        markTables(text, result)
        return result
    }

    /** A run of `|` lines whose second line is a delimiter row is a table (R30). */
    private fun markTables(text: String, lines: MutableList<Line>) {
        var i = 0
        while (i < lines.size) {
            fun isPipe(k: Int) = lines.getOrNull(k)?.let { l -> l.kind == Kind.BODY && text.substring(l.start, l.end).trimStart().startsWith("|") } == true
            if (isPipe(i) && i + 1 < lines.size && lines[i + 1].kind == Kind.BODY &&
                TABLE_RULE_LINE.matches(text.substring(lines[i + 1].start, lines[i + 1].end))
            ) {
                lines[i] = lines[i].copy(kind = Kind.TABLE_ROW)
                lines[i + 1] = lines[i + 1].copy(kind = Kind.TABLE_RULE)
                var k = i + 2
                while (isPipe(k)) { lines[k] = lines[k].copy(kind = Kind.TABLE_ROW); k++ }
                i = k
            } else i++
        }
    }

    /** The cells of a table row as content ranges (trimmed) in [text]; `\|` doesn't split. */
    fun cells(text: String, line: Line): List<IntRange> {
        val bounds = ArrayList<Int>()
        var i = line.start
        while (i < line.end) {
            when (text[i]) {
                '\\' -> i++
                '|' -> bounds += i
            }
            i++
        }
        // Leading/trailing pipes are optional in GitHub tables.
        val first = (line.start until line.end).firstOrNull { text[it] != ' ' } ?: return emptyList()
        val last = (line.end - 1 downTo line.start).first { text[it] != ' ' }
        if (bounds.isEmpty() || bounds.first() != first) bounds.add(0, line.start - 1)
        if (bounds.last() != last) bounds += line.end
        return bounds.zipWithNext().map { (a, b) ->
            var from = a + 1
            var to = b
            while (from < to && text[from] == ' ') from++
            while (to > from && text[to - 1] == ' ') to--
            // An empty cell's place is between its two spaces (`|  |`), where typing gives `| x |`.
            if (from >= to) ((a + 1 + b) / 2).let { mid -> mid until mid } else from until to
        }
    }

    /** A cell's text for display (backslash escapes and inline markers removed). */
    fun cellText(text: String, range: IntRange): String = if (range.isEmpty()) "" else plain(text.substring(range.first, range.last + 1).replace("\\|", "|"))

    private fun parse(raw: String, index: Int, start: Int, end: Int): Line {
        if (raw.isBlank()) return Line(index, start, end, Kind.BLANK)
        if (raw.startsWith("![") && ATTACHMENT.matches(raw)) return Line(index, start, end, Kind.ATTACHMENT)
        CHECK.find(raw)?.let { m ->
            return Line(index, start, end, Kind.CHECK, m.groupValues[1].length, start + m.value.length, checked = m.groupValues[2] != " ")
        }
        NUMBER.find(raw)?.let { m ->
            return Line(index, start, end, Kind.NUMBER, m.groupValues[1].length, start + m.value.length, number = m.groupValues[2].toIntOrNull() ?: 1)
        }
        BULLET.find(raw)?.let { m -> return Line(index, start, end, Kind.BULLET, m.groupValues[1].length, start + m.value.length) }
        QUOTE.find(raw)?.let { m -> return Line(index, start, end, Kind.QUOTE, m.groupValues[1].length, start + m.value.length) }
        HEADING.find(raw)?.let { m ->
            val kind = when (m.groupValues[1].length) { 1 -> Kind.TITLE; 2 -> Kind.HEADING; else -> Kind.SUBHEADING }
            return Line(index, start, end, kind, 0, start + m.value.length)
        }
        return Line(index, start, end, Kind.BODY)
    }

    /** The note's title line: the first non-empty one (outside code). */
    fun titleLine(lines: List<Line>): Line? = lines.firstOrNull {
        it.kind != Kind.BLANK && it.kind != Kind.FENCE && it.kind != Kind.ATTACHMENT && it.kind != Kind.TABLE_ROW && it.kind != Kind.TABLE_RULE
    }

    // Inline ---------------------------------------------------------------------------------------------------------

    enum class Style { BOLD, ITALIC, STRIKE, CODE, LINK, ESCAPE }

    /** A styled run: [contentStart, contentEnd) is shown, [markers] are hidden. [url] for links. */
    data class Span(val style: Style, val contentStart: Int, val contentEnd: Int, val markers: List<IntRange>, val url: String? = null)

    private val BARE_URL = Regex("""https?://[^\s<>()\[\]]+[^\s<>()\[\].,;:!?'"]""")

    /**
     * Inline spans of [text] between [from] and [to] (one line's content). Backslash escapes (`\*`) hide the
     * backslash and keep the character literal; code spans aren't parsed further.
     */
    fun spans(text: String, from: Int, to: Int): List<Span> {
        val spans = ArrayList<Span>()
        var i = from
        while (i < to) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < to && text[i + 1] in "\\`*_~[]()#>|!-+." -> {
                    spans += Span(Style.ESCAPE, i + 1, i + 2, listOf(i..i)) // hide the backslash, keep the character
                    i += 2
                    continue
                }
                c == '`' -> {
                    val close = text.indexOf('`', i + 1).takeIf { it in (i + 2)..<to }
                    if (close != null) {
                        spans += Span(Style.CODE, i + 1, close, listOf(i..i, close..close))
                        i = close + 1
                        continue
                    }
                }
                c == '[' -> {
                    val closeText = findClose(text, i + 1, to, ']')
                    if (closeText != null && closeText + 1 < to && text[closeText + 1] == '(') {
                        val closeUrl = findClose(text, closeText + 2, to, ')')
                        if (closeUrl != null) {
                            val url = text.substring(closeText + 2, closeUrl).removeSurrounding("<", ">")
                            spans += Span(Style.LINK, i + 1, closeText, listOf(i..i, (closeText)..closeUrl), url)
                            spans += spans(text, i + 1, closeText)
                            i = closeUrl + 1
                            continue
                        }
                    }
                }
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val marker = text.substring(i, i + 2)
                    val close = text.indexOf(marker, i + 2).takeIf { it in (i + 3)..(to - 2) }
                    if (close != null && !text[i + 2].isWhitespace()) {
                        spans += Span(Style.BOLD, i + 2, close, listOf(i..(i + 1), close..(close + 1)))
                        spans += spans(text, i + 2, close)
                        i = close + 2
                        continue
                    }
                }
                text.startsWith("~~", i) -> {
                    val close = text.indexOf("~~", i + 2).takeIf { it in (i + 3)..(to - 2) }
                    if (close != null) {
                        spans += Span(Style.STRIKE, i + 2, close, listOf(i..(i + 1), close..(close + 1)))
                        spans += spans(text, i + 2, close)
                        i = close + 2
                        continue
                    }
                }
                (c == '*' || c == '_') && i + 1 < to && !text[i + 1].isWhitespace() -> {
                    // A single * or _ (not part of **); _ only at word boundaries.
                    val wordStart = c == '*' || i == from || !text[i - 1].isLetterOrDigit()
                    var close = i + 1
                    var found = -1
                    while (wordStart && close < to) {
                        if (text[close] == c && !text[close - 1].isWhitespace() && !(close + 1 < to && text[close + 1] == c) &&
                            (c == '*' || close + 1 >= to || !text[close + 1].isLetterOrDigit())
                        ) { found = close; break }
                        if (text[close] == '\\') close++
                        close++
                    }
                    if (found > i + 1) {
                        spans += Span(Style.ITALIC, i + 1, found, listOf(i..i, found..found))
                        spans += spans(text, i + 1, found)
                        i = found + 1
                        continue
                    }
                }
                c == 'h' && (text.startsWith("http://", i) || text.startsWith("https://", i)) && (i == from || !text[i - 1].isLetterOrDigit()) -> {
                    BARE_URL.find(text.substring(i, to))?.takeIf { it.range.first == 0 }?.let { m ->
                        spans += Span(Style.LINK, i, i + m.value.length, emptyList(), m.value)
                        i += m.value.length
                        continue
                    }
                }
            }
            i++
        }
        return spans
    }

    private fun findClose(text: String, from: Int, to: Int, close: Char): Int? {
        var depth = 0
        var i = from
        val open = if (close == ']') '[' else '('
        while (i < to) {
            when (text[i]) {
                '\\' -> i++
                open -> depth++
                close -> if (depth == 0) return i else depth--
            }
            i++
        }
        return null
    }

    /** A line for previews (list cards, widget): its kind and text without Markdown. [index] = line index in the text. */
    data class PreviewLine(val index: Int, val kind: Kind, val level: Int, val text: String, val checked: Boolean, val number: Int)

    /** The lines after the title, ready to show without Markdown (blank lines and code fences left out). */
    fun preview(text: String): List<PreviewLine> {
        val lines = lines(text)
        val title = titleLine(lines)
        return lines.filter { it !== title && it.kind != Kind.BLANK && it.kind != Kind.FENCE && it.kind != Kind.TABLE_RULE }.map {
            val raw = text.substring(it.start, it.end)
            val shown = when (it.kind) {
                Kind.CODE -> raw
                Kind.TABLE_ROW -> cells(text, it).joinToString("  │  ") { r -> cellText(text, r) }
                Kind.ATTACHMENT -> ATTACHMENT.find(raw)?.groupValues?.get(1)?.replace(Regex("""\\(.)"""), "$1").orEmpty()
                else -> plain(raw)
            }
            PreviewLine(it.index, it.kind, it.level, shown, it.checked, it.number)
        }
    }

    /** [text] of one line without its Markdown (for titles, previews, search results). */
    fun plain(line: String): String {
        val parsed = lines(line).first()
        val content = line.substring(parsed.contentStart - parsed.start)
        val hidden = BooleanArray(content.length)
        spans(content, 0, content.length).forEach { span -> span.markers.forEach { r -> for (k in r) if (k in hidden.indices) hidden[k] = true } }
        return buildString { content.forEachIndexed { k, ch -> if (!hidden[k]) append(ch) } }.trim()
    }
}
