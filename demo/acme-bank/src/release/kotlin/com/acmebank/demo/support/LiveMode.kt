package com.acmebank.demo.support

import android.content.Context
import androidx.compose.runtime.Composable
import com.amazon.connect.touchpoint.core.ContactTransport
import com.amazon.connect.touchpoint.core.LiveSyncCredentialSource
import com.amazon.connect.touchpoint.core.TouchpointBackend

/**
 * Release builds: no live mode. The debug variant reaches a real Connect agent and Live Sync with
 * credentials typed in or seeded over adb stdin; none of that code — nor the fields that would take a
 * credential — is compiled into a release APK.
 */
object LiveMode {
    const val AVAILABLE: Boolean = false

    const val AUTHENTICATION_REDIRECT: String = ""

    fun backendAndTransport(context: Context): Pair<TouchpointBackend, ContactTransport>? = null

    fun liveSyncCredentials(): LiveSyncCredentialSource? = null

    fun region(): String = ""

    fun connectInstanceUrl(): String? = null

    fun seed(context: Context): Boolean = false

    fun takeContactId(): String? = null

    val voiceAvailable: Boolean = false

    val voiceByPhone: Boolean = false

    fun voiceTransport(context: Context): com.amazon.connect.touchpoint.voice.ConnectVoiceTransport? = null

    suspend fun placeCall(attributes: Map<String, String>): String = throw com.amazon.connect.touchpoint.core.TouchpointException.InvalidConfiguration("voice mode is debug-only")

    @Composable fun SettingsSection() = Unit

    val configured: Boolean = false

    fun currentFlow(): String = "default"

    val realSignIn: Boolean = false

    val hasSignedInUser: Boolean = false

    suspend fun signInUser(username: String, password: String) = Unit

    fun signOutUser() = Unit

    val keysLoaded: Boolean = true

    val hasLiveSyncKeys: Boolean = false

    val savedDeploymentKey: String = ""

    val savedApiKey: String = ""

    suspend fun loadSavedKeys(context: Context) = Unit

    suspend fun setKeys(context: Context, deploymentKey: String, apiKey: String) = Unit

    suspend fun forgetKeys(context: Context) = Unit
}
