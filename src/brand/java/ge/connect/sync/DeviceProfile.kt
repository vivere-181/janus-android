/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.sync

import android.app.Application
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Display
import android.view.RoundedCorner
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect_tp.BuildConfig
import java.util.Locale
import java.util.TimeZone

/**
 * What this phone is, so the Mac doesn't guess from its name (PROTOCOL.md (janus-mac docs) → ge.connect.device, R28): maker and
 * model, screen shape, Material You colours, region and the companion features this build has. Sent to the owner's
 * computers after each connect and when something in it changes (cancel + repost, R22).
 */
object DeviceProfile {
    private const val TAG = "DeviceProfile"
    private const val ID = "ge.connect.device"
    private const val CALLBACK_KEY = "ge.connect.device"
    private const val DEBOUNCE_MILLIS = 2_000L

    /** The `ge.connect.*` commands and companion features this build understands. */
    private val CAPS = listOf(
        "status", "ringer", "flashlight", "dnd", "dial", "call", "macstate", "macaction", "mirror", "calendar", "live", "device", "notes", "cloud", "cloud2",
    )

    private lateinit var app: Application
    private val worker by lazy { Handler(HandlerThread(TAG).apply { start() }.looper) }
    // Worker thread only.
    private val reachable = mutableSetOf<String>()
    private val lastSent = mutableMapOf<String, String>()

