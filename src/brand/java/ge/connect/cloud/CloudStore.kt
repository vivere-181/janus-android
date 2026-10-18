/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A Mac we reach through the relay; its keys came over the LAN (shared/CLOUD.md §1, §3). */
data class CloudPeer(
    /** KDE Connect device id of the Mac. */
    val kdeId: String,
    /** Its relay id (= deviceId of [sign]). */
    val cloudId: String,
    val name: String,
    val sign: String,
    val box: String,
    val relay: String,
    /** The Mac registered us with the relay and said `ready`. */
    val ready: Boolean = false,
    /** The relay doesn't know us any more (`unknown_device`): say hello again over the LAN. */
    val stale: Boolean = false,
) {
    fun toJson() = JSONObject().put("kdeId", kdeId).put("cloudId", cloudId).put("name", name).put("sign", sign)
        .put("box", box).put("relay", relay).put("ready", ready).put("stale", stale)

    companion object {
        fun fromJson(o: JSONObject) = CloudPeer(
            o.getString("kdeId"), o.getString("cloudId"), o.getString("name"), o.getString("sign"), o.getString("box"),
            o.getString("relay"), o.optBoolean("ready"), o.optBoolean("stale"),
        )
    }
}

/**
 * This phone's cloud identity, the Macs it trusts, and the envelope ids it has handled. The private keys are kept in
 * a file encrypted with an AES key that never leaves the Android Keystore; if that can't be read (new phone, wiped
 * Keystore), a new identity is made and the Macs are told over the LAN.
 */
object CloudStore {
    private const val TAG = "CloudStore"
    private const val PREFS = "ge_connect_cloud"
    private const val KEY_ALIAS = "janus_cloud_identity"
    private const val SEEN_MILLIS = 72 * 3_600_000L

    private lateinit var app: Context
    private val dir get() = File(app.filesDir, "cloud").apply { mkdirs() }
    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) { app = context.applicationContext }

    /** True when the identity had to be made anew while Macs were already set up (they must re-add us). */
    @Volatile var identityRenewed = false
        private set

    val identity: CloudCrypto.Identity by lazy { load() ?: create() }

    private fun load(): CloudCrypto.Identity? {
        val file = File(dir, "identity")
        if (!file.exists()) return null
        return runCatching {
            val data = file.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(create = false) ?: error("no key"), GCMParameterSpec(128, data, 0, 12))
            val plain = cipher.doFinal(data, 12, data.size - 12)
            CloudCrypto.Identity(plain.copyOf(32), plain.copyOfRange(32, 64))
        }.onFailure { Log.w(TAG, "Couldn't read the cloud identity; making a new one", it) }.getOrNull()
    }

    private fun create(): CloudCrypto.Identity {
        val identity = CloudCrypto.Identity.generate()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey(create = true))
        val sealed = cipher.iv + cipher.doFinal(identity.signSeed + identity.boxPrivate)
        val file = File(dir, "identity")
        File(dir, "identity.tmp").apply { writeBytes(sealed); renameTo(file) }
        if (peers().isNotEmpty()) {
            identityRenewed = true
            savePeers(peers().map { it.copy(ready = false, stale = true) })
        }
        return identity
    }

    private fun keystoreKey(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    // Peers -----------------------------------------------------------------------------------------------------------

    @Synchronized
    fun peers(): List<CloudPeer> = runCatching {
        val array = JSONArray(prefs().getString("peers", "[]"))
        (0 until array.length()).map { CloudPeer.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun savePeers(peers: List<CloudPeer>) = prefs().edit { putString("peers", JSONArray(peers.map { it.toJson() }).toString()) }

    @Synchronized
    fun update(kdeId: String, change: (CloudPeer?) -> CloudPeer?) {
        val list = peers()
        val updated = change(list.firstOrNull { it.kdeId == kdeId })
        savePeers(list.filterNot { it.kdeId == kdeId } + listOfNotNull(updated))
    }

    // Envelopes already handled (§5: drop one seen in the last 72 h) -------------------------------------------------

    @Synchronized
    fun seen(id: String): Boolean = prefs().getString("seen", null)?.let { JSONObject(it).has(id) } == true

    @Synchronized
    fun markSeen(id: String) {
        val now = System.currentTimeMillis()
        val seen = runCatching { JSONObject(prefs().getString("seen", "{}")!!) }.getOrDefault(JSONObject())
        seen.keys().asSequence().toList().forEach { if (now - seen.optLong(it) > SEEN_MILLIS) seen.remove(it) }
        seen.put(id, now)
        prefs().edit { putString("seen", seen.toString()) }
    }

    /** Testing: send through the cloud and keep the connection open even when the Mac is on the same network. */
    var force: Boolean
        get() = prefs().getBoolean("force", false)
        set(value) = prefs().edit { putBoolean("force", value) }

    fun tempDir() = File(dir, "incoming").apply { mkdirs() }
}
