/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.kde.kdeconnect.helpers.TrustedNetworkHelper

/**
 * Extra phone stats for the Mac's phone card (PROTOCOL.md (janus-mac docs) → ge.connect.status, R24), as status keys:
 * wifi (needs location permission, else omitted), carrier, net, storage=free/total, os.
 */
object DeviceStats {
    private const val GB = 1L shl 30
    @Volatile private var storage: Pair<Long, Long>? = null

    fun keys(context: Context): List<String> = listOfNotNull(
        wifi(context)?.let { "wifi=$it" },
        carrier(context)?.let { "carrier=$it" },
        network(context)?.let { "net=$it" },
        storage()?.let { (free, total) -> "storage=$free/$total" },
        "os=" + clean(os),
    )

    /** Values can't contain the format's separators. */
    private fun clean(value: String) = value.replace(';', ',').replace('=', '-').trim()

    private fun wifi(context: Context): String? {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return null
        return runCatching { TrustedNetworkHelper(context).getCurrentSSID() }.getOrNull()
            ?.removeSurrounding("\"")?.let(::clean)?.takeIf { it.isNotEmpty() }
    }

    private fun carrier(context: Context): String? =
        runCatching { context.getSystemService(TelephonyManager::class.java).networkOperatorName }.getOrNull()
            ?.let(::clean)?.takeIf { it.isNotEmpty() }

    /** Cellular data type (needs READ_PHONE_STATE, which the call features already have). */
    private fun network(context: Context): String? = runCatching {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        when (context.getSystemService(TelephonyManager::class.java).dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_TD_SCDMA,
            TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_GSM -> "2G"
            else -> null
        }
    }.getOrNull()

    /** Internal storage; the reported value only moves after a change of about 1 GB, so it doesn't cause reports. */
    private fun storage(): Pair<Long, Long>? = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        val now = stat.availableBytes to stat.totalBytes
        val previous = storage
        if (previous == null || kotlin.math.abs(previous.first - now.first) >= GB || previous.second != now.second) storage = now
        storage
    }.getOrNull()

    private val os by lazy { listOfNotNull("Android ${Build.VERSION.RELEASE}", skin).joinToString(" · ") }

    /** The maker's Android version, e.g. "One UI 8.5" (Samsung only for now). */
    val skin: String? by lazy {
        // e.g. 80500 → One UI 8.5
        prop("ro.build.version.oneui")?.toIntOrNull()?.let { "One UI ${it / 10000}.${(it / 100) % 100}" }
    }

    /** A system property as apps may read it (via getprop; some are hidden from apps), or null. */
    fun prop(name: String): String? = runCatching {
        Runtime.getRuntime().exec(arrayOf("getprop", name)).inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}
