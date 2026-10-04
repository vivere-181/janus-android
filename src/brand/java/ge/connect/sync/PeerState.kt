/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.sync

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.battery.BatteryPlugin
import org.kde.kdeconnect_tp.R

/**
 * What we know about another phone running our app (phone ↔ phone "at a glance"): ringer, Do Not Disturb and
 * flashlight from its status reports (ge.connect.status), mobile signal from its connectivity reports. Battery comes
 * from BatteryPlugin. Also changes those settings remotely and warns when its battery runs low.
 */
object PeerState {
    enum class Ringer { NORMAL, VIBRATE, SILENT }
    data class Peer(
        val ringer: Ringer? = null,
        val dnd: Boolean? = null,
        val flashlight: Boolean? = null,
        val networkType: String? = null,
        /** 0–4 bars, or null when unknown. */
        val signal: Int? = null,
    )

    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    private const val CALLBACK_KEY = "ge.connect.PeerState"
    private const val CHANNEL = "ge_peer_battery"
    private const val LOW = 15
    private const val RESET = 25
    private lateinit var app: Context
    private val lowAlerted = HashSet<String>()
    private val watched = HashSet<String>()

    fun of(deviceId: String) = _peers.value[deviceId] ?: Peer()
    fun isPhone(device: Device) = device.deviceType == DeviceType.PHONE || device.deviceType == DeviceType.TABLET

    fun start(context: Context) {
        app = context.applicationContext
        KdeConnect.getInstance().addDeviceListChangedCallback(CALLBACK_KEY) {
            synchronized(watched) {
                KdeConnect.getInstance().devices.values.filter { isPhone(it) && watched.add(it.deviceId) }
                    .forEach { device -> device.addPluginsChangedListener { checkBattery(it) } }
            }
        }
    }

    private fun change(deviceId: String, block: (Peer) -> Peer) {
        _peers.update { it + (deviceId to block(it[deviceId] ?: Peer())) }
        ge.connect.widget.MacWidgetUpdates.request()
    }

    /** "ringer=normal;dnd=off;flashlight=off" from the other phone's PhoneStatus. */
    fun applyStatus(deviceId: String, text: String) {
        val values = text.split(';').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        change(deviceId) { peer ->
            peer.copy(
                ringer = when (values["ringer"]) { "normal" -> Ringer.NORMAL; "vibrate" -> Ringer.VIBRATE; "silent" -> Ringer.SILENT; else -> peer.ringer },
                dnd = values["dnd"]?.let { it == "on" } ?: peer.dnd,
                flashlight = values["flashlight"]?.let { it == "on" } ?: peer.flashlight,
            )
        }
    }

    /** kdeconnect.connectivity_report: the strongest SIM's network type and bars. */
    fun applyConnectivity(deviceId: String, np: NetworkPacket) {
        val strengths = np.getJSONObject("signalStrengths") ?: return
        val best = strengths.keys().asSequence().mapNotNull { strengths.optJSONObject(it) }.maxByOrNull { it.optInt("signalStrength", -1) }
        change(deviceId) { it.copy(networkType = best?.optString("networkType")?.takeIf { t -> t.isNotEmpty() && t != "Unknown" }, signal = best?.optInt("signalStrength")) }
    }

    // Remote control (the same ge.connect commands the Mac app sends) ------------------------------------------------

    private fun command(device: Device, command: String) =
        device.sendPacket(NetworkPacket("kdeconnect.ping").apply { this["message"] = "ge.connect.$command" })

    fun ring(device: Device) = device.sendPacket(NetworkPacket("kdeconnect.findmyphone.request"))

    fun setRinger(device: Device, ringer: Ringer) {
        change(device.deviceId) { it.copy(ringer = ringer) } // its report confirms or corrects this
        command(device, "ringer:" + ringer.name.lowercase())
    }

    fun setDnd(device: Device, on: Boolean) {
        change(device.deviceId) { it.copy(dnd = on) }
        command(device, if (on) "dnd:on" else "dnd:off")
    }

    fun setFlashlight(device: Device, on: Boolean) {
        change(device.deviceId) { it.copy(flashlight = on) }
        command(device, if (on) "flashlight:on" else "flashlight:off")
    }

    // Low battery --------------------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun checkBattery(device: Device) {
        if (!::app.isInitialized || !isPhone(device)) return
        val battery = device.getPlugin(BatteryPlugin::class.java)?.remoteBatteryInfo ?: return
        val manager = NotificationManagerCompat.from(app)
        val id = device.deviceId.hashCode()
        synchronized(lowAlerted) {
            if (battery.isCharging || battery.currentCharge >= RESET) {
                if (lowAlerted.remove(device.deviceId)) manager.cancel(CHANNEL, id)
                return
            }
            if (battery.currentCharge > LOW || !lowAlerted.add(device.deviceId)) return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, app.getString(R.string.peer_battery_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        manager.notify(
            CHANNEL, id,
            NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ms_battery_alert)
                .setContentTitle(app.getString(R.string.peer_battery_low, device.name))
                .setContentText(app.getString(R.string.peer_battery_low_text, battery.currentCharge))
                .setAutoCancel(true)
                .build(),
        )
    }
}
