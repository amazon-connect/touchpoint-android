package com.amazon.connect.touchpoint.core.android

import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.amazon.connect.touchpoint.core.ConversationStore
import com.amazon.connect.touchpoint.core.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration

/**
 * Where Core's storage lives on Android, chosen once so every product agrees.
 *
 * The store goes under `noBackupFilesDir` (layer 1): excluded from Auto Backup and device-to-device
 * transfer, and — being ordinary app storage — credential-encrypted, so unreadable until the first
 * unlock after boot. A host that backs up its whole data directory still never backs this up.
 */
public object AndroidStores {
    private val lock = Mutex()
    private var open: ConversationStore? = null

    /** The SDK's secure store, namespaced so a host's own items are never touched. */
    public fun secureStore(context: Context): SecureStore = KeystoreSecureStore(context)

    /**
     * The durable store, over the platform's own SQLite. **One per process**: every caller gets the
     * same instance while it is open, because two connections to one file would race each other's
     * writes and a key rotation in one would leave the other on a stale key.
     *
     * A bad store — corrupt, or written by a newer schema — is rebuilt rather than thrown (see
     * [ConversationStore.open]). What does throw, as [com.amazon.connect.touchpoint.core.TouchpointException.PersistenceFailure],
     * is a store that cannot be reached at all: a device locked since boot, before credential-encrypted
     * storage is available, or a Keystore that refuses the key.
     */
    public suspend fun conversationStore(
        context: Context,
        secureStore: SecureStore = secureStore(context),
        retention: Duration = ConversationStore.DEFAULT_RETENTION,
    ): ConversationStore =
        lock.withLock {
            open?.takeIf { it.isOpen }?.let { return@withLock it }
            // Resolving `noBackupFilesDir` creates it on first use: disk I/O, so off the caller's thread.
            val directory = withContext(Dispatchers.IO) { directory(context) }
            ConversationStore.open(directory, secureStore, AndroidSQLiteDriver(), retention).also { open = it }
        }

    internal fun directory(context: Context): File = File(context.applicationContext.noBackupFilesDir, "touchpoint/store")
}
