package com.amazon.connect.touchpoint.core.android

import android.content.pm.PackageManager
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amazon.connect.touchpoint.core.SecureStoreAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** Runs on a device: AndroidKeyStore does not exist on the JVM. `make connected`. */
@RunWith(AndroidJUnit4::class)
class KeystoreSecureStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "test.${UUID.randomUUID()}"
    private val store = KeystoreSecureStore(context, namespace)

    @After
    fun cleanUp() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("$namespace.securestore.v1")
        File(context.noBackupFilesDir, "touchpoint/securestore").deleteRecursively()
    }

    @Test
    fun roundTripsOverwritesAndDeletes() = runBlocking {
        store.set("a", "one".encodeToByteArray(), WHEN_UNLOCKED_THIS_DEVICE_ONLY)
        assertArrayEquals("one".encodeToByteArray(), store.get("a"))
        store.set("a", "two".encodeToByteArray(), WHEN_UNLOCKED_THIS_DEVICE_ONLY)
        assertArrayEquals("two".encodeToByteArray(), store.get("a"))
        store.delete("a")
        assertNull(store.get("a"))
    }

    @Test
    fun theKeyIsInStrongBoxWhereThePhoneHasIt() = runBlocking {
        store.set("a", "one".encodeToByteArray(), WHEN_UNLOCKED_THIS_DEVICE_ONLY)
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("$namespace.securestore.v1", null) as SecretKey
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        // StrongBox where declared; otherwise the TEE at least, never software.
        if (strongBox) assertEquals(KeyProperties.SECURITY_LEVEL_STRONGBOX, info.securityLevel)
        else assertEquals(KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT, info.securityLevel)
        assertArrayEquals("one".encodeToByteArray(), store.get("a"))
    }

    @Test
    fun anAbsentItemReadsAsNull() = runBlocking {
        assertNull(store.get("never-written"))
    }

    @Test
    fun valuesAreCiphertextOnDiskUnderHashedNames() = runBlocking {
        store.set("participant-token", "PLAINTEXT-MARKER".encodeToByteArray(), WHEN_UNLOCKED_THIS_DEVICE_ONLY)
        val files = File(context.noBackupFilesDir, "touchpoint/securestore").walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, files.size)
        assertFalse(files[0].readBytes().decodeToString().contains("PLAINTEXT-MARKER"))
        assertFalse(files[0].name.contains("participant-token"))
    }

    @Test
    fun anItemWhoseKeyWasDeletedReadsAsAbsent() = runBlocking {
        store.set("a", "one".encodeToByteArray(), WHEN_UNLOCKED_THIS_DEVICE_ONLY)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("$namespace.securestore.v1")
        assertNull(store.get("a"))
        store.set("a", "again".encodeToByteArray(), WHEN_UNLOCKED_THIS_DEVICE_ONLY) // a fresh key is minted
        assertArrayEquals("again".encodeToByteArray(), store.get("a"))
    }

    @Test
    fun namespacesDoNotSeeEachOthersItems() = runBlocking {
        val other = KeystoreSecureStore(context, "$namespace.other")
        try {
            store.set("a", "mine".encodeToByteArray(), WHEN_UNLOCKED_THIS_DEVICE_ONLY)
            assertNull(other.get("a"))
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("$namespace.other.securestore.v1")
        }
    }

    private fun itemFiles(): List<File> =
        File(context.noBackupFilesDir, "touchpoint/securestore").walkTopDown().filter { it.isFile }.toList()

    @Test
    fun aTamperedItemReadsAsAbsentAndIsRemoved() = runBlocking {
        store.set("a", "one".encodeToByteArray(), WHEN_UNLOCKED_THIS_DEVICE_ONLY)
        val file = itemFiles().single()
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(store.get("a")) // the hardware key's AEADBadTagException maps to absent
        assertFalse(file.exists())
    }

    @Test
    fun aTruncatedItemReadsAsAbsent() = runBlocking {
        store.set("a", "one".encodeToByteArray(), WHEN_UNLOCKED_THIS_DEVICE_ONLY)
        val file = itemFiles().single()
        file.writeBytes(file.readBytes().copyOf(5))
        assertNull(store.get("a"))
    }

    @Test
    fun twoInstancesOfOneNamespaceShareOneKey() = runBlocking {
        val twin = KeystoreSecureStore(context, namespace)
        store.set("a", "one".encodeToByteArray(), WHEN_UNLOCKED_THIS_DEVICE_ONLY)
        twin.set("b", "two".encodeToByteArray(), WHEN_UNLOCKED_THIS_DEVICE_ONLY)
        assertArrayEquals("one".encodeToByteArray(), twin.get("a"))
        assertArrayEquals("two".encodeToByteArray(), store.get("b"))
    }
}
