/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.kde.kdeconnect.plugins

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Every class marked @LoadablePlugin is in PluginFactory.PLUGINS and the other way round. */
class PluginListTest {
    @Test
    fun everyPluginIsListed() {
        val marked = listOf(File("src/main/java"), File("src/brand/java")).flatMap { root ->
            root.walk().filter { it.extension == "kt" || it.extension == "java" }.mapNotNull { file ->
                val text = file.readText()
                if (!Regex("""@(PluginFactory\.)?LoadablePlugin\b""").containsMatchIn(text.substringAfter("annotation class LoadablePlugin"))) return@mapNotNull null
                val pkg = Regex("""^package ([\w.]+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) ?: return@mapNotNull null
                // The first class declared after the annotation (other annotations may come in between).
                val after = text.substringAfter("annotation class LoadablePlugin").let { t -> t.substring(Regex("""@(PluginFactory\.)?LoadablePlugin\b""").find(t)!!.range.last) }
                val cls = Regex("""\bclass (\w+)""").find(after)?.groupValues?.get(1) ?: return@mapNotNull null
                "$pkg.$cls"
            }.toList()
        }.toSortedSet()
        val listed = PluginFactory.PLUGINS.map { it.java.name }.toSortedSet()
        assertEquals(marked, listed)
    }
}
