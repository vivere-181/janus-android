/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.transfers

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
import android.webkit.MimeTypeMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Log of files, links and text sent to and received from computers, with live progress, for the
 * Blip-style transfers feed. Fed by small hooks in upstream's share code; keeps the last [MAX_ITEMS].
 */
object Transfers {
    enum class Direction { IN, OUT }
    enum class Kind { FILE, LINK, TEXT }
    enum class State { WAITING, RUNNING, DONE, FAILED, DECLINED }

    data class Item(
        val id: Long,
        val direction: Direction,
        val kind: Kind,
        val deviceId: String,
        val deviceName: String,
        val name: String,
        val size: Long,
        val mime: String?,
        val uri: String?,
        val thumb: String?,
        val time: Long,
        val state: State,
        val progress: Float = 0f,
        val bytesPerSecond: Long = 0,
        /** Sent to someone else's phone that asked its owner to accept (IncomingConsent); not done until they do. */
        val awaitingAccept: Boolean = false,
        /** Went through Janus Cloud (R36) instead of the local network. */
        val cloud: Boolean = false,
        /** Sent through the cloud and waiting there until the computer picks it up. */
        val awaitingDelivery: Boolean = false,
    ) {
        val active get() = state == State.WAITING || state == State.RUNNING || awaitingAccept
    }

    private const val TAG = "Transfers"
    private const val MAX_ITEMS = 100
    private const val FILE = "transfers.json"
    private const val THUMB_DIR = "transfer_thumbs"

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    /** Newest first. */
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    private lateinit var app: Context
    private val nextId = AtomicLong(System.currentTimeMillis())
    private val packetIds = WeakHashMap<NetworkPacket, Long>()
    private val speed = HashMap<Long, Pair<Long, Long>>() // id -> (bytes, time) at last sample

    fun init(context: Context) {
        app = context.applicationContext
        val loaded = runCatching { parse(File(app.filesDir, FILE).readText()) }.getOrDefault(emptyList())
        // Anything still in flight when the app stopped didn't finish.
        _items.value = loaded.map { if (it.active) it.copy(state = State.FAILED) else it }
    }

    // --- Outgoing files (SharePlugin.sendFiles + CompositeUploadFileJob) ---

    /** Called while the sharing app's permission is still valid: records the file and grabs a thumbnail. */
    fun outgoingQueued(device: Device, np: NetworkPacket, uri: Uri) {
        val id = nextId.incrementAndGet()
        val mime = app.contentResolver.getType(uri)
        synchronized(packetIds) { packetIds[np] = id }
        add(Item(id, Direction.OUT, Kind.FILE, device.deviceId, device.name, np.getString("filename", "file"),
            np.payloadSize, mime, uri.toString(), thumbnail(id, uri, mime), System.currentTimeMillis(), State.WAITING))
    }

    fun outgoingStarted(np: NetworkPacket) = idOf(np)?.let { id -> set(id) { it.copy(state = State.RUNNING, time = System.currentTimeMillis()) } }

    fun outgoingProgress(np: NetworkPacket, percent: Int) = idOf(np)?.let { id -> progress(id, percent / 100f) }

    fun outgoingDone(np: NetworkPacket) = idOf(np)?.let { finish(it, State.DONE) }

    /** On failure or cancel: the current file and everything still queued. */
    fun outgoingFailed(packets: Collection<NetworkPacket>) = packets.forEach { np ->
        // Keep "declined" when the other person said no (the upload then fails as its stream is closed).
        idOf(np)?.let { id -> if (_items.value.firstOrNull { it.id == id }?.state != State.DECLINED) finish(id, State.FAILED) }
    }

    /** Our recent sends to [device]: still going, or finished moments ago (small files finish before they accept). */
    private fun recentOutgoing(device: Device) = _items.value.filter {
        it.direction == Direction.OUT && it.deviceId == device.deviceId && it.kind == Kind.FILE &&
            (it.active || (it.state == State.DONE && System.currentTimeMillis() - it.time < RECENT_MS))
    }

