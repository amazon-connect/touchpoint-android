package com.amazon.connect.touchpoint.core.android

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The on-disk form of one secure-store item: `version(1) | ivLength(1) | iv | ciphertext+tag`, AES-GCM
 * with the item's name as associated data — so a file copied over another item's file fails
 * authentication instead of silently answering for it.
 *
 * Pure `javax.crypto`, so the format, the tamper check and the wrong-key behaviour are tested on the
 * JVM with a software key; [KeystoreSecureStore] supplies the hardware-held one.
 */
internal object SecureStoreBlob {
    private const val VERSION: Byte = 1
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** GCM's standard nonce, and the only length an Android Keystore key accepts. */
    private const val IV_BYTES = 12

    /** The cipher is initialised without an IV: an Android Keystore key generates its own and refuses ours. */
    fun seal(key: SecretKey, name: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(name.encodeToByteArray())
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        return ByteBuffer.allocate(2 + iv.size + ciphertext.size)
            .put(VERSION).put(iv.size.toByte()).put(iv).put(ciphertext).array()
    }

    /** Throws [MalformedBlobException] for a blob this format did not write; [GeneralSecurityException] for a wrong key or tampering. */
    fun open(key: SecretKey, name: String, blob: ByteArray): ByteArray {
        if (blob.size < 2 || blob[0] != VERSION) throw MalformedBlobException()
        val ivLength = blob[1].toInt()
        // Any other IV length is not ours, and the Keystore would refuse it on every read: malformed,
        // so the item reads as absent rather than failing for good.
        if (ivLength != IV_BYTES || blob.size < 2 + ivLength + TAG_BITS / 8) throw MalformedBlobException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 2, ivLength))
        cipher.updateAAD(name.encodeToByteArray())
        return cipher.doFinal(blob, 2 + ivLength, blob.size - 2 - ivLength)
    }

    class MalformedBlobException : Exception("not a secure-store blob")
}
