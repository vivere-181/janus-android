/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * On the TV: things the phone's remote asks for that remote keys can't do (the TV ignores some of them from a remote),
 * opened through the remote protocol's app links (janus://…). No window of its own.
 */
class TvActions : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.data?.host) {
            "inputs" -> inputs()
            "launch" -> intent?.data?.getQueryParameter("package")?.let(::launch)
            "search" -> intent?.data?.getQueryParameter("q")?.takeIf { it.isNotBlank() }?.let(::search)
            // The phone wants Janus here (for the app list): start the connection, it sends the list on connecting.
            "hello" -> runCatching {
                org.kde.kdeconnect.BackgroundService.Start(this)
                org.kde.kdeconnect.BackgroundService.ForceRefreshConnections(this)
            }
        }
        finish()
    }

    /** The TV's list of inputs (HDMI, antenna…): its maker's own if it has one, else Android TV's. */
    private fun inputs() {
        val view = Intent(VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val makers = packageManager.queryIntentActivities(view, 0).map { it.activityInfo.packageName }
        val own = makers.firstOrNull { it != "com.google.android.tvlauncher" && it != "com.google.android.apps.tv.launcherx" }
        runCatching { startActivity(if (own != null) Intent(view).setPackage(own) else view) }
            .onFailure { Log.w("TvActions", "No input list: ${it.message}") }
    }

    /**
     * What was said on the phone, for the TV's assistant as if said to the TV (the TV doesn't take a phone's speech
     * over the remote protocol: its remote service refuses the assistant the audio on some TVs, this TCL among them).
     */
    private fun search(query: String) {
        val intent = Intent("android.speech.action.WEB_SEARCH").putExtra(android.app.SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.recoverCatching {
            startActivity(Intent("android.search.action.GLOBAL_SEARCH").putExtra(android.app.SearchManager.QUERY, query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w("TvActions", "No assistant: ${it.message}") }
    }

    private fun launch(packageName: String) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName) ?: packageManager.getLaunchIntentForPackage(packageName) ?: return
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    companion object {
        fun launchLink(packageName: String) = "janus://launch?package=$packageName"
        const val HELLO = "janus://hello"
        private const val VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS"
        const val INPUTS = "janus://inputs"
        fun searchLink(query: String) = "janus://search?q=" + android.net.Uri.encode(query)
    }
}
