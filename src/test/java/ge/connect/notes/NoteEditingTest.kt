/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteEditingTest {
    private fun edit(old: String, oldCursor: Int, new: String, newCursor: Int) = NoteEditing.apply(old, oldCursor, new, newCursor)

    @Test fun enterContinuesList() {
        val old = "Shop\n- [ ] milk"
        assertEquals(NoteEditing.Edit("Shop\n- [ ] milk\n- [ ] ", 22), edit(old, old.length, "$old\n", old.length + 1))
    }

    @Test fun enterOnEmptyItemEndsList() {
        val old = "Shop\n- [ ] milk\n- [ ] "
        assertEquals(NoteEditing.Edit("Shop\n- [ ] milk\n", 16), edit(old, old.length, "$old\n", old.length + 1))
    }

    @Test fun backspaceRemovesBox() {
        val old = "- [ ] milk"
        assertEquals(NoteEditing.Edit("milk", 0), edit(old, 6, "- [ ]milk", 5))
    }

    @Test fun backspaceAtItemStartJoinsWithoutBox() {
        val old = "Shop\n- [ ] milk"
        assertEquals(NoteEditing.Edit("Shopmilk", 4), edit(old, 5, "Shop- [ ] milk", 4))
    }

    @Test fun typingInsideBoxGoesAfter() {
        val old = "- [ ] milk"
        assertEquals(NoteEditing.Edit("- [ ] Xmilk", 7), edit(old, 3, "- [X ] milk", 4))
    }

    @Test fun cursorSnapsOutOfBox() = assertEquals(6, NoteEditing.snap("- [ ] milk", 2))

    @Test fun plainTyping() = assertEquals(NoteEditing.Edit("ab", 2), edit("a", 1, "ab", 2))

    @Test fun toggleItem() {
        assertEquals(NoteEditing.Edit("a\n- [ ] b", 9), NoteEditing.toggleItem("a\nb", 3))
        assertEquals(NoteEditing.Edit("a\nb", 3), NoteEditing.toggleItem("a\n- [ ] b", 9))
    }

    @Test fun checklistToggle() = assertEquals("x\n- [x] b", Checklist.toggle("x\n- [ ] b", 1))
}

class NoteEditingR30Test {
    private fun edit(old: String, oldCursor: Int, new: String, newCursor: Int) = NoteEditing.apply(old, oldCursor, new, newCursor)

    @Test fun numbersCountUp() {
        val old = "1. a"
        assertEquals(NoteEditing.Edit("1. a\n2. ", 8), edit(old, 4, "1. a\n", 5))
    }

    @Test fun insertingRenumbersTheRest() {
        val old = "1. a\n2. b"
        // Return after "a" inserts "2. " and the old 2 becomes 3.
        assertEquals(NoteEditing.Edit("1. a\n2. \n3. b", 8), edit(old, 4, "1. a\n\n2. b", 5))
    }

    @Test fun emptyNestedItemOutdentsFirst() {
        val old = "- a\n\t- "
        assertEquals(NoteEditing.Edit("- a\n- ", 6), edit(old, old.length, "$old\n", old.length + 1))
    }

    @Test fun bulletsContinueWithTabs() {
        val old = "\t- a"
        assertEquals(NoteEditing.Edit("\t- a\n\t- ", 8), edit(old, 4, "$old\n", 5))
    }

    @Test fun backspaceOnNestedMarkerOutdents() {
        val old = "\t- a"
        assertEquals(NoteEditing.Edit("- a", 2), edit(old, 3, "\t-a", 2))
    }

    @Test fun inlineToggle() {
        assertEquals(NoteEditing.Edit("a **b** c", 4, 5), NoteEditing.toggleInline("a b c", 2, 3, "**"))
        assertEquals(NoteEditing.Edit("a b c", 2, 3), NoteEditing.toggleInline("a **b** c", 4, 5, "**"))
        assertEquals(NoteEditing.Edit("****", 2, 2), NoteEditing.toggleInline("", 0, 0, "**"))
    }

    @Test fun headingAndBack() {
        assertEquals(NoteEditing.Edit("## Hi", 5), NoteEditing.setBlock("Hi", 2, 2, NoteMarkdown.Kind.HEADING))
        assertEquals(NoteEditing.Edit("Hi", 2), NoteEditing.setBlock("## Hi", 5, 5, NoteMarkdown.Kind.HEADING))
    }

    @Test fun bulletToChecklistKeepsNesting() =
        assertEquals("\t- [ ] a", NoteEditing.setBlock("\t- a", 3, 3, NoteMarkdown.Kind.CHECK).text)

    @Test fun indentOutdent() {
        assertEquals(NoteEditing.Edit("\t- a", 4), NoteEditing.indent("- a", 3, 3, outdent = false))
        assertEquals(NoteEditing.Edit("- a", 3), NoteEditing.indent("\t- a", 4, 4, outdent = true))
    }

    @Test fun codeFence() = assertEquals("```\nx\n```", NoteEditing.toggleCode("x", 0, 0).text)

