/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.notes

import ge.connect.notes.NoteMarkdown.Kind

/**
 * Markdown-aware editing on the raw note text (R30). The editor hides list/checklist/quote markers and nesting tabs
 * (drawing bullets and boxes instead), so the cursor must never rest inside them and Return/Backspace act on whole
 * markers. Pure functions on (text, selection) so they can be tested.
 */
object NoteEditing {
    data class Edit(val text: String, val cursor: Int, val selectionEnd: Int = cursor)

    /** Start offsets of the lines of [text]. */
    fun lineStarts(text: String): List<Int> = buildList {
        add(0)
        text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
    }

    private fun lineAt(lines: List<NoteMarkdown.Line>, offset: Int) = lines.last { it.start <= offset }

    /** Lines (with markers the editor hides) whose hidden part strictly contains [offset]. */
    private fun hiddenPrefixAt(lines: List<NoteMarkdown.Line>, offset: Int) =
        lines.firstOrNull { it.isListLike && offset > it.start && offset < it.contentStart }

    /**
     * The user changed [old] (cursor [oldCursor]) into [new] (cursor [newCursor]). Return continues a list (numbers
     * count up) or, on an empty item, outdents it and at level 0 ends the list; deleting into a marker outdents, then
     * removes the marker; joining a list line to the one above drops its marker; typing inside hidden syntax goes after
     * it; numbered lists are renumbered.
     */
    fun apply(old: String, oldCursor: Int, new: String, newCursor: Int): Edit {
        if (old == new) return Edit(new, snap(new, newCursor))
        var start = 0
        while (start < old.length && start < new.length && old[start] == new[start]) start++
        var oldEnd = old.length
        var newEnd = new.length
        while (oldEnd > start && newEnd > start && old[oldEnd - 1] == new[newEnd - 1]) { oldEnd--; newEnd-- }
        // The same change can often be read at several places ("a\n" + "\n" vs "\n" + "a\n"): prefer the one at the cursor.
        val length = newEnd - start - (oldEnd - start)
        if (oldEnd == start && newCursor - length in 0..old.length && newCursor <= new.length &&
            new.substring(newCursor - length, newCursor) == new.substring(start, newEnd) &&
            old == new.removeRange(newCursor - length, newCursor)
        ) { start = newCursor - length; oldEnd = start; newEnd = newCursor }
        if (newEnd == start && oldCursor <= old.length && newCursor in 0..oldCursor && new == old.removeRange(newCursor, oldCursor) &&
            oldCursor - newCursor == oldEnd - start
        ) { start = newCursor; oldEnd = oldCursor; newEnd = newCursor }
        val inserted = new.substring(start, newEnd)
        val lines = NoteMarkdown.lines(old)

        // Tables (R30): a cell never holds a line break or a bare `|`, and rows aren't merged by Backspace.
        val row = lines.lastOrNull { it.start <= start && (it.kind == Kind.TABLE_ROW || it.kind == Kind.TABLE_RULE) }?.takeIf { start <= it.end }
        if (row != null && oldEnd <= row.end) {
            if (inserted == "\n" && oldEnd == start) return nextRow(old, lines, row, start)
            if (inserted == "\t" && oldEnd == start && row.kind == Kind.TABLE_ROW) return nextCell(old, lines, row, start)
            if (inserted == "|" && oldEnd == start && row.kind == Kind.TABLE_ROW) return Edit(old.substring(0, start) + "\\|" + old.substring(start), start + 2)
            if (inserted.isEmpty() && old.substring(start, oldEnd).let { gone -> gone.contains('|') || gone.contains('\n') } &&
                !old.substring(start, oldEnd).contains("\\|")
            ) return Edit(old, oldCursor)
        }
        if (inserted.isEmpty() && old.substring(start, oldEnd) == "\n" &&
            lines.any { it.start == oldEnd && (it.kind == Kind.TABLE_ROW || it.kind == Kind.TABLE_RULE) }
        ) return Edit(old, oldCursor)

        if (inserted.isEmpty()) {
            // Deleting into a marker: outdent first, then remove the marker (the content stays).
            val line = lines.firstOrNull { it.isListLike && start >= it.start && start < it.contentStart && oldEnd <= it.contentStart }
            if (line != null) {
                return renumbered(
                    if (line.level > 0) Edit(old.removeRange(line.start, line.start + 1), line.contentStart - 1)
                    else Edit(old.removeRange(line.markerStart, line.contentStart), line.markerStart),
                )
            }
            // Backspace at the start of a list line joins it to the line above, without its marker.
            if (old.substring(start, oldEnd) == "\n") {
                val next = lines.firstOrNull { it.start == oldEnd }
                if (next != null && next.isListLike) return renumbered(Edit(old.removeRange(start, next.contentStart), start))
            }
            return renumbered(Edit(new, snap(new, newCursor)))
        }

        // Tab on a list item nests it (hardware keyboards; the format bar has buttons for this).
        if (inserted == "\t" && oldEnd == start) {
            val line = lineAt(lines, start)
            if (line.isListLike && start >= line.contentStart) return indent(old, start, start, outdent = false)
        }

        // Typing inside hidden syntax: put it after.
        if (oldEnd == start) hiddenPrefixAt(lines, start)?.let { line ->
            val at = line.contentStart
            return renumbered(Edit(old.substring(0, at) + inserted + old.substring(at), at + inserted.length))
        }

        // Return in a list item.
        if (inserted == "\n" && oldEnd == start) {
            val line = lineAt(lines, start)
            if (line.isListLike && start >= line.contentStart) {
                if (line.end == line.contentStart) {
                    // Empty item: outdent, or at level 0 end the list.
                    return renumbered(
                        if (line.level > 0) Edit(old.removeRange(line.start, line.start + 1), line.contentStart - 1)
                        else Edit(old.removeRange(line.markerStart, line.contentStart), line.markerStart),
                    )
                }
                val add = "\n" + "\t".repeat(line.level) + nextMarker(old, line)
                return renumbered(Edit(old.substring(0, start) + add + old.substring(start), start + add.length))
            }
        }
        return renumbered(Edit(new, snap(new, newCursor)))
    }

