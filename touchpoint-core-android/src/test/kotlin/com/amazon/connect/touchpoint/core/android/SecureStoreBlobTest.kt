package com.amazon.connect.touchpoint.core.android

import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SecureStoreBlobTest {
    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `round-trips a value`() {
        val k = key()
        val blob = SecureStoreBlob.seal(k, "ns/item", "secret".encodeToByteArray())
        assertContentEquals("secret".encodeToByteArray(), SecureStoreBlob.open(k, "ns/item", blob))
    }

    @Test
    fun `the value never appears in the blob`() {
        val blob = SecureStoreBlob.seal(key(), "ns/item", "PLAINTEXT-MARKER".encodeToByteArray())
        assertFalse(blob.decodeToString().contains("PLAINTEXT-MARKER"))
    }

    @Test
    fun `a blob does not open under another item's name`() {
        val k = key()
        val blob = SecureStoreBlob.seal(k, "ns/token", "secret".encodeToByteArray())
        assertFailsWith<GeneralSecurityException> { SecureStoreBlob.open(k, "ns/field-key", blob) }
    }

    @Test
    fun `a blob does not open under another key`() {
        val blob = SecureStoreBlob.seal(key(), "ns/item", "secret".encodeToByteArray())
        assertFailsWith<GeneralSecurityException> { SecureStoreBlob.open(key(), "ns/item", blob) }
    }

    @Test
    fun `a tampered blob fails authentication`() {
        val k = key()
        val blob = SecureStoreBlob.seal(k, "ns/item", "secret".encodeToByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertFailsWith<GeneralSecurityException> { SecureStoreBlob.open(k, "ns/item", blob) }
    }

    @Test
    fun `a blob this format did not write is malformed, not a crash`() {
        val k = key()
        assertFailsWith<SecureStoreBlob.MalformedBlobException> { SecureStoreBlob.open(k, "ns/item", byteArrayOf()) }
        assertFailsWith<SecureStoreBlob.MalformedBlobException> { SecureStoreBlob.open(k, "ns/item", byteArrayOf(9, 12, 1, 2)) }
        assertFailsWith<SecureStoreBlob.MalformedBlobException> { SecureStoreBlob.open(k, "ns/item", byteArrayOf(1, 12, 1, 2)) }
        // Our version byte with a nonce length the Keystore would refuse on every read.
        assertFailsWith<SecureStoreBlob.MalformedBlobException> { SecureStoreBlob.open(k, "ns/item", byteArrayOf(1, 16) + ByteArray(40)) }
    }
}