    @Test fun link() = assertEquals(NoteEditing.Edit("[site](https://a.b)", 19), NoteEditing.link("site", 0, 4, "https://a.b"))

    @Test fun nestedChecklistToggle() = assertEquals("\t- [x] a", Checklist.toggle("\t- [ ] a", 0))

    @Test fun noCheckboxInCode() = assertEquals("```\n- [ ] a\n```", Checklist.toggle("```\n- [ ] a\n```", 1))
}

class NoteMarkdownTest {
    @Test fun kinds() {
        val lines = NoteMarkdown.lines("# T\n## H\n- a\n\t1. b\n> q\n```\n- c\n```\n- [x] d\nplain")
        assertEquals(
            listOf("TITLE", "HEADING", "BULLET", "NUMBER", "QUOTE", "FENCE", "CODE", "FENCE", "CHECK", "BODY"),
            lines.map { it.kind.name },
        )
        assertEquals(1, lines[3].level)
        assertEquals(true, lines[8].checked)
    }

    @Test fun inline() {
        val text = "a **b** *c* ~~d~~ `e` [f](https://g.h) https://x.y"
        val styles = NoteMarkdown.spans(text, 0, text.length).map { it.style.name to text.substring(it.contentStart, it.contentEnd) }
        assertEquals(
            listOf("BOLD" to "b", "ITALIC" to "c", "STRIKE" to "d", "CODE" to "e", "LINK" to "f", "LINK" to "https://x.y"),
            styles,
        )
    }

    @Test fun plain() {
        assertEquals("Shopping list", NoteMarkdown.plain("# **Shopping** list"))
        assertEquals("milk", NoteMarkdown.plain("\t- [ ] milk"))
        assertEquals("a*b", NoteMarkdown.plain("a\\*b"))
    }

    @Test fun title() = assertEquals("Hello world", Note("i", "r", "\n# Hello *world*\nbody", false, 0, 0).title)
}

class NoteTablesTest {
    private val table = "| a | b |\n| --- | --- |\n| c | d |"

    @Test fun parse() {
        val lines = NoteMarkdown.lines(table)
        assertEquals(listOf("TABLE_ROW", "TABLE_RULE", "TABLE_ROW"), lines.map { it.kind.name })
        assertEquals(listOf("c", "d"), NoteMarkdown.cells(table, lines[2]).map { table.substring(it.first, it.last + 1) })
        assertEquals("a  │  b", NoteMarkdown.preview("Title\n$table")[0].text)
    }

    @Test fun escapedPipe() {
        val t = "| a \\| b | c |\n| --- | --- |"
        val cells = NoteMarkdown.cells(t, NoteMarkdown.lines(t)[0])
        assertEquals(2, cells.size)
        assertEquals("a | b", NoteMarkdown.cellText(t, cells[0]))
    }

    @Test fun returnMovesDown() {
        // Cursor after "a" (offset 3); Return goes to the end of "c" in the next row (rule row skipped).
        val r = NoteEditing.apply(table, 3, table.substring(0, 3) + "\n" + table.substring(3), 4)
        assertEquals(table, r.text)
        assertEquals(table.indexOf("c") + 1, r.cursor)
    }

    @Test fun returnInLastRowAddsRow() {
        val at = table.length - 2 // after "d"
        val r = NoteEditing.apply(table, at, table.substring(0, at) + "\n" + table.substring(at), at + 1)
        assertEquals("$table\n|  |  |", r.text)
        assertEquals(r.text.length - 2, r.cursor) // second cell, between its spaces
    }

    @Test fun pipeIsEscaped() {
        val r = NoteEditing.apply(table, 3, table.substring(0, 3) + "|" + table.substring(3), 4)
        assertEquals("| a\\| | b |", r.text.lines()[0])
    }

    @Test fun insertAndColumns() {
        val t = NoteEditing.insertTable("x", 1)
        assertEquals("x\n|  |  |\n| --- | --- |\n|  |  |", t.text)
        assertEquals("| a |  | b |\n| --- | --- | --- |\n| c |  | d |", NoteEditing.addColumn(table, 3).text)
        assertEquals("| b |\n| --- |\n| d |", NoteEditing.deleteColumn(table, 3).text)
        assertEquals("| a | b |\n| --- | --- |\n|  |  |\n| c | d |", NoteEditing.addRow(table, 3).text)
        assertEquals("| c | d |\n| --- | --- |", NoteEditing.deleteRow(table, 3).text)
    }
}

class NoteTabTest {
    private val table = "| a | b |\n| --- | --- |\n| c | d |"

    @Test fun tabMovesToNextCell() {
        val r = NoteEditing.apply(table, 3, table.substring(0, 3) + "\t" + table.substring(3), 4)
        assertEquals(table, r.text)
        assertEquals(table.indexOf("b") + 1, r.cursor)
    }

    @Test fun tabNestsListItem() = assertEquals("\t- a", NoteEditing.apply("- a", 3, "- a\t", 4).text)
}
