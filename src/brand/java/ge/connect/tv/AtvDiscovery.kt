/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Finds Android TVs on the Wi-Fi by their remote service (_androidtvremote2._tcp), as the Google TV app does. */
object AtvDiscovery {
    private const val TYPE = "_androidtvremote2._tcp."

    /** The address of the TV named like [name] (or the only one there is), waiting up to [timeoutMillis]. Blocking. */
    fun find(context: Context, name: String?, timeoutMillis: Long = 4_000): String? {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        val found = java.util.Collections.synchronizedList(mutableListOf<NsdServiceInfo>())
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { found += info }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(type: String?) = Unit
            override fun onDiscoveryStopped(type: String?) = Unit
            override fun onStartDiscoveryFailed(type: String?, code: Int) = Unit
            override fun onStopDiscoveryFailed(type: String?, code: Int) = Unit
        }
        runCatching { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { return null }
        try {
            Thread.sleep(timeoutMillis.coerceAtMost(2_500))
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        val candidates = found.toList()
        Log.i("AtvDiscovery", "Found ${candidates.map { it.serviceName }}")
        val words = name.orEmpty().lowercase().split(' ').filter { it.length > 2 }
        val pick = candidates.firstOrNull { c -> words.any { c.serviceName.lowercase().contains(it) } } ?: candidates.singleOrNull() ?: return null
        return resolve(nsd, pick, timeoutMillis)
    }

    @Suppress("DEPRECATION")
    private fun resolve(nsd: NsdManager, info: NsdServiceInfo, timeoutMillis: Long): String? {
        var host: String? = null
        val done = CountDownLatch(1)
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo?, code: Int) = done.countDown()
            override fun onServiceResolved(info: NsdServiceInfo) { host = info.host?.hostAddress; done.countDown() }
        })
        done.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return host
    }
}
