/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.tv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Between a TV and a phone or Mac: the TV's apps (name, package, banner), sent by the TV when they connect, for the
 * app row on their remotes (which open them through the TV remote: janus://launch?package=…). PROTOCOL.md (janus-mac docs) →
 * ge.connect.tvapps: Janus phones get our own packets; a Mac (whose engine can't take new packet types) gets the list
 * as a silent companion notification and asks with a ping.
 */
@LoadablePlugin
class GeTvPlugin : Plugin() {
    override val displayName: String get() = context.getString(R.string.tv_apps_title)
    override val description: String get() = context.getString(R.string.tv_apps_desc)
    // The TV sends the list and takes requests (a Mac's as pings); phones the other way round.
    override val supportedPacketTypes = if (TvDevices.thisIsTv) arrayOf(REQUEST, PING) else arrayOf(APPS)
    override val outgoingPacketTypes = if (TvDevices.thisIsTv) arrayOf(APPS, NOTIFICATION) else arrayOf(REQUEST)

    /** On the TV: apps installed or removed while connected. */
    private val packages = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = sendApps()
    }

    override fun onCreate() {
        if (!TvDevices.thisIsTv) return
        sendApps()
        context.registerReceiver(packages, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    override fun onDestroy() {
        if (TvDevices.thisIsTv) runCatching { context.unregisterReceiver(packages) }
    }

    private fun sendApps() = Thread {
        runCatching {
            val apps = appList(context)
            when {
                device.supportsPacketType(APPS) -> device.sendPacket(NetworkPacket(APPS).apply { this["apps"] = apps })
                device.supportsPacketType(NOTIFICATION) -> ge.connect.sync.CompanionMessages.sendStatusNotification(
                    context, device, "$APPS:", "TV apps", JSONObject().put("apps", apps).toString(), silent = true,
                )
            }
        }
    }.start()

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        when {
            np.type == REQUEST && TvDevices.thisIsTv -> sendApps()
            np.type == PING && TvDevices.thisIsTv && np.getString("message") == REQUEST -> sendApps() // from a Mac
            np.type == APPS && !TvDevices.thisIsTv -> np.getJSONArray("apps")?.let { TvApps.save(context, device.deviceId, it) }
            else -> return false
        }
        return true
    }

    companion object {
        const val APPS = "ge.connect.tvapps"
        const val REQUEST = "ge.connect.tvapps.request"
        private const val PING = "kdeconnect.ping"
        private const val NOTIFICATION = "kdeconnect.notification"

        /** On the phone: asks a connected TV for its apps now. @return false if it isn't connected */
        fun request(deviceId: String): Boolean {
            val device = org.kde.kdeconnect.KdeConnect.getInstance().getDevice(deviceId) ?: return false
            if (!device.isReachable) return false
            device.sendPacket(NetworkPacket(REQUEST))
            return true
        }

        /** The TV's launchable apps (as in its app row), banners as small WebP. */
        private fun appList(context: Context): JSONArray {
            val pm = context.packageManager
            val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            val array = JSONArray()
            launchable.filter { it.activityInfo.packageName != context.packageName }
                .distinctBy { it.activityInfo.packageName }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
                .forEach { info ->
                    val banner = info.activityInfo.loadBanner(pm) ?: info.activityInfo.applicationInfo.loadBanner(pm)
                    val image = (banner ?: info.loadIcon(pm))?.let { drawable ->
                        val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        if (banner != null) {
                            drawable.setBounds(0, 0, 160, 90); drawable.draw(canvas)
                        } else {
                            canvas.drawColor(0xFF2B2F38.toInt())
                            val icon = drawable.toBitmap(64, 64)
                            canvas.drawBitmap(icon, 48f, 13f, null)
                        }
                        val out = ByteArrayOutputStream()
                        @Suppress("DEPRECATION") bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
                        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    }
                    array.put(JSONObject().put("package", info.activityInfo.packageName).put("label", info.loadLabel(pm).toString()).put("banner", image))
                }
            return array
        }
    }
}

/** On the phone: each TV's apps, as last sent (kept in a file per TV). */
object TvApps {
    data class App(val packageName: String, val label: String, val banner: Bitmap?)

    private val _apps = MutableStateFlow<Map<String, List<App>>>(emptyMap())
    val apps: StateFlow<Map<String, List<App>>> = _apps.asStateFlow()

    private fun file(context: Context, deviceId: String) = File(context.filesDir, "tv_apps_$deviceId.json")

    fun save(context: Context, deviceId: String, apps: JSONArray) {
        runCatching { file(context, deviceId).writeText(apps.toString()) }
        _apps.value = _apps.value + (deviceId to parse(apps))
    }

    fun load(context: Context, deviceId: String) {
        if (_apps.value.containsKey(deviceId)) return
        val text = runCatching { file(context, deviceId).readText() }.getOrNull() ?: return
        _apps.value = _apps.value + (deviceId to parse(JSONArray(text)))
    }

    private fun parse(array: JSONArray): List<App> = (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        val banner = o.optString("banner").takeIf { it.isNotEmpty() }?.let { b64 ->
            runCatching { Base64.decode(b64, Base64.DEFAULT).let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
        }
        App(o.getString("package"), o.optString("label"), banner)
    }
}
