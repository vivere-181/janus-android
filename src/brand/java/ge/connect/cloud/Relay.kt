/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.cloud

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An answer from the relay that isn't a success; [code] is set where the apps act on it (shared/CLOUD.md §9). */
class RelayException(val status: Int, val code: String?, message: String, val serverTime: Long? = null) : IOException(message)

/**
 * The phone's side of janus-relay's HTTP API (shared/CLOUD.md §4, §8): one OkHttp client (HTTP/2, so the four chunk
 * requests in flight share one connection), signing in again on 401. Blocking calls; use them off the main thread.
 */
class Relay(base: String, private val me: CloudCrypto.Identity) {
    val base = base.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS) // a chunk download waits up to 25 s for the upload
        .writeTimeout(90, TimeUnit.SECONDS)
        // Rare: each ping wakes the radio. The relay pings every 150 s (keepalive below); a dead socket after a network
        // change is replaced right away (JanusCloud), this only catches what's left.
        .pingInterval(280, TimeUnit.SECONDS)
        .build()

    @Volatile private var token: String? = null
    @Volatile private var tokenExpires = 0L

    /** `POST /v1/auth`. @throws RelayException with code `unknown_device`, `clock` or `signature` */
    @Synchronized
    fun signIn(): String {
        token?.takeIf { System.currentTimeMillis() / 1000 < tokenExpires - 60 }?.let { return it }
        val ts = System.currentTimeMillis() / 1000
        val body = JSONObject().put("id", me.id).put("ts", ts).put("sig", CloudCrypto.signIn(me, ts))
        client.newCall(Request.Builder().url("$base/v1/auth").post(body.toString().toRequestBody(JSON)).build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw error(res.code, text)
            val o = JSONObject(text)
            tokenExpires = o.optLong("expires", ts + 24 * 3600)
            return o.getString("token").also { token = it }
        }
    }

    private fun forgetToken(used: String) = synchronized(this) { if (token == used) token = null }

    /** A signed-in call; signs in again once on 401. The caller closes the response. */
    fun call(method: String, path: String, body: RequestBody? = null, headers: Map<String, String> = emptyMap()): Response {
        repeat(2) { attempt ->
            val token = signIn()
            val request = Request.Builder().url(base + path).method(method, body)
                .header("Authorization", "Bearer $token")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            val res = client.newCall(request).execute()
            if (res.code != 401 || attempt == 1) return res
            res.close()
            forgetToken(token)
        }
        error("unreachable")
    }

    /** A call answering JSON; any status but 2xx throws. */
    fun json(method: String, path: String, body: JSONObject? = null): JSONObject {
        call(method, path, body?.toString()?.toRequestBody(JSON) ?: if (method == "POST") "{}".toRequestBody(JSON) else null).use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw error(res.code, text)
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    fun webSocket(listener: WebSocketListener): WebSocket {
        val request = Request.Builder().url("$base/v1/ws?keepalive=150").header("Authorization", "Bearer ${signIn()}").build()
        return client.newWebSocket(request, listener)
    }

    fun error(status: Int, text: String): RelayException {
        val o = runCatching { JSONObject(text) }.getOrNull()
        return RelayException(
            status, o?.optString("code")?.takeIf { it.isNotEmpty() }, o?.optString("error")?.takeIf { it.isNotEmpty() } ?: "relay answered $status",
            o?.optLong("serverTime")?.takeIf { it > 0 },
        )
    }

    companion object {
        val JSON = "application/json".toMediaType()
        val BYTES = "application/octet-stream".toMediaType()

        /** A body that reports how much of it has been written (from 0 again when OkHttp retries), for progress. */
        fun counting(bytes: ByteArray, onWritten: (Long) -> Unit) = object : RequestBody() {
            override fun contentType() = BYTES
            override fun contentLength() = bytes.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                var offset = 0
                while (offset < bytes.size) {
                    val n = minOf(64 * 1024, bytes.size - offset)
                    sink.write(bytes, offset, n)
                    offset += n
                    onWritten(offset.toLong())
                }
            }
        }
    }
}
