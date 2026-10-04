/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.sync

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the Mac reports about itself and about the actions we ask for (PROTOCOL.md (janus-mac docs) → Mac actions,
 * R19): muted / finding / locked, whether it went to sleep, and per-action progress for the Control card.
 */
object MacState {
    enum class Muted { ON, OFF, NA, UNKNOWN }
    enum class Progress { WORKING, DONE, FAILED }

    data class State(
        val muted: Muted = Muted.UNKNOWN,
        val finding: Boolean = false,
        val locked: Boolean = false,
        /** Set when the Mac confirms "sleep"; cleared by its next state report (it sends one when it reconnects). */
        val asleep: Boolean = false,
        /** Action key without "ge-" → progress; DONE/FAILED clear themselves after a moment. */
        val progress: Map<String, Progress> = emptyMap(),
    )

    /**
     * Snackbar messages for the screen showing that computer. [wasOn]: for the toggles (mute, findmac), whether
     * it was on when pressed, so the message doesn't depend on the order of the result and the state report.
     */
    data class Event(val deviceId: String, val action: String, val ok: Boolean, val reason: String?, val wasOn: Boolean)

    private const val TIMEOUT_MILLIS = 5_000L
    private const val SHOW_RESULT_MILLIS = 1_500L
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()
    private val wasOnWhenPressed = HashMap<String, Boolean>()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun start(context: Context) {
        app = context.applicationContext
    }

    fun of(deviceId: String): State = _states.value[deviceId] ?: State()

    /** The phone just asked the Mac to run an action (key with or without "ge-"). */
    fun started(deviceId: String, key: String) {
        val action = key.removePrefix("ge-")
        of(deviceId).let { wasOnWhenPressed["$deviceId/$action"] = if (action == "mute") it.muted == Muted.ON else it.finding }
        change(deviceId) { it.copy(progress = it.progress + (action to Progress.WORKING)) }
        main.postDelayed({
            if (of(deviceId).progress[action] == Progress.WORKING) finish(deviceId, action, ok = false, reason = null)
        }, TIMEOUT_MILLIS)
    }

    /** "macstate:muted=…;finding=…;locked=…" and "macaction:<id>:ok|failed:<reason>". @return handled */
    fun handleCommand(deviceId: String, command: String): Boolean {
        when {
            command.startsWith("macstate:") -> {
                val values = command.removePrefix("macstate:").split(';').mapNotNull { part ->
                    part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                }.toMap()
                // R28: what the Mac is, so its icon doesn't depend on its name. Remembered for when it's away.
                if (::app.isInitialized) MacInfo.update(app, deviceId, kind = values["kind"], model = values["model"], market = values["market"])
                change(deviceId) { state ->
                    state.copy(
                        muted = when (values["muted"]) { "on" -> Muted.ON; "off" -> Muted.OFF; "na" -> Muted.NA; else -> state.muted },
                        finding = values["finding"]?.let { it == "on" } ?: state.finding,
                        locked = values["locked"]?.let { it == "on" } ?: state.locked,
                        asleep = false,
                    )
                }
            }
            command.startsWith("macaction:") -> {
                val parts = command.removePrefix("macaction:").split(':', limit = 3)
                val action = parts.getOrNull(0) ?: return false
                val ok = parts.getOrNull(1) == "ok"
                finish(deviceId, action, ok, reason = parts.getOrNull(2))
                if (ok && action == "sleep") change(deviceId) { it.copy(asleep = true) }
            }
            else -> return false
        }
        return true
    }

    private fun finish(deviceId: String, action: String, ok: Boolean, reason: String?) {
        val result = if (ok) Progress.DONE else Progress.FAILED
        change(deviceId) { it.copy(progress = it.progress + (action to result)) }
        val wasOn = wasOnWhenPressed.remove("$deviceId/$action")
            ?: of(deviceId).let { if (action == "mute") it.muted == Muted.ON else it.finding }
        _events.tryEmit(Event(deviceId, action, ok, reason, wasOn))
        main.postDelayed({
            change(deviceId) { state -> if (state.progress[action] == result) state.copy(progress = state.progress - action) else state }
        }, SHOW_RESULT_MILLIS)
    }

    private fun change(deviceId: String, update: (State) -> State) {
        _states.update { it + (deviceId to update(it[deviceId] ?: State())) }
        ge.connect.widget.MacWidgetUpdates.request() // the widget shows the same states
        // The Lock Mac tile shows the lock state and progress.
        if (::app.isInitialized) {
            runCatching { TileService.requestListeningState(app, ComponentName(app, "ge.connect.tiles.LockMacTileService")) }
        }
    }
}

/** What a Mac said it is in `ge.connect.macstate` (R28): `kind` laptop|desktop, `model` (Mac15,3), `market` (MacBook Pro). */
object MacInfo {
    private const val PREFS = "ge_connect_mac_info"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun update(context: Context, deviceId: String, kind: String?, model: String?, market: String?) {
        if (kind == null && model == null && market == null) return
        prefs(context).edit().apply {
            kind?.let { putString("$deviceId/kind", it) }
            model?.let { putString("$deviceId/model", it) }
            market?.let { putString("$deviceId/market", it) }
        }.apply()
    }

    fun kind(context: Context, deviceId: String): String? = prefs(context).getString("$deviceId/kind", null)
    fun market(context: Context, deviceId: String): String? = prefs(context).getString("$deviceId/market", null)
}

