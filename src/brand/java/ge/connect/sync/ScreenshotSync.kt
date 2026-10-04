/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.sync

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.plugins.share.SharePlugin

/**
 * "Screenshots follow you" (PROTOCOL.md (janus-mac docs) → ge.connect.screenshot, R17): new screenshots are sent to
 * connected computers, announced with a marker so the Mac puts them on its clipboard. Off by default
 * (owner's choice); needs photo access. Started from KdeConnect.onCreate.
 */
object ScreenshotSync {
    const val KEY_ENABLED = "ge_connect_send_screenshots"
    private const val TAG = "ScreenshotSync"
    private const val RECENT_SECONDS = 20
    private lateinit var app: Context
    private val sent = LinkedHashSet<Long>()
    private val worker by lazy { Handler(HandlerThread("ScreenshotSync").apply { start() }.looper) }

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun isEnabled(context: Context) = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit { putBoolean(KEY_ENABLED, enabled) }
        updateObserver()
    }
    fun hasPermission(context: Context) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun start(context: Context) {
        app = context.applicationContext
        updateObserver()
    }

    private val observer by lazy {
        object : ContentObserver(worker) {
            override fun onChange(selfChange: Boolean) {
                // A screenshot triggers several changes while it's written; check once things settle.
                worker.removeCallbacks(check)
                worker.postDelayed(check, 800)
            }
        }
    }
    private var observing = false

    /** Watch the photo library only while the feature is on, so other apps' photos don't wake us. */
    @Synchronized
    private fun updateObserver() {
        if (!::app.isInitialized) return
        val wanted = isEnabled(app) && hasPermission(app)
        if (wanted == observing) return
        if (wanted) app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        else app.contentResolver.unregisterContentObserver(observer)
        observing = wanted
    }

    private val check = Runnable {
        if (!isEnabled(app) || !hasPermission(app)) return@Runnable
        val devices = KdeConnect.getInstance().devices.values.filter { it.isReachable && it.isPaired && ge.connect.devices.DeviceOwnership.isMine(it) }
        if (devices.isEmpty()) return@Runnable
        for ((id, name) in recentScreenshots()) {
            if (!sent.add(id)) continue
            while (sent.size > 50) sent.remove(sent.first())
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            for (device in devices) {
                val share = device.getPlugin(SharePlugin::class.java) ?: continue
                // Marker first (blocking), so it's there when the file arrives.
                CompanionMessages.sendStatusNotification(app, device, "ge.connect.screenshot:", "Screenshot", name, silent = true)
                share.sendFiles(listOf(uri))
            }
        }
    }

    /** Finished screenshots from the last few seconds: (MediaStore id, file name). */
    private fun recentScreenshots(): List<Pair<Long, String>> {
        val since = System.currentTimeMillis() / 1000 - RECENT_SECONDS
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        val selection = buildString {
            append("${MediaStore.Images.Media.DATE_ADDED} >= ? AND $pathColumn LIKE ?")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) append(" AND ${MediaStore.Images.Media.IS_PENDING} = 0")
        }
        return runCatching {
            app.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
                selection, arrayOf(since.toString(), "%Screenshots%"),
                "${MediaStore.Images.Media.DATE_ADDED} ASC",
            )?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) } }.orEmpty()
        }.onFailure { Log.w(TAG, "Can't read screenshots: ${it.message}") }.getOrDefault(emptyList())
    }
}
