/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.pairing

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import ge.connect.devices.DeviceOwnership
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect_tp.R
import java.net.InetAddress

/**
 * Pairing with a Mac from the QR code it shows (R41): `janus://pair?v=1&id&name&addr&port&fp&code`. The phone connects
 * straight to the Mac (no network discovery), accepts it only if its certificate hashes to `fp`, and asks to pair
 * with the one-time `code`, which the Mac accepts without asking. The Mac becomes one of the owner's own devices.
 */
object QrPairing {
    private const val TAG = "QrPairing"

    class Code(
        val id: String,
        val name: String,
        val addresses: List<String>,
        val port: Int,
        val fingerprint: ByteArray,
        val code: String,
    )

    sealed interface Result {
        data class Paired(val deviceId: String, val name: String) : Result
        data class Failed(val message: String) : Result
    }

    /** The code from a scanned QR or an opened link, or null if it isn't one of ours. */
    fun parse(text: String): Code? = runCatching {
        val uri = Uri.parse(text.trim())
        if (uri.scheme != "janus" || uri.host != "pair") return null
        if ((uri.getQueryParameter("v") ?: "1") != "1") return null
        val id = uri.getQueryParameter("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_]{16,64}")) } ?: return null
        val addresses = uri.getQueryParameter("addr")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val port = uri.getQueryParameter("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val fingerprint = Base64.decode(uri.getQueryParameter("fp") ?: return null, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val code = uri.getQueryParameter("code")?.takeIf { it.length >= 16 } ?: return null
        if (addresses.isEmpty() || fingerprint.size != 32) return null
        Code(id, uri.getQueryParameter("name").orEmpty().ifEmpty { "Mac" }, addresses, port, fingerprint, code)
    }.getOrNull()

    /** Connects and pairs. Takes up to ~40 s (the Mac answers at once; the rest is for slow networks). */
    suspend fun pair(context: Context, code: Code): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        existing(code.id)?.takeIf { it.isPaired }?.let { device ->
            DeviceOwnership.set(app, device.deviceId, mine = true)
            return@withContext Result.Paired(device.deviceId, device.name)
        }
        val provider = provider(app) ?: return@withContext Result.Failed(app.getString(R.string.qr_pair_failed_service))

        // Each address in turn until one connects; the link then shows up as a device.
        var connected = false
        for (address in code.addresses) {
            try {
                provider.connectDirectly(InetAddress.getByName(address), code.port, code.id, code.name, code.fingerprint)
                if (waitFor(10_000) { existing(code.id)?.isReachable == true }) { connected = true; break }
            } catch (e: Exception) {
                Log.i(TAG, "Couldn't connect to $address:${code.port}: ${e.message}")
            }
        }
        val device = existing(code.id)?.takeIf { connected && it.isReachable }
            ?: return@withContext Result.Failed(app.getString(R.string.qr_pair_failed_connect, code.name))

        if (!device.isPaired) {
            withContext(Dispatchers.Main) { device.requestPairing(janusPairCode = code.code) }
            if (!waitFor(35_000) { device.isPaired }) return@withContext Result.Failed(app.getString(R.string.qr_pair_failed_pair, code.name))
        }
        DeviceOwnership.set(app, device.deviceId, mine = true)
        Log.i(TAG, "Paired with ${device.name} from its code")
        Result.Paired(device.deviceId, device.name)
    }

    private fun existing(id: String): Device? = KdeConnect.getInstance().getDevice(id)

    /** The Wi-Fi link provider, starting Janus's service first if it isn't running yet. */
    private suspend fun provider(context: Context) = run {
        if (BackgroundService.instance == null) withContext(Dispatchers.Main) { BackgroundService.Start(context) }
        var provider = BackgroundService.instance?.lanLinkProvider
        var waited = 0
        while (provider == null && waited < 5_000) { delay(100); waited += 100; provider = BackgroundService.instance?.lanLinkProvider }
        provider
    }

    private suspend fun waitFor(millis: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            delay(150)
        }
        return condition()
    }
}
