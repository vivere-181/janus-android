/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.sync

import android.content.Context
import android.util.Log
import androidx.core.content.edit

/**
 * Decides whether the Mac's clipboard goes on the phone's, so Android's "Copied" pop-up only appears when the owner
 * copied something on the Mac. The Mac's engine forwards every change of the Mac's clipboard, including ones nobody
 * made there by hand, and on every reconnection it sends the clipboard again. Left out:
 * - empty text (the Mac clearing its clipboard, e.g. an auto-copied code after 2 minutes);
 * - what the phone just sent to the Mac (shared text, the clipboard, verification codes in forwarded notifications),
 *   coming back because the Mac put it on its clipboard;
 * - what's already on the phone's clipboard from the Mac;
 * - on reconnection: anything but a copy made in the last few minutes that hasn't been applied yet (also across
 *   restarts of the app).
 */
object ClipboardGuard {
    private const val TAG = "ClipboardGuard"
    private const val PREFS = "ge_connect_clipboard_guard"
    private const val KEY_HASH = "last_hash"
    private const val KEY_TIME = "last_time"
    private const val ECHO_MILLIS = 5 * 60_000L
    private const val RECONNECT_MAX_AGE_MILLIS = 3 * 60_000L
    private const val MAX_SENT = 100

    /** Text the phone sent to the Mac lately: hash → when. */
    private val sent = LinkedHashMap<Int, Long>()
    private val CODE = Regex("""(?<![\p{L}\p{N}])(?=[\p{L}\p{N}-]*\d)[\p{L}\p{N}-]{4,10}(?![\p{L}\p{N}])""")

    private fun key(text: String) = text.trim().hashCode()

    /** The phone sent [text] to the Mac (shared text, its clipboard). */
    fun sent(text: String?) {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return
        synchronized(sent) {
            sent.remove(key(value))
            sent[key(value)] = System.currentTimeMillis()
            while (sent.size > MAX_SENT) sent.remove(sent.keys.first())
        }
    }

    /** A notification went to the Mac: its codes may come back if the Mac copies them (its "Copy Code"). */
    fun sentNotification(vararg texts: String?) {
        texts.filterNotNull().forEach { text -> CODE.findAll(text).forEach { sent(it.value) } }
    }

    private fun sentLately(text: String): Boolean = synchronized(sent) {
        val time = sent[key(text)] ?: return false
        System.currentTimeMillis() - time < ECHO_MILLIS
    }

    /**
     * Whether the Mac's clipboard [text] should go on the phone's.
     * @param reconnect from the packet the Mac sends when it (re)connects, copied at [copiedAt] (0 = unknown)
     * @param current what's on the phone's clipboard as far as the app knows
     */
    fun accept(context: Context, text: String?, current: String?, reconnect: Boolean, copiedAt: Long = 0): Boolean {
        val value = text?.trim().orEmpty()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val reason = when {
            value.isEmpty() -> "empty"
            current?.trim() == value -> "already on the clipboard"
            sentLately(value) -> "sent from this phone"
            reconnect && copiedAt <= 0L -> "reconnection, copy time unknown"
            reconnect && System.currentTimeMillis() - copiedAt > RECONNECT_MAX_AGE_MILLIS -> "reconnection, old copy"
            reconnect && (prefs.getInt(KEY_HASH, 0) == key(value) || copiedAt <= prefs.getLong(KEY_TIME, 0)) -> "reconnection, already applied"
            else -> null
        }
        if (reason != null) {
            Log.i(TAG, "Mac clipboard (${value.length} chars${if (reconnect) ", on connect" else ""}) not applied: $reason")
            return false
        }
        prefs.edit { putInt(KEY_HASH, key(value)); putLong(KEY_TIME, if (copiedAt > 0) copiedAt else System.currentTimeMillis()) }
        Log.i(TAG, "Mac clipboard (${value.length} chars${if (reconnect) ", on connect" else ""}) applied")
        return true
    }
}