    /** The marker a new item after [line] gets. */
    private fun nextMarker(text: String, line: NoteMarkdown.Line): String = when (line.kind) {
        Kind.CHECK -> Checklist.UNCHECKED
        Kind.NUMBER -> "${line.number + 1}" + text.substring(line.markerStart, line.contentStart).dropWhile { it.isDigit() }
        Kind.QUOTE -> "> "
        else -> "- "
    }

    /** A cursor inside hidden syntax (nesting tabs, list markers) moves to where the content starts. */
    fun snap(text: String, cursor: Int): Int = hiddenPrefixAt(NoteMarkdown.lines(text), cursor)?.contentStart ?: cursor

    /** Numbers count 1, 2, 3… per level within a list (the first item keeps its number). Keeps the cursor in place. */
    fun renumbered(edit: Edit): Edit {
        val lines = NoteMarkdown.lines(edit.text)
        val next = HashMap<Int, Int>() // level → the number the next item should have
        val out = StringBuilder()
        var cursor = edit.cursor
        var selectionEnd = edit.selectionEnd
        var copied = 0
        for (line in lines) {
            when {
                line.kind == Kind.NUMBER -> {
                    next.keys.filter { it > line.level }.forEach { next.remove(it) }
                    val expected = next[line.level] ?: line.number
                    next[line.level] = expected + 1
                    if (expected != line.number) {
                        val digitsEnd = line.markerStart + line.number.toString().length
                        out.append(edit.text, copied, line.markerStart).append(expected)
                        val delta = expected.toString().length - (digitsEnd - line.markerStart)
                        if (cursor >= digitsEnd) cursor += delta
                        if (selectionEnd >= digitsEnd) selectionEnd += delta
                        copied = digitsEnd
                    }
                }
                line.isListLike -> next.keys.filter { it > line.level }.forEach { next.remove(it) }
                line.kind == Kind.BLANK || line.kind == Kind.BODY || line.kind == Kind.TITLE || line.kind == Kind.HEADING ||
                    line.kind == Kind.SUBHEADING || line.kind == Kind.FENCE -> next.clear()
                else -> {}
            }
        }
        if (copied == 0) return edit
        out.append(edit.text, copied, edit.text.length)
        return Edit(out.toString(), cursor, selectionEnd)
    }

    // Formatting (the format bar) ------------------------------------------------------------------------------------

    /** Lines touched by the selection [from]..[to]. */
    private fun selectedLines(text: String, from: Int, to: Int): List<NoteMarkdown.Line> {
        val lines = NoteMarkdown.lines(text)
        val a = minOf(from, to).coerceIn(0, text.length)
        val b = maxOf(from, to).coerceIn(0, text.length)
        return lines.filter { it.end >= a && it.start <= b }.ifEmpty { listOf(lineAt(lines, a)) }
    }

