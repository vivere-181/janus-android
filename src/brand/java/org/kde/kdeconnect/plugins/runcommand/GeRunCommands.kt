/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

// ge.connect: lives in upstream's package to reach RunCommandPlugin's package-private callback API.
package org.kde.kdeconnect.plugins.runcommand

/** Commands the computer publishes, as (key, name) pairs, plus change notifications. */
object GeRunCommands {
    fun commands(plugin: RunCommandPlugin): List<Pair<String, String>> =
        plugin.commandList.mapNotNull { o -> runCatching { o.getString("key") to o.getString("name") }.getOrNull() }

    /** @return a function that stops listening */
    fun listen(plugin: RunCommandPlugin, onChange: () -> Unit): () -> Unit {
        val callback = RunCommandPlugin.CommandsChangedCallback { onChange() }
        plugin.addCommandsUpdatedCallback(callback)
        return { plugin.removeCommandsUpdatedCallback(callback) }
    }
}
