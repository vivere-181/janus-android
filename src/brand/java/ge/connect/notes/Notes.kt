/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.notes

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A note (PROTOCOL.md (janus-mac docs) → ge.connect.notes, R29). The first line is the title; checklists are `- [ ] ` text. */
data class Note(
    val id: String,
    val rev: String,
    val text: String,
    val pinned: Boolean,
    val created: Long,
    val modified: Long,
    val deleted: Long? = null,
) {
    /** The first non-empty line without its Markdown (R30). */
    val title: String get() = NoteMarkdown.titleLine(NoteMarkdown.lines(text))?.let { NoteMarkdown.plain(text.substring(it.start, it.end)) }.orEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("rev", rev); put("text", text); put("pinned", pinned)
        put("created", created); put("modified", modified)
        deleted?.let { put("deleted", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = Note(
            id = o.getString("id"),
            rev = o.getString("rev"),
            text = o.optString("text"),
            pinned = o.optBoolean("pinned"),
            created = o.optLong("created"),
            modified = o.optLong("modified"),
            deleted = if (o.has("deleted") && !o.isNull("deleted")) o.getLong("deleted") else null,
        )

        fun newRev() = UUID.randomUUID().toString()
    }
}

/** Checklist lines (`- [ ] item` / `- [x] item`, optionally nested with tabs; not inside code blocks). */
object Checklist {
    const val UNCHECKED = "- [ ] "
    const val CHECKED = "- [x] "
    const val PREFIX_LENGTH = 6

    /** [text] with line [index]'s box ticked or unticked (no change if it isn't a checklist item). */
    fun toggle(text: String, index: Int): String {
        val line = NoteMarkdown.lines(text).getOrNull(index)?.takeIf { it.kind == NoteMarkdown.Kind.CHECK } ?: return text
        val at = line.markerStart + 3 // "- [x] ": the box character
        return text.substring(0, at) + (if (line.checked) ' ' else 'x') + text.substring(at + 1)
    }
}

/**
 * The notes on this phone (a JSON file; there are few and they're small) and their sync state per peer. All changes go
 * through here; every change gets a new `rev` and tells [NotesSync] so it can push. Thread-safe.
 */
object NotesStore {
    private const val TAG = "NotesStore"
    private const val KEEP_DELETED_MILLIS = 30L * 24 * 3_600_000L

    private lateinit var file: File
    private lateinit var ackFile: File
    private lateinit var hiddenFile: File
    private val lock = Any()
    private val notes = LinkedHashMap<String, Note>()
    /** peer deviceId → (note id → the rev that peer has). */
    private val acked = HashMap<String, HashMap<String, String>>()
    /** Notes deleted for good here ("Delete now") whose tombstone still syncs until it expires. */
    private val hidden = HashSet<String>()
    private val io by lazy { Handler(HandlerThread("NotesStore").apply { start() }.looper) }

    private val _all = MutableStateFlow<List<Note>>(emptyList())
    /** Every note, tombstones included, newest edit first. */
    val all: StateFlow<List<Note>> = _all.asStateFlow()

    fun start(app: Application) {
        file = File(app.filesDir, "notes.json")
        ackFile = File(app.filesDir, "notes_acks.json")
        hiddenFile = File(app.filesDir, "notes_hidden.json")
        synchronized(lock) {
            runCatching {
                if (file.exists()) JSONArray(file.readText()).let { a -> for (i in 0 until a.length()) Note.fromJson(a.getJSONObject(i)).let { notes[it.id] = it } }
                if (ackFile.exists()) JSONObject(ackFile.readText()).let { o ->
                    o.keys().forEach { peer -> acked[peer] = HashMap<String, String>().also { m -> o.getJSONObject(peer).let { p -> p.keys().forEach { m[it] = p.getString(it) } } } }
                }
                if (hiddenFile.exists()) JSONArray(hiddenFile.readText()).let { a -> for (i in 0 until a.length()) hidden += a.getString(i) }
            }.onFailure { Log.e(TAG, "Couldn't read the notes", it) }
            purgeExpired()
            publish()
        }
    }

    fun get(id: String): Note? = synchronized(lock) { notes[id] }

    /** Visible notes: pinned first, then by last edit. */
    fun visible(all: List<Note>) = all.filter { it.deleted == null }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.modified })
    fun recentlyDeleted(all: List<Note>) = all.filter { it.deleted != null && it.id !in synchronized(lock) { hidden } }.sortedByDescending { it.deleted }

    fun create(text: String = ""): Note {
        val now = System.currentTimeMillis()
        val note = Note(UUID.randomUUID().toString(), Note.newRev(), text, pinned = false, created = now, modified = now)
        change { notes[note.id] = note }
        return note
    }

    fun setText(id: String, text: String) = edit(id) { if (it.text == text) null else it.copy(text = text) }
    fun setPinned(id: String, pinned: Boolean) = edit(id) { if (it.pinned == pinned) null else it.copy(pinned = pinned) }
    fun toggleItem(id: String, line: Int) = edit(id) { it.copy(text = Checklist.toggle(it.text, line)) }
    fun delete(id: String) = edit(id) { if (it.deleted != null) null else it.copy(deleted = System.currentTimeMillis()) }
    fun restore(id: String) = edit(id) { if (it.deleted == null) null else it.copy(deleted = null) }

    /** An empty note isn't worth keeping (left without typing anything): dropped if no peer has it yet. */
    fun discardIfEmpty(id: String) = synchronized(lock) {
        val note = notes[id] ?: return
        if (note.text.isNotBlank() || note.deleted != null) return
        if (acked.values.any { it.containsKey(id) }) delete(id) else change { notes.remove(id) }
    }

    /** "Delete now": hidden here at once; the tombstone keeps syncing until it expires so peers don't bring it back. */
    fun deleteNow(id: String) = synchronized(lock) {
        val note = notes[id] ?: return
        if (note.deleted == null) delete(id)
        change { hidden += id }
    }

    private fun edit(id: String, update: (Note) -> Note?) = synchronized(lock) {
        val note = notes[id] ?: return
        val changed = update(note) ?: return
        change { notes[id] = changed.copy(rev = Note.newRev(), modified = System.currentTimeMillis()) }
    }

    private fun change(block: () -> Unit) {
        synchronized(lock) { block(); publish(); save() }
        NotesSync.localChange()
    }

    private fun publish() {
        _all.value = notes.values.sortedByDescending { it.modified }
        NotesWidget.requestUpdate()
    }

    private fun save() {
        val notesJson = JSONArray(notes.values.map { it.toJson() }).toString()
        val acksJson = JSONObject(acked.mapValues { JSONObject(it.value as Map<*, *>) }).toString()
        val hiddenJson = JSONArray(hidden.toList()).toString()
        io.post {
            runCatching {
                writeAtomically(file, notesJson)
                writeAtomically(ackFile, acksJson)
                writeAtomically(hiddenFile, hiddenJson)
            }.onFailure { Log.e(TAG, "Couldn't save the notes", it) }
        }
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(target)
    }

    private fun purgeExpired() {
        val cutoff = System.currentTimeMillis() - KEEP_DELETED_MILLIS
        val expired = notes.values.filter { (it.deleted ?: Long.MAX_VALUE) < cutoff }.map { it.id }
        if (expired.isEmpty()) return
        expired.forEach { id -> notes.remove(id); hidden.remove(id); acked.values.forEach { it.remove(id) } }
        save()
    }

    // Sync (NotesSync) ---------------------------------------------------------------------------------------------

    /** Notes [peer] doesn't have in their current version, with the `base` it has. */
    fun pendingFor(peer: String): List<Pair<Note, String?>> = synchronized(lock) {
        val have = acked[peer].orEmpty()
        notes.values.filter { have[it.id] != it.rev }.map { it to have[it.id] }
    }

    fun ack(peer: String, id: String, rev: String) = synchronized(lock) {
        acked.getOrPut(peer) { HashMap() }[id] = rev
        save()
    }

    /**
     * A note from [peer] (with the `base` it was edited from). Takes it, or resolves a conflict: the later
     * (`modified`, then `rev`) stays under the id, the other is kept as a copy with an id both sides derive the same way.
     * @return whether anything changed here
     */
    fun receive(peer: String, incoming: Note, base: String?): Boolean = synchronized(lock) {
        val local = notes[incoming.id]
        acked.getOrPut(peer) { HashMap() }[incoming.id] = incoming.rev
        val changed = when {
            local == null || local.rev == base -> { notes[incoming.id] = incoming; true }
            local.rev == incoming.rev -> false
            else -> {
                val incomingWins = compareValuesBy(incoming, local, { it.modified }, { it.rev }) > 0
                val (winner, loser) = if (incomingWins) incoming to local else local to incoming
                notes[winner.id] = winner
                if (loser.deleted == null && loser.text != winner.text) {
                    val copyId = UUID.nameUUIDFromBytes("conflict:${loser.id}:${loser.rev}".toByteArray(Charsets.UTF_8)).toString()
                    if (copyId !in notes) notes[copyId] = loser.copy(id = copyId)
                }
                Log.i(TAG, "Conflict on ${incoming.id}: kept ${if (incomingWins) "theirs" else "ours"}")
                true
            }
        }
        if (changed) publish()
        save()
        changed
    }
}