    /**
     * Gives the selected lines block style [kind] (TITLE, HEADING, SUBHEADING, BODY, BULLET, NUMBER, CHECK, QUOTE).
     * If they all have it already, they go back to body text. Lists keep their nesting.
     */
    fun setBlock(text: String, from: Int, to: Int, kind: Kind): Edit {
        val lines = selectedLines(text, from, to).filter { it.kind != Kind.FENCE && it.kind != Kind.CODE }
        if (lines.isEmpty()) return Edit(text, from, to)
        val target = if (lines.all { it.kind == kind || (it.kind == Kind.BLANK && kind != Kind.BODY) }) Kind.BODY else kind
        val out = StringBuilder(text)
        var cursor = from
        var selectionEnd = to
        var number = 1
        for (line in lines.reversed()) {
            val keepTabs = target in setOf(Kind.BULLET, Kind.NUMBER, Kind.CHECK, Kind.QUOTE) && line.isListLike
            val prefixStart = if (keepTabs) line.markerStart else line.start
            val marker = when (target) {
                Kind.TITLE -> "# "; Kind.HEADING -> "## "; Kind.SUBHEADING -> "### "
                Kind.BULLET -> "- "; Kind.NUMBER -> "${lines.size - number++ + 1}. "; Kind.CHECK -> Checklist.UNCHECKED; Kind.QUOTE -> "> "
                else -> ""
            }
            val removed = line.contentStart - prefixStart
            out.replace(prefixStart, line.contentStart, marker)
            val delta = marker.length - removed
            if (cursor >= line.contentStart) cursor += delta else if (cursor > prefixStart) cursor = prefixStart + marker.length
            if (selectionEnd >= line.contentStart) selectionEnd += delta else if (selectionEnd > prefixStart) selectionEnd = prefixStart + marker.length
        }
        return renumbered(Edit(out.toString(), cursor, selectionEnd))
    }

    /** Wraps the selected lines in a code fence, or unwraps the code block the cursor is in. */
    fun toggleCode(text: String, from: Int, to: Int): Edit {
        val lines = NoteMarkdown.lines(text)
        val here = lineAt(lines, minOf(from, to).coerceIn(0, text.length))
        if (here.kind == Kind.CODE || here.kind == Kind.FENCE) {
            // Unwrap: find the fences around the cursor.
            val openIndex = lines.subList(0, here.index + 1).indexOfLast { it.kind == Kind.FENCE && (it.index < here.index || here.kind == Kind.FENCE) }
            val open = lines.getOrNull(openIndex) ?: return Edit(text, from, to)
            val close = lines.drop(open.index + 1).firstOrNull { it.kind == Kind.FENCE }
            val out = StringBuilder(text)
            var cursor = from
            close?.let { out.delete(maxOf(it.start - 1, 0), it.end); if (cursor > it.start) cursor -= (it.end - it.start + 1) }
            val removeEnd = minOf(open.end + 1, out.length)
            out.delete(open.start, removeEnd)
            if (cursor > open.start) cursor = maxOf(open.start, cursor - (removeEnd - open.start))
            return Edit(out.toString(), cursor)
        }
        val selected = selectedLines(text, from, to)
        val first = selected.first()
        val last = selected.last()
        val out = text.substring(0, first.start) + "```\n" + text.substring(first.start, last.end) + "\n```" + text.substring(last.end)
        return Edit(out, from + 4, to + 4)
    }

    /** Nests (or un-nests) the selected list lines by one level. */
    fun indent(text: String, from: Int, to: Int, outdent: Boolean): Edit {
        val lines = selectedLines(text, from, to).filter { it.isListLike && (!outdent || it.level > 0) }
        if (lines.isEmpty()) return Edit(text, from, to)
        val out = StringBuilder(text)
        var cursor = from
        var selectionEnd = to
        for (line in lines.reversed()) {
            if (outdent) out.deleteCharAt(line.start) else out.insert(line.start, '\t')
            val delta = if (outdent) -1 else 1
            if (cursor > line.start) cursor += delta
            if (selectionEnd > line.start) selectionEnd += delta
        }
        return renumbered(Edit(out.toString(), cursor, selectionEnd))
    }

