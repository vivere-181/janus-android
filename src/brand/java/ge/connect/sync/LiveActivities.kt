/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.sync

import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.edit
import androidx.core.os.HandlerCompat
import androidx.preference.PreferenceManager
import org.json.JSONObject
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.notifications.GeNotificationMedia
import org.kde.kdeconnect.plugins.notifications.NotificationReceiver
import org.kde.kdeconnect.plugins.notifications.NotificationsPlugin

/**
 * Live Activities on the Mac (PROTOCOL.md (janus-mac docs) → ge.connect.live, R21): ongoing "live" notifications
 * (deliveries, rides, navigation, timers, downloads, calls…) mirrored as companion notifications with a stable id,
 * updated at most every 2 s and cancelled when they end. Started from KdeConnect.onCreate.
 */
object LiveActivities : NotificationReceiver.NotificationListener {
    private const val TAG = "LiveActivities"
    private const val ID_PREFIX = "ge.connect.live:"
    private const val MIN_INTERVAL_MILLIS = 2_000L
    private const val CALLBACK_KEY = "ge.connect.LiveActivities"
    const val KEY_ENABLED = "ge_connect_live_enabled"
    private const val KEY_DISABLED_APPS = "ge_connect_live_disabled_apps"
    private const val KEY_SEEN_APPS = "ge_connect_live_seen_apps"

    private val LIVE_CATEGORIES = setOf(
        Notification.CATEGORY_NAVIGATION, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS,
        Notification.CATEGORY_STOPWATCH, Notification.CATEGORY_WORKOUT, Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM,
    )

    /** Delivery and ride apps: any ongoing notification is a live activity (their updates are mostly text). */
    private val ALWAYS_LIVE = setOf(
        "com.wolt.android", "com.glovo", "ee.mtakso.client", "com.bolt.deliveryclient",
        "com.ubercab", "com.ubercab.eats", "ru.yandex.taxi",
    )

    /**
     * Apps whose ongoing notifications count when they show a progress bar or a running time, whatever their category.
     * Not unconditionally: browsers keep an "incognito tabs open" notification, Maps a "location sharing" one, etc.
     */
    private val TIMED_ALLOWLIST = setOf(
        "com.google.android.apps.maps", "com.waze",
        "com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.sec.android.app.shealth", "com.strava",
        "com.android.chrome", "com.sec.android.app.sbrowser", "com.android.vending",
        "com.samsung.android.app.screenrecorder", "com.sec.android.app.screenrecorder",
        "com.sec.android.app.voicenotes", "com.google.android.apps.recorder",
    )

    /** Never live: system noise and apps whose ongoing notifications are covered elsewhere. */
    private val DENYLIST = setOf("android", "com.android.systemui", "com.android.vpndialogs", "com.android.providers.downloads.ui")

    private class Tracked(val deviceIds: MutableSet<String> = mutableSetOf(), var lastJson: String? = null, var lastSent: Long = 0, var pending: StatusBarNotification? = null)

    private lateinit var app: Context
    private val worker by lazy { Handler(HandlerThread("LiveActivities").apply { start() }.looper) }
    private val tracked = HashMap<String, Tracked>() // notification key → state; worker thread only
    private val iconsSent = HashSet<String>() // "deviceId/package", reset on reconnect
    private val icons = HashMap<String, Pair<ByteArray, String>?>() // package → (PNG, hash), rendered once
    private val reachable = HashSet<String>()
    private var receiver: NotificationReceiver? = null

    fun start(context: Context) {
        app = context.applicationContext
        NotificationReceiver.RunCommand(app) { service -> service.addListener(this) }
        KdeConnect.getInstance().addDeviceListChangedCallback(CALLBACK_KEY) { worker.post(::onDevicesChanged) }
    }