    /** Their phone is asking its owner to accept (see IncomingConsent). */
    fun outgoingAwaitingAccept(device: Device) = recentOutgoing(device).forEach { item -> set(item.id) { it.copy(awaitingAccept = true) } }

    /** They accepted: the send finishes (or keeps going) as usual. */
    fun outgoingAccepted(device: Device) = recentOutgoing(device).filter { it.awaitingAccept }.forEach { item ->
        set(item.id) { it.copy(awaitingAccept = false) }
        if (item.state == State.DONE) persist()
    }

    /** The other person declined what we're sending them. */
    fun outgoingDeclined(device: Device) = recentOutgoing(device).forEach { item ->
        set(item.id) { it.copy(awaitingAccept = false) }
        finish(item.id, State.DECLINED)
    }

    // --- Incoming files (CompositeReceiveFileJob) ---

    fun incomingStarted(device: Device, np: NetworkPacket, fileName: String): Long {
        val id = nextId.incrementAndGet()
        add(Item(id, Direction.IN, Kind.FILE, device.deviceId, device.name, fileName, np.payloadSize,
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase()),
            null, null, System.currentTimeMillis(), State.RUNNING))
        return id
    }

    fun incomingProgress(id: Long, received: Long, total: Long) {
        if (total > 0) progress(id, received.toFloat() / total, received)
    }

    fun incomingDone(id: Long, uri: Uri, mime: String?) {
        set(id) { it.copy(uri = uri.toString(), mime = mime ?: it.mime) }
        val thumb = thumbnail(id, uri, mime)
        finish(id, State.DONE) { it.copy(thumb = thumb) }
    }

    fun incomingFailed(id: Long) = finish(id, State.FAILED)

    // --- Janus Cloud (R36): files through the relay, both directions ---

    fun cloudOutgoing(deviceId: String, deviceName: String, name: String, size: Long, mime: String?, uri: Uri): Long {
        val id = nextId.incrementAndGet()
        add(Item(id, Direction.OUT, Kind.FILE, deviceId, deviceName, name, size, mime, uri.toString(), thumbnail(id, uri, mime),
            System.currentTimeMillis(), State.WAITING, cloud = true))
        return id
    }

    fun cloudIncoming(deviceId: String, deviceName: String, name: String, size: Long, mime: String?): Long {
        val id = nextId.incrementAndGet()
        add(Item(id, Direction.IN, Kind.FILE, deviceId, deviceName, name, size, mime, null, null, System.currentTimeMillis(),
            State.RUNNING, cloud = true))
        return id
    }

    fun cloudProgress(id: Long, bytes: Long, total: Long) = progress(id, if (total > 0) bytes.toFloat() / total else 1f, bytes)

    /** Uploaded; it waits in the cloud until the computer has it ([cloudDelivered]). */
    fun cloudUploaded(id: Long) = finish(id, State.DONE) { it.copy(awaitingDelivery = true) }

    fun cloudDelivered(id: Long) = set(id) { it.copy(awaitingDelivery = false) }

    fun failed(id: Long) = finish(id, State.FAILED) { it.copy(awaitingDelivery = false) }

    // --- Links and text (both directions) ---

    fun message(device: Device, direction: Direction, kind: Kind, content: String, cloud: Boolean = false) {
        val id = nextId.incrementAndGet()
        add(Item(id, direction, kind, device.deviceId, device.name, content, content.length.toLong(), null, null, null,
            System.currentTimeMillis(), State.DONE, progress = 1f, cloud = cloud))
        persist()
    }

    fun remove(id: Long) {
        _items.update { list -> list.filterNot { it.id == id } }
        File(File(app.filesDir, THUMB_DIR), "$id.jpg").delete()
        persist()
    }

    // --- Internals ---

    private fun idOf(np: NetworkPacket): Long? = synchronized(packetIds) { packetIds[np] }

    private fun add(item: Item) = _items.update { (listOf(item) + it).take(MAX_ITEMS) }

    private const val RECENT_MS = 10 * 60_000L

    private fun set(id: Long, change: (Item) -> Item) = _items.update { list -> list.map { if (it.id == id) change(it) else it } }

    private fun progress(id: Long, fraction: Float, bytes: Long? = null) {
        val now = System.currentTimeMillis()
        set(id) { item ->
            val done = bytes ?: (item.size * fraction).toLong()
            val (lastBytes, lastTime) = synchronized(speed) { speed[id] } ?: (0L to item.time)
            val rate = if (now > lastTime) ((done - lastBytes) * 1000 / (now - lastTime)) else item.bytesPerSecond
            if (now - lastTime >= 500) synchronized(speed) { speed[id] = done to now }
            // Smooth the rate so the number doesn't jump around.
            item.copy(state = State.RUNNING, progress = fraction.coerceIn(0f, 1f),
                bytesPerSecond = if (item.bytesPerSecond == 0L) rate else (item.bytesPerSecond * 3 + rate) / 4)
        }
    }

    private fun finish(id: Long, state: State, change: (Item) -> Item = { it }) {
        synchronized(speed) { speed.remove(id) }
        set(id) { change(it).copy(state = state, progress = if (state == State.DONE) 1f else it.progress, bytesPerSecond = 0) }
        persist()
    }

    /** Small preview saved in app storage, so it survives the sharing app's temporary permission. */
    private fun thumbnail(id: Long, uri: Uri, mime: String?): String? {
        if (mime == null || !(mime.startsWith("image/") || mime.startsWith("video/") || mime == "application/pdf")) return null
        val size = Size(512, 512)
        val bitmap: Bitmap? = runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            if (uri.scheme == "file") {
                val file = File(uri.path!!)
                if (mime.startsWith("video/")) ThumbnailUtils.createVideoThumbnail(file, size, null)
                else ThumbnailUtils.createImageThumbnail(file, size, null)
            } else {
                app.contentResolver.loadThumbnail(uri, size, null)
            }
        }.onFailure { Log.d(TAG, "No thumbnail for $uri: ${it.message}") }.getOrNull()
        bitmap ?: return null
        val dir = File(app.filesDir, THUMB_DIR).apply { mkdirs() }
        val out = File(dir, "$id.jpg")
        runCatching { out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } }.getOrElse { return null }
        return out.absolutePath
    }

    @Synchronized
    private fun persist() {
        val done = _items.value.filterNot { it.active }
        val json = JSONArray(done.map { i ->
            JSONObject().apply {
                put("id", i.id); put("direction", i.direction.name); put("kind", i.kind.name)
                put("deviceId", i.deviceId); put("deviceName", i.deviceName); put("name", i.name)
                put("size", i.size); put("mime", i.mime); put("uri", i.uri); put("thumb", i.thumb)
                put("time", i.time); put("state", i.state.name)
                if (i.cloud) put("cloud", true)
            }
        })
        runCatching { File(app.filesDir, FILE).writeText(json.toString()) }
        // Drop thumbnails of items that fell off the list.
        val keep = _items.value.mapNotNull { it.thumb }.toSet()
        File(app.filesDir, THUMB_DIR).listFiles()?.filter { it.absolutePath !in keep && it.name.removeSuffix(".jpg").toLongOrNull() !in _items.value.map { i -> i.id } }?.forEach { it.delete() }
    }

    private fun parse(text: String): List<Item> {
        val array = JSONArray(text)
        return (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            Item(
                o.getLong("id"), Direction.valueOf(o.getString("direction")), Kind.valueOf(o.getString("kind")),
                o.getString("deviceId"), o.getString("deviceName"), o.getString("name"), o.getLong("size"),
                o.optString("mime").takeIf { it.isNotEmpty() && it != "null" },
                o.optString("uri").takeIf { it.isNotEmpty() && it != "null" },
                o.optString("thumb").takeIf { it.isNotEmpty() && it != "null" },
                o.getLong("time"), State.valueOf(o.getString("state")), progress = if (o.getString("state") == "DONE") 1f else 0f,
                cloud = o.optBoolean("cloud"),
            )
        }
    }
}