    fun start(app: Application) {
        this.app = app
        KdeConnect.getInstance().addDeviceListChangedCallback(CALLBACK_KEY) {
            worker.post {
                val connected = computers()
                connected.filter { reachable.add(it.deviceId) }.forEach { lastSent.remove(it.deviceId); send(it) }
                reachable.retainAll(connected.map { it.deviceId }.toSet())
            }
        }
        // Language, time zone, display size (configuration) and wallpaper colours can change.
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = changedSoon()
        }, IntentFilter().apply {
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching { WallpaperManager.getInstance(app).addOnColorsChangedListener({ _, _ -> changedSoon() }, worker) }
        }
    }

    /** Our relay socket just connected: away Macs get the profile again, as on a new LAN connection (R37). */
    fun cloudConnected(devices: List<Device>) = worker.post {
        devices.forEach { lastSent.remove(it.deviceId); send(it) }
    }

    private fun changedSoon() {
        worker.removeCallbacks(sendAll)
        worker.postDelayed(sendAll, DEBOUNCE_MILLIS)
    }

    private val sendAll = Runnable { (computers() + ge.connect.cloud.CloudCompanion.awayMacs()).forEach(::send) } // R37: away Macs via the cloud

    private fun computers() = KdeConnect.getInstance().devices.values.filter {
        it.isReachable && it.isPaired && ge.connect.devices.DeviceOwnership.isMine(it) && !PeerState.isPhone(it)
    }

    private fun send(device: Device) {
        val profile = runCatching { profile() }.onFailure { Log.w(TAG, "Couldn't describe the phone", it) }.getOrNull() ?: return
        val text = profile.toString()
        if (lastSent[device.deviceId] == text) return
        if (!device.isReachable) {
            if (ge.connect.cloud.CloudCompanion.away(device) && ge.connect.cloud.CloudCompanion.send(device, ID, text)) {
                lastSent[device.deviceId] = text
                Log.i(TAG, "Profile → ${device.name} through Janus Cloud")
            }
            return
        }
        lastSent[device.deviceId] = text
        device.sendPacket(NetworkPacket("kdeconnect.notification").apply {
            this["id"] = ID
            this["isCancel"] = true
        })
        device.sendPacket(NetworkPacket("kdeconnect.notification").apply {
            this["id"] = ID
            this["appName"] = "Phone"
            this["title"] = "Phone"
            this["text"] = text
            this["ticker"] = ""
            this["isClearable"] = false
            this["silent"] = true
            this["time"] = System.currentTimeMillis().toString()
        })
        Log.i(TAG, "Profile → ${device.name}: $text")
    }

    fun profile(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("maker", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        market?.let { put("market", it) }
        put("sdk", Build.VERSION.SDK_INT)
        put("release", Build.VERSION.RELEASE)
        DeviceStats.skin?.let { put("skin", it) }
        colorCode?.let { put("colorCode", it) }
        put("screen", screen())
        accents()?.let { put("accent", it) }
        country()?.let { put("country", it) }
        put("tz", TimeZone.getDefault().id)
        put("locale", Locale.getDefault().toLanguageTag())
        put("app", BuildConfig.VERSION_NAME)
        put("caps", JSONArray(CAPS))
    }

    // These don't change while the app runs; reading them spawns getprop, so only once (R34).
    private val market by lazy { marketName() }
    private val colorCode by lazy { colorCode() }

    /** "Galaxy S22 Ultra": Samsung's default device name, else a marketing-name property; null if the phone has none. */
    private fun marketName(): String? =
        runCatching { Settings.Global.getString(app.contentResolver, "default_device_name") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: listOf("ro.product.marketname", "ro.product.vendor.marketname", "ro.config.marketing_name")
                .firstNotNullOfOrNull { DeviceStats.prop(it) }

    /** Samsung's product code (e.g. SM-S908EZKGKSA, "ZK" = the body colour), raw, if apps may read it. */
    private fun colorCode(): String? =
        listOf("ril.product_code", "ro.boot.product_code").firstNotNullOfOrNull { DeviceStats.prop(it) }

    /** Natural-portrait screen: size in px, density, corner radius and cutouts. */
    private fun screen(): JSONObject {
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = app.resources.displayMetrics
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            app.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            @Suppress("DEPRECATION")
            android.util.DisplayMetrics().also { display.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
        }
        val rotation = display.rotation
        val sideways = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        val naturalW = if (sideways) height else width
        val naturalH = if (sideways) width else height
        return JSONObject().apply {
            put("w", naturalW)
            put("h", naturalH)
            put("dpi", metrics.densityDpi)
            put("xdpi", metrics.xdpi.toDouble())
            put("ydpi", metrics.ydpi.toDouble())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                display.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.takeIf { it > 0 }?.let { put("radius", it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                display.cutout?.boundingRects?.takeIf { it.isNotEmpty() }?.let { rects ->
                    put("cutouts", JSONArray(rects.map { toNatural(it, rotation, naturalW, naturalH) }.map { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) }))
                }
            }
        }
    }

    /** A rect in the current rotation's coordinates → natural-portrait coordinates ([w]×[h]). */
    private fun toNatural(r: Rect, rotation: Int, w: Int, h: Int): Rect {
        fun point(x: Int, y: Int): Pair<Int, Int> = when (rotation) {
            Surface.ROTATION_90 -> (w - y) to x
            Surface.ROTATION_180 -> (w - x) to (h - y)
            Surface.ROTATION_270 -> y to (h - x)
            else -> x to y
        }
        val (ax, ay) = point(r.left, r.top)
        val (bx, by) = point(r.right, r.bottom)
        return Rect(minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by))
    }

    /** Material You colours from the wallpaper (Android 12+). */
    private fun accents(): JSONArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return JSONArray(listOf(
            android.R.color.system_accent1_200, android.R.color.system_accent1_500,
            android.R.color.system_accent2_500, android.R.color.system_neutral1_900,
        ).map { String.format("#%06X", app.getColor(it) and 0xFFFFFF) })
    }

    /** SIM country, else the mobile network's, else the language region (ISO 3166, upper case). */
    private fun country(): String? {
        val telephony = app.getSystemService(TelephonyManager::class.java)
        return listOfNotNull(
            runCatching { telephony?.simCountryIso }.getOrNull(),
            runCatching { telephony?.networkCountryIso }.getOrNull(),
            Locale.getDefault().country,
        ).firstOrNull { it.isNotBlank() }?.uppercase(Locale.ROOT)
    }
}