    // Settings -----------------------------------------------------------------------------------------------------

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)
    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
        worker.post(::resync)
    }
    fun seenApps(context: Context): Set<String> = prefs(context).getStringSet(KEY_SEEN_APPS, emptySet()).orEmpty()
    fun isAppEnabled(context: Context, packageName: String) = packageName !in prefs(context).getStringSet(KEY_DISABLED_APPS, emptySet()).orEmpty()
    fun setAppEnabled(context: Context, packageName: String, enabled: Boolean) {
        val disabled = prefs(context).getStringSet(KEY_DISABLED_APPS, emptySet()).orEmpty().toMutableSet()
        if (enabled) disabled.remove(packageName) else disabled.add(packageName)
        prefs(context).edit { putStringSet(KEY_DISABLED_APPS, disabled) }
        worker.post(::resync)
    }

    private fun rememberApp(packageName: String) {
        val seen = seenApps(app)
        if (packageName !in seen) prefs(app).edit { putStringSet(KEY_SEEN_APPS, seen + packageName) }
    }

    // Listener -----------------------------------------------------------------------------------------------------

    override fun onListenerConnected(service: NotificationReceiver) {
        receiver = service
        worker.post(::resync)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        worker.post { handle(sbn) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        worker.post {
            end(sbn.key)
            // An order update cleared (swiped, or tapped to open the app): end the order unless another one is still up.
            // A moment later, so an app that cancels and reposts its update doesn't end it.
            if (runCatching { isOrderUpdate(sbn) }.getOrDefault(false)) worker.postDelayed({
                val active = runCatching { receiver?.activeNotifications?.toList() }.getOrNull().orEmpty()
                if (active.none { it.packageName == sbn.packageName && isOrderUpdate(it) }) end(ORDER_KEY + sbn.packageName)
            }, ORDER_REPOST_MILLIS)
        }
    }

    /** Re-evaluates everything on the phone right now (after connect, settings changes). */
    private fun resync() {
        val active = runCatching { receiver?.activeNotifications?.toList() }.getOrNull().orEmpty()
        val activeKeys = active.map { it.key }.toSet()
        // Orders end on their own rules (handleOrder), not with their source notification.
        tracked.keys.filter { !it.startsWith(ORDER_KEY) && it !in activeKeys }.forEach(::end)
        active.forEach(::handle)
    }

    private fun onDevicesChanged() {
        val now = targets().map { it.deviceId }.toSet()
        val added = now - reachable
        reachable.clear(); reachable.addAll(now)
        if (added.isEmpty()) return
        iconsSent.removeAll { entry -> added.any { entry.startsWith("$it/") } }
        // New connection: the Mac starts empty, so send everything again.
        tracked.values.forEach { it.lastJson = null; it.deviceIds.removeAll(added) }
        resync()
    }

    private fun handle(sbn: StatusBarNotification) {
        // Never let order parsing take the app down (this thread's crash kills the whole process).
        if (runCatching { isOrderUpdate(sbn) }.onFailure { Log.w(TAG, "Order check failed", it) }.getOrDefault(false)) {
            runCatching { handleOrder(sbn) }.onFailure { Log.w(TAG, "Order update failed", it) }
            return
        }
        if (!isLive(sbn) || !isEnabled(app) || !isAppEnabled(app, sbn.packageName)) {
            end(sbn.key)
            if (isLive(sbn)) rememberApp(sbn.packageName)
            return
        }
        rememberApp(sbn.packageName)
        val state = tracked.getOrPut(sbn.key) { Tracked() }
        val wait = state.lastSent + MIN_INTERVAL_MILLIS - System.currentTimeMillis()
        if (wait > 0 && !isStateChange(sbn, state)) {
            // Coalesce bursts: send the newest version once the interval has passed.
            val scheduled = state.pending != null
            state.pending = sbn
            if (!scheduled) worker.postDelayed({ state.pending?.let { state.pending = null; send(it, state) } }, wait)
            return
        }
        state.pending = null
        send(sbn, state)
    }

    /** Paused ↔ running (or a new end time) goes out right away; only routine updates wait for the interval. */
    private fun isStateChange(sbn: StatusBarNotification, state: Tracked): Boolean {
        val last = state.lastJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return true
        val now = describe(sbn)
        return now.optString("chrono") != last.optString("chrono") || now.optLong("when") != last.optLong("when")
    }

    private fun send(sbn: StatusBarNotification, state: Tracked) = send(sbn.packageName, sbn.key, describe(sbn), state)

    private fun send(packageName: String, key: String, description: JSONObject, state: Tracked) {
        val icon = icons.getOrPut(packageName) {
            GeNotificationMedia.appIconBytes(app, packageName)?.let { it to checksum(it) }
        }
        // The Mac UI can't see payloadHash, so the hash also goes in the JSON; it finds the engine's cached file by it.
        val json = description.apply { icon?.let { put("icon", it.second) } }.toString()
        val devices = targets()
        val newDevices = devices.filter { it.deviceId !in state.deviceIds }
        if (json == state.lastJson && newDevices.isEmpty()) return
        val label = appLabel(packageName)
        for (device in if (json == state.lastJson) newDevices else devices) {
            val np = NetworkPacket("kdeconnect.notification").apply {
                this["id"] = ID_PREFIX + key
                this["appName"] = label
                this["title"] = label
                // "now": the phone's clock when sent, so the Mac can correct `when` for a clock difference.
                // Not part of the change check (json), which would otherwise always differ.
                this["text"] = JSONObject(json).put("now", System.currentTimeMillis()).toString()
                this["ticker"] = ""
                this["isClearable"] = false
                this["silent"] = true
                this["time"] = System.currentTimeMillis().toString()
            }
            // Launcher icon, once per app per connection (the Mac caches it by hash).
            icon?.let { (bytes, hash) ->
                np["payloadHash"] = hash
                if (iconsSent.add("${device.deviceId}/${packageName}")) np.payload = NetworkPacket.Payload(bytes)
            }
            // The Mac's engine ignores in-place updates (R22): cancel, then post again under the same id.
            if (device.deviceId in state.deviceIds) {
                device.sendPacket(NetworkPacket("kdeconnect.notification").apply {
                    this["id"] = ID_PREFIX + key
                    this["isCancel"] = true
                })
            }
            device.sendPacket(np)
            state.deviceIds.add(device.deviceId)
        }
        state.lastJson = json
        state.lastSent = System.currentTimeMillis()
        Log.i(TAG, "Live ${packageName} → ${devices.size} device(s): $json")
    }

    private fun end(key: String) {
        val state = tracked.remove(key) ?: return
        state.pending = null
        Log.i(TAG, "Ended $key")
        val devices = KdeConnect.getInstance().devices.values.filter { it.deviceId in state.deviceIds && it.isReachable }
        for (device in devices) {
            device.sendPacket(NetworkPacket("kdeconnect.notification").apply {
                this["id"] = ID_PREFIX + key
                this["isCancel"] = true
            })
        }
    }

    // Delivery orders without an ongoing tracker (R27) ---------------------------------------------------------------

    /** A delivery app's plain (not ongoing) order-progress notification, e.g. Glovo's order_updates_channel. */
    private fun isOrderUpdate(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName !in ALWAYS_LIVE) return false
        val n = sbn.notification
        if ((n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY)) != 0) return false
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.channelId else null
        return OrderUpdates.isOrderChannel(channel)
    }

    /** One live activity per app: each update replaces the text; ends when delivered/cancelled or after 90 min of quiet. */
    private fun handleOrder(sbn: StatusBarNotification) {
        val key = ORDER_KEY + sbn.packageName
        rememberApp(sbn.packageName)
        if (!isEnabled(app) || !isAppEnabled(app, sbn.packageName)) return end(key)
        val extras = sbn.notification.extras
        fun text(name: String) = extras.getCharSequence(name)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val title = text(Notification.EXTRA_TITLE_BIG) ?: text(Notification.EXTRA_TITLE) ?: ""
        val body = text(Notification.EXTRA_BIG_TEXT) ?: text(Notification.EXTRA_TEXT) ?: ""
        if (title.isEmpty() && body.isEmpty()) return
        if (OrderUpdates.isDone("$title\n$body")) return end(key)
        val description = JSONObject().apply {
            put("v", 1)
            put("app", appLabel(sbn.packageName))
            put("title", title)
            put("text", body)
            when (val eta = OrderUpdates.eta("$title\n$body", sbn.postTime)) {
                is OrderUpdates.Eta.At -> { put("when", eta.whenMillis); put("chrono", "down") }
                is OrderUpdates.Eta.Shown -> put("short", eta.text)
                null -> {}
            }
            put("cat", Notification.CATEGORY_TRANSPORT)
        }
        val state = tracked.getOrPut(key) { Tracked() }
        state.pending = null
        send(sbn.packageName, key, description, state)
        worker.removeCallbacksAndMessages(key)
        HandlerCompat.postDelayed(worker, { end(key) }, key, ORDER_QUIET_MILLIS)
    }

    private fun targets(): List<Device> = KdeConnect.getInstance().devices.values
        .filter { it.isReachable && it.isPaired && ge.connect.devices.DeviceOwnership.isMine(it) && it.getPlugin(NotificationsPlugin::class.java) != null }

    // What counts as live ------------------------------------------------------------------------------------------

    fun isLive(sbn: StatusBarNotification): Boolean {
        if (::app.isInitialized && sbn.packageName == app.packageName) return false
        if (sbn.packageName in DENYLIST) return false
        val n = sbn.notification
        val extras = n.extras
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        if (template.contains("MediaStyle") || extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false
        if (Build.VERSION.SDK_INT >= 36 && (n.flags and Notification.FLAG_PROMOTED_ONGOING) != 0) return true
        if (template.endsWith("\$ProgressStyle")) return true
        val ongoing = (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0
        if (!ongoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        // Samsung marks what it shows in the Now Bar (timers, recorders, calls, Samsung Health…).
        if (extras.containsKey(SAMSUNG_STYLE)) return true
        if (sbn.packageName in ALWAYS_LIVE) return true
        val hasProgress = extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0 || extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val hasChrono = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        if (sbn.packageName in TIMED_ALLOWLIST) return hasProgress || hasChrono
        return (hasProgress || hasChrono) && n.category in LIVE_CATEGORIES
    }

    private fun describe(sbn: StatusBarNotification): JSONObject {
        val n = sbn.notification
        val extras = n.extras
        fun text(key: String) = extras.getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }
        val samsung = samsungTime(sbn)
        return JSONObject().apply {
            put("v", 1)
            put("app", appLabel(sbn.packageName))
            put("title", text(Notification.EXTRA_TITLE_BIG) ?: text(Notification.EXTRA_TITLE) ?: text(SAMSUNG_PRIMARY) ?: "")
            // A clock-like text ("00:00:02") goes stale on the Mac, so it's dropped; Samsung's Now Bar line is used instead.
            val body = text(Notification.EXTRA_TEXT) ?: text(Notification.EXTRA_BIG_TEXT) ?: text(Notification.EXTRA_SUB_TEXT)
            put("text", body?.takeUnless { CLOCK_TEXT.matches(it.trim()) } ?: text(SAMSUNG_SECONDARY) ?: "")
            when (samsung) {
                is SamsungTime.Running -> {
                    put("when", samsung.whenMillis)
                    put("chrono", if (samsung.countDown) "down" else "up")
                }
                is SamsungTime.Stopped -> if (samsung.shownText.isNotBlank()) put("short", samsung.shownText)
                null -> {}
            }
            if (!has("short")) text("android.shortCriticalText")?.let { put("short", it) } // Notification.EXTRA_SHORT_CRITICAL_TEXT (API 36)
            val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
            val progress = extras.getInt(Notification.EXTRA_PROGRESS)
            if (extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) {
                put("indeterminate", true)
            } else if (max > 0) {
                put("progress", (progress.toDouble() / max).coerceIn(0.0, 1.0))
            } else if (max == 0 && progress in 1..100 && extras.getString(Notification.EXTRA_TEMPLATE).orEmpty().endsWith("\$ProgressStyle")) {
                put("progress", progress / 100.0) // ProgressStyle without segments: total 100
            }
            if (samsung == null && extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && n.`when` > 0) {
                put("when", n.`when`)
                put("chrono", if (extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)) "down" else "up")
            }
            n.category?.let { put("cat", it) }
        }
    }

    private const val ORDER_KEY = "order:"
    private const val ORDER_QUIET_MILLIS = 90 * 60_000L
    private const val ORDER_REPOST_MILLIS = 1_000L
    private const val SAMSUNG_STYLE = "android.ongoingActivityNoti.style"
    private const val SAMSUNG_PRIMARY = "android.ongoingActivityNoti.nowbarPrimaryInfo"
    private const val SAMSUNG_SECONDARY = "android.ongoingActivityNoti.secondaryInfo"
    private const val SAMSUNG_CHRONOMETER = "android.ongoingActivityNoti.chronometerRemoteView"
    private val CLOCK_TEXT = Regex("""\d{1,2}(:\d{2}){1,2}(\.\d+)?""")

    /** A Samsung Now Bar chronometer: running (ticks on the Mac from [whenMillis]) or stopped at [shownText]. */
    private sealed interface SamsungTime {
        data class Running(val whenMillis: Long, val countDown: Boolean) : SamsungTime
        data class Stopped(val shownText: String) : SamsungTime
    }

    /**
     * Samsung's Now Bar notifications (Clock timer/stopwatch…) keep their time in a Chronometer inside
     * `ongoingActivityNoti.chronometerRemoteView` instead of the standard fields. We inflate those views (as a launcher
     * does for widgets) and read the Chronometer. A running one has its base set (a timer's end, a stopwatch's start);
     * a paused/stopped one keeps the default base (= now, not counting down) and only shows fixed text.
     */
    private fun samsungTime(sbn: StatusBarNotification): SamsungTime? = runCatching {
        @Suppress("DEPRECATION")
        val views = sbn.notification.extras.getParcelable(SAMSUNG_CHRONOMETER) as? android.widget.RemoteViews ?: return null
        val chronometer = findChronometer(views.apply(app, android.widget.FrameLayout(app))) ?: return null
        val offset = chronometer.base - android.os.SystemClock.elapsedRealtime()
        // A Chronometer only ticks after setStarted(true); Samsung omits it for paused/stopped ones. If the views
        // can't be read, a base still at "now" (not counting down) also means it's stopped.
        val running = startedFlag(views) ?: (chronometer.isCountDown || kotlin.math.abs(offset) >= 1_500)
        if (!running) {
            SamsungTime.Stopped(chronometer.text.toString())
        } else {
            // Rounded to the second: the two clocks are read a moment apart, and a jittering value would resend every 2 s.
            SamsungTime.Running((System.currentTimeMillis() + offset + 500) / 1000 * 1000, chronometer.isCountDown)
        }
    }.onFailure { Log.w(TAG, "Samsung chronometer unreadable: ${it.message}") }.getOrNull()

    /**
     * The RemoteViews' setStarted(boolean) value, read from its parcel with public APIs only (the Chronometer's own
     * flag is hidden). false when there's no setStarted action, null when the parcel can't be read.
     */
    private fun startedFlag(views: android.widget.RemoteViews): Boolean? {
        val parcel = android.os.Parcel.obtain()
        return try {
            views.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            val name = "setStarted".toByteArray(Charsets.UTF_8)
            val at = (0..bytes.size - name.size).lastOrNull { i -> name.indices.all { bytes[i + it] == name[it] } } ?: return false
            // ReflectionAction: method name (string8, NUL-terminated, 4-byte aligned), then type (1 = boolean), then value.
            var position = at + name.size + 1
            position += (4 - position % 4) % 4
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (buffer.getInt(position) != 1) null else buffer.getInt(position + 4) != 0
        } catch (e: Exception) {
            null // e.g. views holding binders can't be marshalled
        } finally {
            parcel.recycle()
        }
    }

    private fun findChronometer(view: android.view.View): android.widget.Chronometer? = when (view) {
        is android.widget.Chronometer -> view
        is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findChronometer(view.getChildAt(it)) }
        else -> null
    }

    fun appLabel(packageName: String): String = runCatching {
        app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun checksum(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