    /**
     * Bold/italic/strikethrough/code around the selection: removes [marker] if it's already around it, else adds it.
     * With nothing selected, inserts the pair and puts the cursor between.
     */
    fun toggleInline(text: String, from: Int, to: Int, marker: String): Edit {
        val a = minOf(from, to)
        val b = maxOf(from, to)
        val m = marker.length
        if (a >= m && b + m <= text.length && text.substring(a - m, a) == marker && text.substring(b, b + m) == marker) {
            return Edit(text.removeRange(b, b + m).removeRange(a - m, a), a - m, b - m)
        }
        val selected = text.substring(a, b)
        if (selected.length >= 2 * m && selected.startsWith(marker) && selected.endsWith(marker)) {
            return Edit(text.substring(0, a) + selected.substring(m, selected.length - m) + text.substring(b), a, b - 2 * m)
        }
        return Edit(text.substring(0, a) + marker + selected + marker + text.substring(b), a + m, b + m)
    }

    /** `[selection](url)`; with nothing selected the URL is also the text. */
    fun link(text: String, from: Int, to: Int, url: String): Edit {
        val a = minOf(from, to)
        val b = maxOf(from, to)
        val label = text.substring(a, b).ifEmpty { url }
        val target = if (url.any { it == ' ' || it == '(' || it == ')' }) "<$url>" else url
        val inserted = "[$label]($target)"
        return Edit(text.substring(0, a) + inserted + text.substring(b), a + inserted.length)
    }

    // Tables (R30) ----------------------------------------------------------------------------------------------------

    private fun tableOf(lines: List<NoteMarkdown.Line>, line: NoteMarkdown.Line): List<NoteMarkdown.Line> {
        var a = line.index
        var b = line.index
        fun inTable(l: NoteMarkdown.Line?) = l != null && (l.kind == Kind.TABLE_ROW || l.kind == Kind.TABLE_RULE)
        while (inTable(lines.getOrNull(a - 1))) a--
        while (inTable(lines.getOrNull(b + 1))) b++
        return lines.subList(a, b + 1)
    }

    /** Which cell of [row] [offset] is in (unescaped pipes before it). */
    private fun column(text: String, row: NoteMarkdown.Line, offset: Int): Int {
        var pipes = 0
        var i = row.start
        while (i < minOf(offset, row.end)) {
            when (text[i]) { '\\' -> i++; '|' -> pipes++ }
            i++
        }
        val leading = text.substring(row.start, row.end).trimStart().startsWith("|")
        val columns = NoteMarkdown.cells(text, row).size
        return (pipes - if (leading) 1 else 0).coerceIn(0, (columns - 1).coerceAtLeast(0))
    }

    /** Where to put the cursor for cell [col] of [row]: the end of its text, or between the two spaces of an empty cell. */
    private fun cellCursor(text: String, row: NoteMarkdown.Line, col: Int): Int {
        val cell = NoteMarkdown.cells(text, row).let { it.getOrNull(col) ?: it.lastOrNull() } ?: return row.end
        return if (cell.isEmpty()) cell.first else cell.last + 1
    }

    private fun cellTexts(text: String, row: NoteMarkdown.Line): MutableList<String> =
        NoteMarkdown.cells(text, row).map { if (it.isEmpty()) "" else text.substring(it.first, it.last + 1) }.toMutableList()

    private fun rowText(cells: List<String>) = "| " + cells.joinToString(" | ") + " |"
    private fun ruleText(columns: Int) = "| " + List(columns) { "---" }.joinToString(" | ") + " |"

    /** Return in a table: the same column of the next row, adding a row at the end. */
    private fun nextRow(text: String, lines: List<NoteMarkdown.Line>, row: NoteMarkdown.Line, cursor: Int): Edit {
        val col = if (row.kind == Kind.TABLE_ROW) column(text, row, cursor) else 0
        val table = tableOf(lines, row)
        val next = table.firstOrNull { it.index > row.index && it.kind == Kind.TABLE_ROW }
        if (next != null) return Edit(text, cellCursor(text, next, col))
        val columns = cellTexts(text, table.first()).size.coerceAtLeast(1)
        val added = "\n" + rowText(List(columns) { "" })
        val out = text.substring(0, row.end) + added + text.substring(row.end)
        val newRow = NoteMarkdown.lines(out)[row.index + 1]
        return Edit(out, cellCursor(out, newRow, col))
    }

    /** The cursor position for cell [col] of the table row at line [lineIndex] (taps on the drawn grid). */
    fun cellCursorAt(text: String, lineIndex: Int, col: Int): Int? =
        NoteMarkdown.lines(text).getOrNull(lineIndex)?.takeIf { it.kind == Kind.TABLE_ROW }?.let { cellCursor(text, it, col) }

