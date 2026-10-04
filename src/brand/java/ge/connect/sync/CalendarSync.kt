/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.sync

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.NetworkPacket
import java.util.Calendar
import java.util.TimeZone

/**
 * The phone's calendars for the Mac's countdown, agenda and heads-ups (PROTOCOL.md (janus-mac docs) → ge.connect.calendar, R26).
 * A complete snapshot (now − 12 h … end of the 7th day) goes to the owner's Macs as the companion notification
 * `ge.connect.calendar`: on connect, when the calendar provider changes (debounced), at midnight, and on request.
 * Changes are cancel + repost (R22); unchanged snapshots aren't resent. Needs READ_CALENDAR (optional setup step).
 */
object CalendarSync {
    private const val TAG = "CalendarSync"
    private const val ID = "ge.connect.calendar"
    private const val CALLBACK_KEY = "ge.connect.calendar"
    private const val DEBOUNCE_MILLIS = 2_000L
    private const val MAX_EVENTS = 400
    private const val DAYS = 7 // today and the 6 days after it
    private const val PREFS = "ge_connect_calendar"
    private const val KEY_SENT_TO = "sent_to" // Macs that have a snapshot from us (so they hear when access goes off)
    private const val HOUR = 3_600_000L

    private lateinit var app: Application
    private val worker by lazy { Handler(HandlerThread(TAG).apply { start() }.looper) }
    // Worker thread only.
    private var observing = false
    private val reachable = mutableSetOf<String>()
    private val lastSent = mutableMapOf<String, String>()

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun start(app: Application) {
        this.app = app
        // Snapshot for Macs that just connected.
        KdeConnect.getInstance().addDeviceListChangedCallback(CALLBACK_KEY) {
            worker.post {
                val connected = macs()
                connected.filter { reachable.add(it.deviceId) }.forEach { lastSent.remove(it.deviceId); send(it) }
                reachable.retainAll(connected.map { it.deviceId }.toSet())
            }
        }
        // The window moves at midnight; the clock or time zone changing moves it too.
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = changedSoon()
        }, IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        worker.post { observe() }
    }

    /** After the setup guide (the permission may just have been granted). */
    fun permissionMaybeChanged() {
        if (!::app.isInitialized) return
        worker.post { if (observe()) sendAll.run() }
    }

    /** "ge.connect.calendar:refresh" from a Mac: a fresh snapshot now. */
    fun refresh(device: Device) = worker.post {
        requestSync()
        lastSent.remove(device.deviceId)
        send(device, forceOff = true)
    }

    /**
     * Asks Android to sync the calendar accounts now (changes made elsewhere, e.g. on the web, can take a while to
     * arrive otherwise). The observer sends the new snapshot when the sync lands.
     */
    private fun requestSync() {
        if (!hasPermission(app)) return
        runCatching {
            val accounts = mutableSetOf<android.accounts.Account>()
            app.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE),
                "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1", null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val type = c.getString(1)?.takeIf { it != CalendarContract.ACCOUNT_TYPE_LOCAL } ?: continue
                    accounts += android.accounts.Account(name, type)
                }
            }
            val extras = android.os.Bundle().apply {
                putBoolean(android.content.ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(android.content.ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            accounts.forEach { android.content.ContentResolver.requestSync(it, CalendarContract.AUTHORITY, extras) }
            Log.i(TAG, "Requested a calendar sync for ${accounts.size} accounts")
        }.onFailure { Log.w(TAG, "Couldn't request a calendar sync: ${it.message}") }
    }

    /** @return true if it just started observing */
    private fun observe(): Boolean {
        if (observing || !hasPermission(app)) return false
        runCatching {
            app.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, object : ContentObserver(worker) {
                override fun onChange(selfChange: Boolean) = changedSoon()
            })
            observing = true
        }.onFailure { Log.w(TAG, "Can't observe the calendar: ${it.message}") }
        return observing
    }

    private fun changedSoon() {
        worker.removeCallbacks(sendAll)
        worker.postDelayed(sendAll, DEBOUNCE_MILLIS)
    }

    private val sendAll = Runnable { macs().forEach(::send) }

    /** The owner's own computers (phones running our app don't use it). */
    private fun macs() = KdeConnect.getInstance().devices.values.filter {
        it.isReachable && it.isPaired && ge.connect.devices.DeviceOwnership.isMine(it) && !PeerState.isPhone(it)
    }

    /**
     * Without calendar access, a Mac that had a snapshot from us gets one "off" snapshot (so it drops its copy), once;
     * [forceOff] sends it anyway (the Mac asked). Revoking the permission restarts the app, so that's remembered.
     */
    private fun send(device: Device, forceOff: Boolean = false) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sentTo = prefs.getStringSet(KEY_SENT_TO, emptySet())!!
        val key = if (hasPermission(app)) {
            val snapshot = runCatching { snapshot() }.onFailure { Log.w(TAG, "Couldn't read the calendar: ${it.message}") }.getOrNull() ?: return
            if (device.deviceId !in sentTo) prefs.edit().putStringSet(KEY_SENT_TO, sentTo + device.deviceId).apply()
            snapshot.toString()
        } else {
            if (!forceOff && device.deviceId !in sentTo) return
            prefs.edit().putStringSet(KEY_SENT_TO, sentTo - device.deviceId).apply()
            JSONObject().put("v", 1).put("calendars", JSONArray()).put("events", JSONArray()).put("off", true).toString()
        }
        if (lastSent[device.deviceId] == key) return
        lastSent[device.deviceId] = key
        // Always cancel first: the Mac's engine ignores a repost of an id it still has (R22).
        device.sendPacket(NetworkPacket("kdeconnect.notification").apply {
            this["id"] = ID
            this["isCancel"] = true
        })
        device.sendPacket(NetworkPacket("kdeconnect.notification").apply {
            this["id"] = ID
            this["appName"] = "Calendar"
            this["title"] = "Calendar"
            this["text"] = JSONObject(key).put("now", System.currentTimeMillis()).toString()
            this["ticker"] = ""
            this["isClearable"] = false
            this["silent"] = true
            this["time"] = System.currentTimeMillis().toString()
        })
    }

    /** The snapshot without `now` (so unchanged ones compare equal). */
    private fun snapshot(): JSONObject {
        val resolver = app.contentResolver
        val now = System.currentTimeMillis()
        val from = now - 12 * HOUR
        val to = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, DAYS)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // Calendars shown and synced on the phone.
        val calendars = JSONArray()
        val calendarColors = mutableMapOf<Long, Int>()
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_COLOR,
            ),
            "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1", null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val color = c.getInt(3)
                calendarColors[id] = color
                calendars.put(JSONObject().put("id", id.toString()).put("name", c.getString(1).orEmpty())
                    .put("account", c.getString(2).orEmpty()).put("color", hex(color)))
            }
        }

        // Occurrences in the window (all-day ones are stored at UTC midnights, so ask a day wider and convert).
        class Occurrence(val eventId: Long, val begin: Long, val end: Long, val json: JSONObject, val location: String?, val description: String?)
        val occurrences = mutableListOf<Occurrence>()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .also { ContentUris.appendId(it, from - 24 * HOUR); ContentUris.appendId(it, to + 24 * HOUR) }.build()
        resolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.TITLE, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.EVENT_COLOR,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS, CalendarContract.Instances.STATUS,
            ),
            null, null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val calendarId = c.getLong(7)
                if (calendarId !in calendarColors) continue
                if (!c.isNull(9) && c.getInt(9) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                if (!c.isNull(10) && c.getInt(10) == CalendarContract.Events.STATUS_CANCELED) continue
                val allDay = c.getInt(4) == 1
                val begin = if (allDay) localMidnight(c.getLong(1)) else c.getLong(1)
                val end = if (allDay) localMidnight(c.getLong(2)) else c.getLong(2)
                if (end <= from || begin >= to) continue
                val eventId = c.getLong(0)
                val location = c.getString(5)?.trim()?.takeIf { it.isNotEmpty() }
                val json = JSONObject()
                    .put("id", "$eventId:${c.getLong(1)}")
                    .put("cal", calendarId.toString())
                    .put("title", c.getString(3).orEmpty())
                    .put("start", begin)
                    .put("end", end)
                    .put("allDay", allDay)
                location?.let { json.put("location", it) }
                if (!c.isNull(8)) json.put("color", hex(c.getInt(8)))
                occurrences += Occurrence(eventId, begin, end, json, location, c.getString(6))
                if (occurrences.size >= MAX_EVENTS) break
            }
        }

        // Meeting links: conference data the provider keeps (extended properties), then location, then description.
        val extended = extendedProperties(occurrences.map { it.eventId }.distinct())
        val events = JSONArray()
        occurrences.sortedWith(compareBy({ it.begin }, { it.end })).forEach { o ->
            meetingLink(extended[o.eventId].orEmpty() + listOfNotNull(o.location, o.description))?.let { o.json.put("join", it) }
            events.put(o.json)
        }
        return JSONObject().put("v", 1).put("from", from).put("to", to).put("calendars", calendars).put("events", events)
    }

    /** Values of each event's extended properties (where sync adapters may keep conference data). */
    private fun extendedProperties(eventIds: List<Long>): Map<Long, List<String>> {
        if (eventIds.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, MutableList<String>>()
        runCatching {
            app.contentResolver.query(
                CalendarContract.ExtendedProperties.CONTENT_URI,
                arrayOf(CalendarContract.ExtendedProperties.EVENT_ID, CalendarContract.ExtendedProperties.VALUE),
                "${CalendarContract.ExtendedProperties.EVENT_ID} IN (${eventIds.joinToString(",")})", null, null,
            )?.use { c ->
                while (c.moveToNext()) c.getString(1)?.let { result.getOrPut(c.getLong(0)) { mutableListOf() } += it }
            }
        }.onFailure { Log.w(TAG, "No extended properties: ${it.message}") }
        return result
    }

    private val MEETING_LINKS = listOf(
        Regex("""(?:https?://)?meet\.google\.com/[a-z]{3}-[a-z]{4}-[a-z]{3}(?:\?[^\s"'<>)\]]*)?""", RegexOption.IGNORE_CASE),
        Regex("""(?:https?://)?(?:[\w-]+\.)*zoom\.us/(?:j|w|my)/[^\s"'<>)\]]+""", RegexOption.IGNORE_CASE),
        Regex("""(?:https?://)?teams\.microsoft\.com/l/meetup-join/[^\s"'<>)\]]+""", RegexOption.IGNORE_CASE),
        Regex("""(?:https?://)?(?:[\w-]+\.)*webex\.com/(?:meet|join|[\w-]+/j\.php)[^\s"'<>)\]]*""", RegexOption.IGNORE_CASE),
    )

    /** The first meeting link in [fields] (in order; Meet, Zoom, Teams, Webex within a field). */
    private fun meetingLink(fields: List<String>): String? {
        for (field in fields) {
            val text = field.replace("\\/", "/") // JSON-escaped values
            for (pattern in MEETING_LINKS) {
                val link = pattern.find(text)?.value?.trimEnd('.', ',', ';') ?: continue
                return if (link.startsWith("http", ignoreCase = true)) link else "https://$link"
            }
        }
        return null
    }

    /** An all-day instance's UTC midnight → the same date's local midnight. */
    private fun localMidnight(utcMidnight: Long): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
        return Calendar.getInstance().apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    private fun hex(color: Int) = String.format("#%06X", color and 0xFFFFFF)
}
