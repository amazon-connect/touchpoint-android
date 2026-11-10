package com.amazon.connect.touchpoint.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Secure-storage accessibility classes the SDK is allowed to use.
 *
 * The Android implementation maps [WHEN_UNLOCKED_THIS_DEVICE_ONLY] to an Android Keystore key that
 * is usable only while the device is unlocked and, like every Keystore key, never leaves the device
 * or enters a backup.
 */
public enum class SecureStoreAccessibility {
    /** Readable only while the device is unlocked; never migrates to another device or backup. */
    WHEN_UNLOCKED_THIS_DEVICE_ONLY,
}

/**
 * The secure store. Every platform dependency sits behind an interface so Core is testable with
 * no device; the Android Keystore implementation lives in `touchpoint-core-android`.
 *
 * Implementations namespace their items so a host application's own secrets are never touched.
 */
public interface SecureStore {
    public suspend fun get(key: String): ByteArray?

    public suspend fun set(key: String, value: ByteArray, accessibility: SecureStoreAccessibility)

    public suspend fun delete(key: String)
}

/** Test double. Records the accessibility class each write asked for so tests can assert it. */
public class InMemorySecureStore : SecureStore {
    private val mutex = Mutex()
    private val items = mutableMapOf<String, Pair<ByteArray, SecureStoreAccessibility>>()

    override suspend fun get(key: String): ByteArray? = mutex.withLock { items[key]?.first?.copyOf() }

    override suspend fun set(key: String, value: ByteArray, accessibility: SecureStoreAccessibility) {
        mutex.withLock { items[key] = value.copyOf() to accessibility }
    }

    override suspend fun delete(key: String) {
        mutex.withLock { items.remove(key) }
    }

    public suspend fun allAccessibility(): List<SecureStoreAccessibility> = mutex.withLock { items.values.map { it.second } }
}
