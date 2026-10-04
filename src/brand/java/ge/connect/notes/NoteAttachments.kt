/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.notes

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Files in notes (PROTOCOL.md (janus-mac docs) → Rich notes, R30): a line `![name](attachments/<sha256>.<ext>)`, the file stored
 * once per hash in files/attachments. Missing files are asked for (`want`); asked-for files go one at a time as a `file`
 * op followed by a plain share packet named `<sha>.<ext>` (no transfer card or notification here); the receiver checks
 * the hash and answers `have`.
 */
object NoteAttachments {
    private const val TAG = "NoteAttachments"
    const val MAX_BYTES = 50L * 1024 * 1024
    private const val EXPECT_MILLIS = 10 * 60_000L
    private const val NEXT_FILE_TIMEOUT_MILLIS = 3 * 60_000L
    private const val KEEP_UNREFERENCED_MILLIS = 30L * 24 * 3_600_000L

    private val NAME = Regex("""^[0-9a-f]{64}\.[a-z0-9]{1,8}$""")
    /** `![name](attachments/<sha>.<ext>)` alone on a line (name: `\` and `]` escaped). */
    val LINE = NoteMarkdown.ATTACHMENT
    private val IMAGES = setOf("png", "jpg", "jpeg", "heic", "heif", "gif", "webp")

    private lateinit var app: Application
    private lateinit var dir: File
    private val worker by lazy { Handler(HandlerThread(TAG).apply { start() }.looper) }

    /** Names announced by a `file` op → when (so the share that follows is filed here). Guarded by itself. */
    private val expected = HashMap<String, Long>()
    /** Per peer: files they asked for, sent one at a time. Worker thread only. */
    private val queues = HashMap<String, ArrayDeque<String>>()
    private val sending = HashMap<String, String>()

    private val _changed = MutableStateFlow(0)
    /** Bumped when a file arrives, so editors and previews redraw. */
    val changed: StateFlow<Int> = _changed.asStateFlow()

    fun start(app: Application) {
        this.app = app
        dir = File(app.filesDir, "attachments").apply { mkdirs() }
        worker.post(::cleanUp)
    }

    fun file(name: String) = File(dir, name)
    fun has(name: String) = NAME.matches(name) && file(name).exists()
    fun isImage(name: String) = name.substringAfterLast('.') in IMAGES
    fun isPdf(name: String) = name.endsWith(".pdf")

    /** The attachment on [line], as (display name, stored name), or null. */
    fun parse(line: String): Pair<String, String>? = LINE.find(line)?.let { m ->
        m.groupValues[1].replace(Regex("""\\(.)"""), "$1") to m.groupValues[2]
    }

    /** Every stored name the notes refer to (Recently Deleted included). */
    fun referenced(notes: List<Note> = NotesStore.all.value): Set<String> =
        notes.flatMap { note -> note.text.lineSequence().mapNotNull { parse(it)?.second }.toList() }.toSet()

    fun missing(): List<String> = referenced().filter { !file(it).exists() }

