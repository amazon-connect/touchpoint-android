package com.amazon.connect.touchpoint.core.android

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.amazon.connect.touchpoint.core.SecureStore
import com.amazon.connect.touchpoint.core.SecureStoreAccessibility
import com.amazon.connect.touchpoint.core.TouchpointException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The Android secure store. Each item is AES-256-GCM ciphertext in its own file under
 * `noBackupFilesDir`, sealed with a key generated inside **AndroidKeyStore** that:
 * - never leaves secure hardware, and so never enters a backup or reaches another device — what
 *   [SecureStoreAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY] asks for;
 * - is usable only while the device is unlocked (`setUnlockedDeviceRequired`);
 * - lives in **StrongBox** — a separate secure chip — where the phone has one, else in the TEE
 *   (StrongBox is optional hardware). It is not bound to a biometric or a recent unlock: the store
 *   is read when a notification is tapped and when a conversation resumes in the background.
 *
 * Failure semantics, chosen so a lost key degrades instead of crashing:
 * - an item whose key is gone, whose ciphertext does not authenticate, or whose file this format did
 *   not write reads as **absent** and is deleted — the broker re-vends, the store resets;
 * - a key the system invalidated, or a key entry that no longer loads while the device is unlocked
 *   (a corrupted entry, seen on some devices), is deleted with every item it sealed, and a new one
 *   is minted on the next write;
 * - anything else — the device is locked, the Keystore is unavailable — is
 *   [TouchpointException.PersistenceFailure] and **deletes nothing**: a transient condition must
 *   never destroy the store's encryption key.
 *
 * Every failure leaves as a [TouchpointException]; no `java.security` exception escapes.
 *
 * File names are a hash of the item name, so the directory does not list what is stored. Instances
 * sharing a [namespace] share one lock, so two of them cannot both mint a key and orphan the other's
 * items. Constructing one touches no disk.
 *
 * @param namespace Keeps two stores in one app — or the SDK and a host using this class — apart.
 */
public class KeystoreSecureStore(
    context: Context,
    private val namespace: String = "com.amazon.connect.touchpoint",
) : SecureStore {
    private val context = context.applicationContext
    private val directory by lazy { File(this.context.noBackupFilesDir, "touchpoint/securestore/${hash(namespace).take(16)}") }
    private val alias = "$namespace.securestore.v1"
    private val mutex = locks.getOrPut(namespace) { Mutex() }

    override suspend fun get(key: String): ByteArray? =
        io("read") {
            val file = fileFor(key)
            if (!file.isFile) return@io null
            val blob = file.readBytes()
            val secret =
                when (val loaded = loadKey()) {
                    KeyLookup.Absent -> {
                        file.delete() // sealed under a key that no longer exists: unreadable for good
                        return@io null
                    }
                    KeyLookup.Lost -> {
                        discardKeyAndItems()
                        return@io null
                    }
                    is KeyLookup.Found -> loaded.key
                }
            try {
                SecureStoreBlob.open(secret, itemName(key), blob)
            } catch (_: KeyPermanentlyInvalidatedException) {
                discardKeyAndItems()
                null
            } catch (_: AEADBadTagException) {
                file.delete()
                null
            } catch (_: SecureStoreBlob.MalformedBlobException) {
                file.delete()
                null
            }
        }

    override suspend fun set(key: String, value: ByteArray, accessibility: SecureStoreAccessibility) {
        io("write") {
            // One accessibility class exists, and the key is configured for it. A second class would
            // take a second key with its own parameters, not a flag on this one.
            when (accessibility) {
                SecureStoreAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY -> Unit
            }
            val secret =
                when (val loaded = loadKey()) {
                    KeyLookup.Absent -> generateKey()
                    KeyLookup.Lost -> {
                        discardKeyAndItems()
                        generateKey()
                    }
                    is KeyLookup.Found -> loaded.key
                }
            val blob =
                try {
                    SecureStoreBlob.seal(secret, itemName(key), value)
                } catch (_: KeyPermanentlyInvalidatedException) {
                    discardKeyAndItems()
                    SecureStoreBlob.seal(generateKey(), itemName(key), value)
                }
            writeAtomically(fileFor(key), blob)
        }
    }

    /** Throws if the item exists and cannot be removed: a caller invalidating a credential must know it is still there. */
    override suspend fun delete(key: String) {
        io("delete") {
            val file = fileFor(key)
            if (file.exists() && !file.delete()) throw IOException("cannot delete secure store item")
        }
    }

    /**
     * Serialised per namespace, on the I/O dispatcher. Everything the platform can throw — I/O,
     * `GeneralSecurityException`, and the `ProviderException` a failed Keystore operation raises — is
     * mapped here, so no path through [block] can leak one.
     */
    private suspend fun <T> io(
        operation: String,
        block: () -> T,
    ): T =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    block()
                } catch (e: IOException) {
                    throw TouchpointException.PersistenceFailure("secure store $operation failed: ${e.javaClass.simpleName}")
                } catch (e: GeneralSecurityException) {
                    throw TouchpointException.PersistenceFailure("secure store $operation failed: ${e.javaClass.simpleName}")
                } catch (e: ProviderException) {
                    throw TouchpointException.PersistenceFailure("secure store $operation failed: ${e.javaClass.simpleName}")
                }
            }
        }

    private fun itemName(key: String): String = "$namespace/$key"

    private fun fileFor(key: String): File = File(directory, hash(itemName(key)))

    private fun writeAtomically(target: File, bytes: ByteArray) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create secure store directory")
        val tmp = File(directory, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("cannot replace secure store item")
        }
    }

    private sealed interface KeyLookup {
        data object Absent : KeyLookup

        /** The entry exists but will never load again. */
        data object Lost : KeyLookup

        class Found(val key: SecretKey) : KeyLookup
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun loadKey(): KeyLookup =
        try {
            (keyStore().getKey(alias, null) as? SecretKey)?.let(KeyLookup::Found) ?: KeyLookup.Absent
        } catch (e: UnrecoverableKeyException) {
            // Corrupted for good — but only believed while the device is unlocked. While it is
            // locked the same exception may be the lock talking, and deleting the key then would
            // destroy the store over a transient condition.
            if (deviceLocked()) throw e else KeyLookup.Lost
        }

    private fun deviceLocked(): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked ?: true

    private fun generateKey(): SecretKey {
        // StrongBox where the phone declares it; a phone that declares it and then cannot make the key
        // there gets the TEE's, rather than no store.
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
            try {
                return generateKey(strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
            }
        }
        return generateKey(strongBox = false)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec =
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUnlockedDeviceRequired(true)
                .setIsStrongBoxBacked(strongBox)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    /** The items go even if the entry will not delete: generating under the alias replaces it anyway. */
    private fun discardKeyAndItems() {
        try {
            keyStore().deleteEntry(alias)
        } catch (_: GeneralSecurityException) {
        }
        directory.listFiles()?.forEach { it.delete() }
    }

    internal companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private val locks = ConcurrentHashMap<String, Mutex>()

        fun hash(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
