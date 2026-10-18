/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.cloud

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import ge.connect.transfers.Transfers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.FilesHelper
import org.kde.kdeconnect.helpers.MediaStoreHelper
import org.kde.kdeconnect.plugins.share.GeReceivedNotification
import org.kde.kdeconnect.plugins.share.ShareSettingsFragment
import org.kde.kdeconnect_tp.R
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Files through Janus Cloud (shared/CLOUD.md §6), like Blip: the offer goes out before the upload finishes, the
 * receiver downloads chunk i while the sender uploads i+1, four chunks in flight each way, a failed chunk is retried
 * (resumed mid-chunk with Range), and a download cut off by the app stopping picks up where it was.
 */
object CloudTransfers {
    private const val TAG = "CloudTransfers"
    private const val IN_FLIGHT = 4
    private const val MAX_CHUNK = 16 * 1024 * 1024
    /** A chunk that makes no progress for this long fails its transfer. */
    private const val GIVE_UP_MILLIS = 30 * 60_000L

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Running transfers by blob id, both directions. */
    private val running = ConcurrentHashMap<String, Job>()
    /** Our uploads waiting for the computer's `file-done`: blob → transfers item. */
    private val outgoing = ConcurrentHashMap<String, Long>()
    /** Incoming offers: blob → called once it's handled (saved, gone or cancelled), so the envelope is acknowledged. */
    private val incoming = ConcurrentHashMap<String, () -> Unit>()
    /** Files to one Mac go one after another (the receiver can already be downloading the first). */
    private val sendQueue = Mutex()

    private class Gone : IOException("the file is no longer in the cloud")
    private class Full : IOException("cloud storage is full")

