/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** shared/CLOUD.md §12: the same bytes as the Mac (CryptoKit) and the Go reference client. */
class CloudCryptoTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val mac = CloudCrypto.Identity(
        hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
        hex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"),
    )
    private val phone = CloudCrypto.Identity(
        hex("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f"),
        hex("606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f"),
    )

    @Test
    fun identities() {
        assertEquals("A6EHv/POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg=", CloudCrypto.b64(mac.signPublic))
        assertEquals("NYBy1jZYgNGu6jKa35EhODhR7SGijjt16WXQ0s0WYlQ=", CloudCrypto.b64(mac.boxPublic))
        assertEquals("56475aa75463474c0285df5dbf2bcab7", mac.id)
        assertEquals("JUO5L/EJVRFHatyDadtt3JM2ZaEZeN2hQE7hBmypVZ0=", CloudCrypto.b64(phone.signPublic))
        assertEquals("Z13VdO13iTELPS52gfN5C0ZsdzsVIf7PNld5WDcepS8=", CloudCrypto.b64(phone.boxPublic))
        assertEquals("03396219237f75a64f12aeb7f39723ab", phone.id)
    }

    @Test
    fun pairKeyBothWays() {
        val expected = "a7201096664a3a9f67bbe4b72295243f35e7ecad6f27d29c968e7f964cf1dedd"
        assertEquals(expected, CloudCrypto.pairKey(mac, phone.boxPublic, phone.id).hex())
        assertEquals(expected, CloudCrypto.pairKey(phone, mac.boxPublic, mac.id).hex())
    }

    @Test
    fun envelope() {
        val plaintext = """{"t":"file","id":"6f1c2a9e-0d1b-4c55-9a7e-3b2f4d6e8a10","ts":1760000000000,"blob":"0123456789abcdef0123456789abcdef","name":"photo.jpg","size":5000000,"mime":"image/jpeg","chunk":4194304,"count":2,"key":"oKGio6SlpqeoqaqrrK2ur7CxsrO0tba3uLm6u7y9vr8="}"""
        val box = "AAECAwQFBgcICQoLp9hgNiPdDm5K/WQUawh/9LRMe8Hj3q9cUqbroliL7VEqJd05NAk1IxKnv3zc6pxInOdRCWEHKQtuJ/qUAZDV1qYQYGNMIy5BXpIsP2d4A4qPOK2ZNAobgB0U8qRNMZJXO1x9uioOJuw09f8JtVLYJpy+Vp6Qs1Dqym9A1iubhTQVXNiw7nhKyvpQxvDGWK05R6+gOwF+RLe8g5Iby/Muazhv3/QHhWDP9vaT+//2UrfqF0SPK8DPXNpAFkzjHCMW3ucM67qtznHh80qThFPsnvZPwgpdhFbdJZHhhe1oZH04Y6k4fTq355MMz2/a9QIKx0ev/GitdR2xO22uTV0k2PWFf5YJzSJsvsY="
        val key = CloudCrypto.pairKey(mac, phone.boxPublic, phone.id)
        assertEquals(box, CloudCrypto.seal(plaintext.toByteArray(), key, mac.id, phone.id, hex("000102030405060708090a0b")))
        assertEquals(plaintext, String(CloudCrypto.open(box, CloudCrypto.pairKey(phone, mac.boxPublic, mac.id), mac.id, phone.id)))
        // The other direction's AAD doesn't open it.
        assertTrue(runCatching { CloudCrypto.open(box, key, phone.id, mac.id) }.isFailure)
    }

    @Test
    fun chunk() {
        val key = hex("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf")
        val plain = "Janus chunk test vector".toByteArray()
        val sealed = CloudCrypto.sealChunk(plain, plain.size, key, "0123456789abcdef0123456789abcdef", 1, 2)
        assertEquals("8161f3877e7c88a533371772836122339e5f22b53073d1c4880a8d0cfede6d0ebf7807ea45c2aa", sealed.hex())
        assertArrayEquals(plain, CloudCrypto.openChunk(sealed, sealed.size, key, "0123456789abcdef0123456789abcdef", 1, 2))
        assertEquals(2, CloudCrypto.chunkCount(5_000_000))
        assertEquals(1, CloudCrypto.chunkCount(0))
        assertEquals(5_000_032, CloudCrypto.encryptedSize(5_000_000))
    }

    @Test
    fun signIn() {
        val sig = CloudCrypto.unb64(CloudCrypto.signIn(mac, 1760000000))
        assertTrue(CloudCrypto.verify(mac.signPublic, "janus-auth v1|56475aa75463474c0285df5dbf2bcab7|1760000000".toByteArray(), sig))
    }
}