    /** Tab in a table: the next cell, then the first cell of the next row (adding a row at the end). */
    private fun nextCell(text: String, lines: List<NoteMarkdown.Line>, row: NoteMarkdown.Line, cursor: Int): Edit {
        val col = column(text, row, cursor)
        if (col + 1 < NoteMarkdown.cells(text, row).size) return Edit(text, cellCursor(text, row, col + 1))
        val next = nextRow(text, lines, row, cursor)
        val newRow = NoteMarkdown.lines(next.text).lastOrNull { it.start <= next.cursor } ?: return next
        return Edit(next.text, cellCursor(next.text, newRow, 0))
    }

    /** A 2×2 table on its own lines at [cursor], the cursor in the first cell. */
    fun insertTable(text: String, cursor: Int): Edit {
        val at = cursor.coerceIn(0, text.length)
        val before = if (at > 0 && text[at - 1] != '\n') "\n" else ""
        val after = if (at < text.length && text[at] != '\n') "\n" else ""
        val table = rowText(listOf("", "")) + "\n" + ruleText(2) + "\n" + rowText(listOf("", ""))
        val out = text.substring(0, at) + before + table + after + text.substring(at)
        return Edit(out, at + before.length + 2)
    }

    /** Is [cursor] in a table? (The format bar shows the table menu then.) */
    fun inTable(text: String, cursor: Int): Boolean = NoteMarkdown.lines(text).lastOrNull { it.start <= cursor }
        ?.let { it.kind == Kind.TABLE_ROW || it.kind == Kind.TABLE_RULE } == true

    /** Rewrites the table at [cursor]: [change] gets the rows' cells (rule row left out) and the cursor's (row, column). */
    private fun editTable(text: String, cursor: Int, change: (rows: MutableList<MutableList<String>>, row: Int, col: Int) -> Pair<Int, Int>): Edit {
        val lines = NoteMarkdown.lines(text)
        val here = lines.lastOrNull { it.start <= cursor }?.takeIf { it.kind == Kind.TABLE_ROW || it.kind == Kind.TABLE_RULE } ?: return Edit(text, cursor)
        val table = tableOf(lines, here)
        val rows = table.filter { it.kind == Kind.TABLE_ROW }
        val cells = rows.map { cellTexts(text, it) }.toMutableList()
        val row = rows.indexOfFirst { it.index == here.index }.coerceAtLeast(0)
        val col = if (here.kind == Kind.TABLE_ROW) column(text, here, cursor) else 0
        val (toRow, toCol) = change(cells, row, col)
        if (cells.isEmpty() || cells.all { it.isEmpty() }) {
            // Last row or column gone: the table is removed.
            val end = minOf(table.last().end + 1, text.length)
            return Edit(text.removeRange(table.first().start, end), table.first().start)
        }
        val columns = cells.maxOf { it.size }
        cells.forEach { r -> while (r.size < columns) r += "" }
        val block = (listOf(rowText(cells[0]), ruleText(columns)) + cells.drop(1).map { rowText(it) }).joinToString("\n")
        val out = text.substring(0, table.first().start) + block + text.substring(table.last().end)
        val newLines = NoteMarkdown.lines(out)
        val newRows = newLines.filter { it.index >= table.first().index && it.index < table.first().index + cells.size + 1 && it.kind == Kind.TABLE_ROW }
        val target = newRows.getOrNull(toRow.coerceIn(0, newRows.lastIndex)) ?: return Edit(out, table.first().start)
        return Edit(out, cellCursor(out, target, toCol.coerceIn(0, columns - 1)))
    }

    fun addRow(text: String, cursor: Int) = editTable(text, cursor) { rows, row, col ->
        rows.add(row + 1, MutableList(rows[row].size) { "" }); (row + 1) to col
    }

    fun addColumn(text: String, cursor: Int) = editTable(text, cursor) { rows, row, col ->
        rows.forEach { it.add(minOf(col + 1, it.size), "") }; row to (col + 1)
    }

    fun deleteRow(text: String, cursor: Int) = editTable(text, cursor) { rows, row, col ->
        rows.removeAt(row); minOf(row, rows.size - 1) to col
    }

    fun deleteColumn(text: String, cursor: Int) = editTable(text, cursor) { rows, row, col ->
        rows.forEach { if (col < it.size) it.removeAt(col) }
        if (rows.all { it.isEmpty() }) rows.clear()
        row to maxOf(0, col - 1)
    }

    /** Turns the line at [cursor] into a checklist item, or back into plain text (kept for the older toolbar button). */
    fun toggleItem(text: String, cursor: Int): Edit = setBlock(text, cursor, cursor, Kind.CHECK)
}