    fun init(context: Context) {
        app = context.applicationContext
        // Leftovers of downloads whose offer never came back (cancelled or expired while we were away).
        CloudStore.tempDir().listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 72 * 3_600_000L }?.forEach { it.delete() }
    }

    fun busy() = running.isNotEmpty()

    // Sending ---------------------------------------------------------------------------------------------------------

    private class Outgoing(val uri: Uri, val name: String, val size: Long, val mime: String, val input: InputStream, val item: Long)

    /**
     * Sends [uris] to [device] through the cloud. Opens the files right away, while the caller still holds the
     * pickers' read permission; call off the main thread.
     */
    fun send(device: Device, uris: List<Uri>) {
        val peer = JanusCloud.peer(device.deviceId) ?: return
        val resolver = app.contentResolver
        val files = uris.mapNotNull { uri ->
            runCatching {
                var name = uri.lastPathSegment ?: "file"
                var size = -1L
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
                val mime = resolver.getType(uri) ?: FilesHelper.getMimeTypeFromFile(name)
                var input = resolver.openInputStream(uri) ?: error("can't open")
                if (size < 0) {
                    // Size unknown: keep a copy, so the chunk count is right.
                    val copy = File(app.cacheDir, "cloud-out-${System.nanoTime()}").apply { deleteOnExit() }
                    input.use { i -> copy.outputStream().use { i.copyTo(it) } }
                    size = copy.length()
                    input = copy.inputStream()
                }
                Outgoing(uri, name, size, mime, input, Transfers.cloudOutgoing(device.deviceId, device.name, name, size, mime, uri))
            }.onFailure { Log.w(TAG, "Couldn't open $uri", it) }.getOrNull()
        }
        if (files.isEmpty()) return
        val batch = scope.launch {
            sendQueue.withLock { files.forEach { upload(peer, it) } }
        }
        val key = "send-${System.nanoTime()}"
        running[key] = batch
        JanusCloud.poke()
        batch.invokeOnCompletion { running.remove(key); JanusCloud.poke() }
    }

    private suspend fun upload(peer: CloudPeer, file: Outgoing) {
        val relay = JanusCloud.relayFor(peer)
        val count = CloudCrypto.chunkCount(file.size)
        var blob: String? = null
        try {
            file.input.use { input ->
                blob = retrying("create") {
                    try {
                        relay.json("POST", "/v1/blobs", JSONObject().put("to", peer.cloudId).put("count", count).put("size", CloudCrypto.encryptedSize(file.size)))
                            .getString("id")
                    } catch (e: RelayException) {
                        if (e.status == 507) throw Full() else throw e
                    }
                }
                val id = blob!!
                val key = CloudCrypto.randomKey()
                retrying("offer") {
                    JanusCloud.send(peer, "file", JSONObject().put("blob", id).put("name", file.name).put("size", file.size).put("mime", file.mime)
                        .put("chunk", CloudCrypto.CHUNK_SIZE).put("count", count).put("key", CloudCrypto.b64(key)))
                }
                outgoing[id] = file.item
                val sent = AtomicLongArray(count)
                val lastReport = AtomicLong(0)
                fun report(force: Boolean = false) {
                    val now = System.currentTimeMillis()
                    if (!force && now - lastReport.get() < 200) return
                    lastReport.set(now)
                    var total = 0L
                    for (i in 0 until count) total += sent.get(i)
                    Transfers.cloudProgress(file.item, total, file.size)
                }
                Transfers.cloudProgress(file.item, 0, file.size)
                val permits = Semaphore(IN_FLIGHT)
                coroutineScope {
                    for (i in 0 until count) {
                        val length = minOf(CloudCrypto.CHUNK_SIZE.toLong(), file.size - i.toLong() * CloudCrypto.CHUNK_SIZE).toInt()
                        permits.acquire()
                        val plain = ByteArray(length)
                        if (readFully(input, plain) != length) { permits.release(); throw IOException("the file got shorter while sending") }
                        val sealed = CloudCrypto.sealChunk(plain, length, key, id, i, count)
                        launch {
                            try {
                                putChunk(relay, id, i, sealed) { written -> sent.set(i, written * length / sealed.size); report() }
                                sent.set(i, length.toLong())
                                report()
                            } finally { permits.release() }
                        }
                    }
                }
                report(force = true)
                Transfers.cloudUploaded(file.item)
                Log.i(TAG, "Uploaded ${file.size} bytes in $count chunk(s) for ${peer.name}")
            }
        } catch (e: CancellationException) {
            Transfers.failed(file.item)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sending ${file.name} through the cloud failed: ${e.message}")
            Transfers.failed(file.item)
            blob?.let { id ->
                outgoing.remove(id)
                runCatching { JanusCloud.send(peer, "file-cancel", JSONObject().put("blob", id)) }
                runCatching { relay.call("DELETE", "/v1/blobs/$id").close() }
            }
            if (e is Full) toast(R.string.cloud_full)
        }
    }

    private suspend fun putChunk(relay: Relay, blob: String, index: Int, sealed: ByteArray, onWritten: (Long) -> Unit) {
        retrying("chunk $index") {
            relay.call("PUT", "/v1/blobs/$blob/$index", Relay.counting(sealed, onWritten)).use { res ->
                when {
                    res.isSuccessful -> Unit
                    res.code == 404 -> throw Gone()
                    else -> throw relay.error(res.code, res.body?.string().orEmpty())
                }
            }
        }
    }

    /** `file-done` from the computer: it has our file. */
    fun delivered(blob: String) {
        outgoing.remove(blob)?.let(Transfers::cloudDelivered)
    }

    /** `file-cancel` from the computer, for one of ours or one it was sending us. */
    fun cancelled(blob: String) {
        outgoing.remove(blob)?.let(Transfers::failed)
        running.remove(blob)?.cancel()
        incoming.remove(blob)?.invoke()
    }

    // Receiving -------------------------------------------------------------------------------------------------------

    /** A `file` offer from [peer]; [handled] acknowledges it once the file is saved, gone or cancelled. */
    fun receive(peer: CloudPeer, offer: JSONObject, handled: () -> Unit) {
        val blob = offer.optString("blob")
        if (running.containsKey(blob)) return // offered again while we're already on it (reconnect)
        val size = offer.optLong("size", -1)
        val chunk = offer.optInt("chunk", CloudCrypto.CHUNK_SIZE)
        val count = offer.optInt("count")
        val key = runCatching { CloudCrypto.unb64(offer.optString("key")) }.getOrNull()
        val valid = Regex("^[0-9a-f]{32}$").matches(blob) && size >= 0 && chunk in 1..MAX_CHUNK && key?.size == 32 &&
            count == maxOf(1, ((size + chunk - 1) / chunk).toInt())
        if (!valid) { Log.w(TAG, "Ignoring a malformed file offer from ${peer.name}"); handled(); return }
        val name = offer.optString("name").substringAfterLast('/').substringAfterLast('\\').trim().trimStart('.').ifEmpty { "file" }
        val mime = offer.optString("mime").ifEmpty { FilesHelper.getMimeTypeFromFile(name) }
        val device = KdeConnect.getInstance().getDevice(peer.kdeId)
        val item = Transfers.cloudIncoming(peer.kdeId, device?.name ?: peer.name, name, size, mime)
        incoming[blob] = handled
        val job = scope.launch {
            val temp = File(CloudStore.tempDir(), "$blob.part")
            val progressFile = File(CloudStore.tempDir(), "$blob.have")
            try {
                download(peer, blob, size, chunk, count, key!!, temp, progressFile, item)
                val uri = save(temp, name)
                Transfers.incomingDone(item, uri, mime)
                device?.let { notifyReceived(it, uri, mime, name) }
                temp.delete(); progressFile.delete()
                val relay = JanusCloud.relayFor(peer)
                runCatching { relay.call("DELETE", "/v1/blobs/$blob").close() }
                runCatching { retrying("done") { JanusCloud.send(peer, "file-done", JSONObject().put("blob", blob)) } }
                Log.i(TAG, "Received $size bytes from ${peer.name} through the cloud")
                incoming.remove(blob)?.invoke()
            } catch (e: CancellationException) {
                Transfers.failed(item)
                temp.delete(); progressFile.delete()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Receiving through the cloud failed: ${e.message}")
                Transfers.failed(item)
                temp.delete(); progressFile.delete()
                if (e !is Gone) runCatching { JanusCloud.send(peer, "file-cancel", JSONObject().put("blob", blob)) }
                incoming.remove(blob)?.invoke()
            }
        }
        running[blob] = job
        JanusCloud.poke()
        job.invokeOnCompletion { running.remove(blob, job); JanusCloud.poke() }
    }

    private suspend fun download(
        peer: CloudPeer, blob: String, size: Long, chunk: Int, count: Int, key: ByteArray, temp: File, progressFile: File, item: Long,
    ) = coroutineScope {
        val relay = JanusCloud.relayFor(peer)
        // Chunks already in from an earlier try (the app stopped mid-download).
        val have = if (temp.exists() && temp.length() == size) progressFile.takeIf { it.exists() }?.readLines()
            ?.mapNotNull { it.toIntOrNull() }?.toMutableSet() ?: mutableSetOf() else mutableSetOf()
        RandomAccessFile(temp, "rw").use { file ->
            file.setLength(size)
            val received = AtomicLong(have.sumOf { minOf(chunk.toLong(), size - it.toLong() * chunk) })
            val lastReport = AtomicLong(0)
            Transfers.cloudProgress(item, received.get(), size)
            val todo = (0 until count).filter { it !in have }.iterator()
            val lock = Any()
            // Waits for the workers inside use {}, so the file stays open until they're done.
            coroutineScope { repeat(IN_FLIGHT) {
                launch {
                    while (true) {
                        val i = synchronized(lock) { if (todo.hasNext()) todo.next() else null } ?: break
                        val length = minOf(chunk.toLong(), size - i.toLong() * chunk).toInt()
                        val sealed = getChunk(relay, blob, i, length + 16)
                        val plain = CloudCrypto.openChunk(sealed, sealed.size, key, blob, i, count)
                        synchronized(lock) {
                            file.seek(i.toLong() * chunk)
                            file.write(plain)
                            progressFile.appendText("$i\n")
                        }
                        val total = received.addAndGet(length.toLong())
                        val now = System.currentTimeMillis()
                        if (now - lastReport.get() >= 200 || total == size) {
                            lastReport.set(now)
                            Transfers.cloudProgress(item, total, size)
                        }
                    }
                }
            } }
        }
    }

    /** One chunk: waits while it isn't uploaded yet (204), resumes a cut-off download with Range. */
    private suspend fun getChunk(relay: Relay, blob: String, index: Int, expected: Int): ByteArray {
        val buffer = ByteArray(expected)
        var got = 0
        var failingSince = 0L
        var backoff = 1_000L
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val range = if (got > 0) mapOf("Range" to "bytes=$got-") else emptyMap()
                relay.call("GET", "/v1/blobs/$blob/$index", headers = range).use { res ->
                    when (res.code) {
                        204 -> { failingSince = 0; return@use }
                        404, 410 -> throw Gone()
                        200, 206 -> {
                            if (res.code == 200) got = 0 // the whole chunk again
                            val body = res.body!!.byteStream()
                            while (got < expected) {
                                val n = body.read(buffer, got, expected - got)
                                if (n < 0) break
                                got += n
                                failingSince = 0
                            }
                            if (got == expected && body.read() < 0) return buffer
                            if (got == expected) throw IOException("chunk $index is longer than expected")
                            throw IOException("chunk $index cut off at $got bytes")
                        }
                        else -> throw relay.error(res.code, res.body?.string().orEmpty())
                    }
                }
            } catch (e: Gone) {
                throw e
            } catch (e: IOException) {
                if (e is RelayException && e.status in 400..499 && e.status != 401 && e.status != 408 && e.status != 429) throw e
                val now = System.currentTimeMillis()
                if (failingSince == 0L) failingSince = now
                if (now - failingSince > GIVE_UP_MILLIS) throw e
                Log.d(TAG, "Chunk $index: ${e.message}; again in ${backoff / 1000} s")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
                continue
            }
            backoff = 1_000L
        }
    }

    // Helpers ---------------------------------------------------------------------------------------------------------

    /** Runs [block] until it succeeds: retried with backoff on network trouble and 5xx, for up to 30 minutes. */
    private suspend fun <T> retrying(what: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        var backoff = 1_000L
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                val transient = e !is Gone && e !is Full && (e !is RelayException || e.status >= 500 || e.status == 400 || e.status == 408 || e.status == 429)
                if (!transient || System.currentTimeMillis() - start > GIVE_UP_MILLIS) throw e
                Log.d(TAG, "$what: ${e.message}; again in ${backoff / 1000} s")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var got = 0
        while (got < buffer.size) {
            val n = input.read(buffer, got, buffer.size - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    /** Where LAN transfers go (upstream's CompositeReceiveFileJob): Downloads, or the folder chosen in Settings. */
    private fun save(temp: File, name: String): Uri {
        val custom = ShareSettingsFragment.isCustomDestinationEnabled(app)
        val folder = if (custom) ShareSettingsFragment.getDestinationDirectory(app)
        else DocumentFile.fromFile(ShareSettingsFragment.getDefaultDestinationDirectory().apply { mkdirs() })
        val document = folder.createFile("*/*", FilesHelper.findValidNonExistingFileName(folder, name))
            ?: throw IOException(app.getString(R.string.cannot_create_file, name))
        try {
            (app.contentResolver.openOutputStream(document.uri) ?: throw IOException("can't write ${document.uri}")).use { out ->
                temp.inputStream().use { it.copyTo(out, 256 * 1024) }
            }
        } catch (e: IOException) {
            document.delete()
            throw e
        }
        if (!custom) {
            ContextCompat.getSystemService(app, DownloadManager::class.java)?.let { manager ->
                @Suppress("DEPRECATION")
                runCatching {
                    manager.addCompletedDownload(document.uri.lastPathSegment, app.getString(R.string.cloud_title), true,
                        document.type ?: "application/octet-stream", document.uri.path, temp.length(), false)
                }
            }
        } else {
            MediaStoreHelper.indexFile(app, document.uri)
        }
        return document.uri
    }

    private fun notifyReceived(device: Device, uri: Uri, mime: String, name: String) = runCatching {
        GeReceivedNotification.show(device, app.getString(R.string.cloud_received, name, device.name), uri, mime, name)
    }

    private fun toast(text: Int) = android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show()
    }
}