    fun contentUri(context: Context, name: String): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file(name))

    fun mimeType(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.')) ?: "application/octet-stream"

    /**
     * Copies [uri] into the store. @return the Markdown line for it, or null (unreadable, or over 50 MB).
     * Blocks while copying; call off the main thread.
     */
    fun add(context: Context, uri: Uri): String? = runCatching {
        val resolver = context.contentResolver
        var displayName = "file"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { displayName = it }
        }
        val ext = (displayName.substringAfterLast('.', "").takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) }
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri) ?: "")
            ?: "bin").lowercase()
        val tmp = File(dir, "incoming-${System.nanoTime()}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        resolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    if (size > MAX_BYTES) { tmp.delete(); return null }
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
            }
        }
        val name = digest.digest().joinToString("") { "%02x".format(it) } + "." + ext
        val target = file(name)
        if (target.exists()) tmp.delete() else tmp.renameTo(target)
        _changed.value++
        line(displayName, name)
    }.onFailure { Log.w(TAG, "Couldn't add $uri", it) }.getOrNull()

    fun line(displayName: String, name: String): String {
        val clean = displayName.replace('\n', ' ').replace("\\", "\\\\").replace("]", "\\]")
        return "![$clean](attachments/$name)"
    }

    // Sync -----------------------------------------------------------------------------------------------------------

    /** Ask [device] for the files our notes need but we don't have. */
    fun requestMissing(device: Device) = worker.post {
        val missing = missing()
        if (missing.isNotEmpty()) NotesSync.sendOp(device, "want", missing)
    }

    /** `want` from [device]: queue what we have, send one at a time. */
    fun wanted(device: Device, names: List<String>) = worker.post {
        val queue = queues.getOrPut(device.deviceId) { ArrayDeque() }
        names.filter { has(it) && it !in queue && sending[device.deviceId] != it }.forEach { queue.addLast(it) }
        if (sending[device.deviceId] == null) sendNext(device)
    }

    /** `have` from [device]: that file arrived; send the next. */
    fun had(device: Device, names: List<String>) = worker.post {
        if (sending[device.deviceId] in names) {
            sending.remove(device.deviceId)
            worker.removeCallbacksAndMessages(device.deviceId)
            sendNext(device)
        }
    }

    private fun sendNext(device: Device) {
        val name = queues[device.deviceId]?.removeFirstOrNull() ?: return
        val file = file(name)
        if (!file.exists()) return sendNext(device)
        sending[device.deviceId] = name
        NotesSync.sendFileOp(device, name, file.length())
        val np = NetworkPacket("kdeconnect.share.request").apply {
            this["filename"] = name
            this["lastModified"] = file.lastModified()
            payload = NetworkPacket.Payload(FileInputStream(file), file.length())
        }
        val sent = device.sendPacketBlocking(np, object : Device.SendPacketStatusCallback() {
            override fun onSuccess() {}
            override fun onFailure(e: Throwable) { Log.w(TAG, "Sending $name failed: ${e.message}") }
        }, true)
        Log.i(TAG, "Sent attachment $name to ${device.name}: $sent")
        // If no `have` comes back, move on after a while anyway (it's asked for again at the next hello).
        androidx.core.os.HandlerCompat.postDelayed(worker, {
            if (sending[device.deviceId] == name) { sending.remove(device.deviceId); sendNext(device) }
        }, device.deviceId, NEXT_FILE_TIMEOUT_MILLIS)
    }

    /** `file` op from [device]: the share named [name] that follows is an attachment. */
    fun announced(name: String) { if (NAME.matches(name)) synchronized(expected) { expected[name] = System.currentTimeMillis() } }

    /**
     * An incoming share from one of the owner's devices: if it's an attachment (its name is `<sha>.<ext>` and a `file`
     * op announced it, or a note refers to it), it's stored here instead of Downloads. @return whether it was taken
     */
    fun receive(device: Device, np: NetworkPacket): Boolean {
        val name = np.getStringOrNull("filename") ?: return false
        if (!NAME.matches(name)) return false
        val announced = synchronized(expected) { expected[name]?.let { System.currentTimeMillis() - it < EXPECT_MILLIS } } == true
        if (!announced && name !in referenced()) return false
        val input = np.payload?.inputStream ?: return false
        Thread {
            val tmp = File(dir, "incoming-${System.nanoTime()}.tmp")
            val ok = runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                input.use { stream ->
                    tmp.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                        }
                    }
                }
                np.payload?.close()
                digest.digest().joinToString("") { "%02x".format(it) } == name.substringBefore('.')
            }.getOrDefault(false)
            if (ok) {
                if (!file(name).exists()) tmp.renameTo(file(name)) else tmp.delete()
                synchronized(expected) { expected.remove(name) }
                _changed.value++
                NotesSync.sendOp(device, "have", listOf(name))
                Log.i(TAG, "Received attachment $name from ${device.name}")
            } else {
                tmp.delete()
                Log.w(TAG, "Attachment $name from ${device.name} didn't match its hash; asking again later")
            }
        }.start()
        return true
    }

    /** Files no note refers to for 30 days are removed. */
    private fun cleanUp() {
        val prefs = app.getSharedPreferences("ge_connect_note_attachments", Context.MODE_PRIVATE)
        val referenced = referenced()
        val now = System.currentTimeMillis()
        val edit = prefs.edit()
        dir.listFiles()?.forEach { file ->
            when {
                file.name.endsWith(".tmp") -> if (now - file.lastModified() > 3_600_000L) file.delete()
                file.name in referenced -> edit.remove(file.name)
                else -> {
                    val since = prefs.getLong(file.name, 0L)
                    if (since == 0L) edit.putLong(file.name, now)
                    else if (now - since > KEEP_UNREFERENCED_MILLIS) { file.delete(); edit.remove(file.name) }
                }
            }
        }
        edit.apply()
    }
}
