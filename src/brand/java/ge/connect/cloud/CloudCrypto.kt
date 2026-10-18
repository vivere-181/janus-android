/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.cloud

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Janus Cloud cryptography (shared/CLOUD.md §2, §4–6): Ed25519 to sign in, X25519 + HKDF for the key between two
 * devices, ChaCha20-Poly1305 for envelopes and file chunks. Pure (BouncyCastle's lightweight API), so the test vectors
 * in §12 are checked by a JVM test.
 */
object CloudCrypto {
    const val CHUNK_SIZE = 4 * 1024 * 1024
    private const val TAG_BYTES = 16
    private val random = SecureRandom()

    class Identity(val signSeed: ByteArray, val boxPrivate: ByteArray) {
        private val signKey = Ed25519PrivateKeyParameters(signSeed, 0)
        val signPublic: ByteArray = signKey.generatePublicKey().encoded
        val boxPublic: ByteArray = X25519PrivateKeyParameters(boxPrivate, 0).generatePublicKey().encoded
        val id: String = deviceId(signPublic)

        fun sign(message: ByteArray): ByteArray = Ed25519Signer().run {
            init(true, signKey)
            update(message, 0, message.size)
            generateSignature()
        }

        companion object {
            fun generate() = Identity(ByteArray(32).also(random::nextBytes), ByteArray(32).also(random::nextBytes))
        }
    }

    /** First 16 bytes of SHA-256(Ed25519 public key), lowercase hex. */
    fun deviceId(signPublic: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(signPublic).copyOf(16).toHex()

    /** `janus-auth v1|<id>|<ts>` signed, base64. */
    fun signIn(me: Identity, ts: Long): String = b64(me.sign("janus-auth v1|${me.id}|$ts".toByteArray()))

    fun verify(signPublic: ByteArray, message: ByteArray, signature: ByteArray): Boolean = Ed25519Signer().run {
        init(false, Ed25519PublicKeyParameters(signPublic, 0))
        update(message, 0, message.size)
        verifySignature(signature)
    }

    /** HKDF-SHA256(X25519(own, peer), salt "janus-cloud v1", info "<smaller id>|<larger id>"), 32 bytes. */
    fun pairKey(me: Identity, peerBox: ByteArray, peerId: String): ByteArray {
        val shared = ByteArray(32)
        X25519PrivateKeyParameters(me.boxPrivate, 0).generateSecret(X25519PublicKeyParameters(peerBox, 0), shared, 0)
        val (a, b) = listOf(me.id, peerId).sorted()
        return HKDFBytesGenerator(SHA256Digest()).run {
            init(HKDFParameters(shared, "janus-cloud v1".toByteArray(), "$a|$b".toByteArray()))
            ByteArray(32).also { generateBytes(it, 0, 32) }
        }
    }

    /** base64(nonce ‖ ciphertext ‖ tag); [nonce] only for tests. */
    fun seal(plaintext: ByteArray, key: ByteArray, from: String, to: String, nonce: ByteArray? = null): String {
        val n = nonce ?: ByteArray(12).also(random::nextBytes)
        return b64(n + aead(true, key, n, "janus-env v1|$from|$to".toByteArray(), plaintext))
    }

    /** @throws org.bouncycastle.crypto.InvalidCipherTextException when it wasn't sealed with [key] for this pair */
    fun open(box: String, key: ByteArray, from: String, to: String): ByteArray {
        val data = Base64.getDecoder().decode(box)
        require(data.size >= 12 + TAG_BYTES) { "short box" }
        return aead(false, key, data.copyOf(12), "janus-env v1|$from|$to".toByteArray(), data.copyOfRange(12, data.size))
    }

    fun chunkCount(size: Long): Int = maxOf(1, ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt())
    fun encryptedSize(size: Long): Long = size + TAG_BYTES.toLong() * chunkCount(size)

    fun sealChunk(plain: ByteArray, length: Int, key: ByteArray, blob: String, index: Int, count: Int): ByteArray =
        aead(true, key, chunkNonce(index), chunkAad(blob, index, count), plain, length)

    fun openChunk(sealed: ByteArray, length: Int, key: ByteArray, blob: String, index: Int, count: Int): ByteArray =
        aead(false, key, chunkNonce(index), chunkAad(blob, index, count), sealed, length)

    private fun chunkNonce(index: Int) = ByteBuffer.allocate(12).putInt(0).putLong(index.toLong()).array()
    private fun chunkAad(blob: String, index: Int, count: Int) = "janus-chunk v1|$blob|$index|$count".toByteArray()

    private fun aead(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray, length: Int = input.size): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), TAG_BYTES * 8, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(length))
        val n = cipher.processBytes(input, 0, length, out, 0)
        val total = n + cipher.doFinal(out, n)
        return if (total == out.size) out else out.copyOf(total)
    }

    fun randomKey() = ByteArray(32).also(random::nextBytes)
    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
