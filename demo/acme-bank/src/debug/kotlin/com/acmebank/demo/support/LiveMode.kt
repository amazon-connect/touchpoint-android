package com.acmebank.demo.support

import com.amazon.connect.touchpoint.voice.secureFromScreenShare
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import com.amazon.connect.touchpoint.voice.ConnectVoiceTransport
import com.amazon.connect.touchpoint.voice.redactedFromScreenShare

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.acmebank.demo.BuildConfig
import com.acmebank.demo.R
import com.amazon.connect.touchpoint.core.SecureStoreAccessibility
import com.amazon.connect.touchpoint.core.android.AndroidStores
import com.amazon.connect.touchpoint.chat.ChatAuthentication
import com.amazon.connect.touchpoint.chat.ConnectChatTransport
import com.amazon.connect.touchpoint.core.AuthenticationCompletion
import com.amazon.connect.touchpoint.core.AuthenticationRelaying
import com.amazon.connect.touchpoint.core.ChatContactCredentials
import com.amazon.connect.touchpoint.core.ChatContactRequest
import com.amazon.connect.touchpoint.core.ContactTransport
import com.amazon.connect.touchpoint.core.CredentialBroker
import com.amazon.connect.touchpoint.core.InMemorySecureStore
import com.amazon.connect.touchpoint.core.LiveSyncCredentialSource
import com.amazon.connect.touchpoint.core.LiveSyncCredentials
import com.amazon.connect.touchpoint.core.TouchpointBackend
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.VoiceContactCredentials
import com.amazon.connect.touchpoint.core.VoiceContactRequest
import com.amazon.connect.touchpoint.development.DevelopmentBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Debug builds only: a real Amazon Connect agent through the demo backend, which requires AWS IAM.
 * The credentials come from the signed-in demo user ([DemoSignIn], the backend's identity pool) or,
 * without one, from temporary credentials seeded over adb or typed into Settings. Either way they are
 * held in memory for the run; they are never in the APK, the repository, or any store. A production
 * app never carries IAM credentials: its own backend vends the participant token.
 *
 * The backend's endpoint, its Region and the Cognito ids come from `demo/acme-bank/live.properties`
 * at build time (none is a secret), or from the seed. With a signed-in demo user, the Live Sync keys
 * come from the backend at each sign-in and are held in memory; without one, they are typed on the
 * sign-in screen and kept in this device's Keystore-backed store.
 */
object LiveMode {
    const val AVAILABLE: Boolean = true

    /** The identity provider's one callback for the demo (the Cognito app client's). */
    const val AUTHENTICATION_REDIRECT: String = "acmebank-touchpoint://auth"

    /**
     * The flow the demo backend starts chats on: with Live Sync keys, the Live Sync flow (the
     * assistant that drives the dispute screens); with the showcase setting, the showcase, a menu of
     * everything the SDK draws; otherwise plain chat to the queue.
     */
    private fun flow(): String =
        when {
            hasLiveSyncKeys -> "livesync"
            settings.showcase -> "showcase"
            else -> "default"
        }

    fun region(): String = settings.region

    /** The contact flow a new live session would start chats on, for the coordinator to notice a change. */
    fun currentFlow(): String = flow()

    // ---- The demo user's sign-in.

    /** The demo user's sign-in, when the backend's identity pool is configured; null otherwise. */
    val signIn: DemoSignIn?
        get() {
            val config = DemoSignIn.Configuration.of(settings.region, settings.userPoolId, settings.clientId, settings.identityPoolId)
            if (config != demoSignIn?.configuration) demoSignIn = config?.let(::DemoSignIn)
            return demoSignIn
        }
    private var demoSignIn: DemoSignIn? = null

    /** Whether the sign-in screen signs a demo user in for real: live mode on the identity pool. */
    val realSignIn: Boolean get() = signIn != null

    /** Whether a demo user is signed in now (meaningful only with [realSignIn]). */
    var hasSignedInUser by mutableStateOf(false)
        private set

    /** Signs a demo user in; throws with the sign-in screen's message. */
    suspend fun signInUser(username: String, password: String) {
        val signIn = signIn ?: return
        hasSignedInUser = false
        backendKeys = null
        try {
            signIn.signIn(username, password)
        } catch (e: DemoSignIn.Failure) {
            throw TouchpointException.InvalidConfiguration(e.message ?: "Sign-in failed.")
        }
        hasSignedInUser = true
        // On every sign-in, so a rotated key is picked up. A failure leaves Live Sync off; the
        // sign-in itself has succeeded and chat works without it.
        backendKeys = fetchLiveSyncKeys(CredentialBroker(LiveDemoBackend(settings, signIn = signIn), InMemorySecureStore()))
    }

    fun signOutUser() {
        hasSignedInUser = false
        backendKeys = null
        demoSignIn?.signOut()
    }

    // ---- The Live Sync keys from the demo backend (`POST /livesync/credentials`), for a signed-in user.

    /** The signed-in user's Live Sync keys, vended by the backend at sign-in; null when they could not be fetched. */
    private var backendKeys by mutableStateOf<LiveSyncCredentialSource?>(null)

    /**
     * Fetches the keys once through [source] (which caches them for the session) and returns it, or
     * null if the backend refused (a 503 when it has no key configured) or could not be reached. Logs
     * the outcome's status code only, never a key.
     */
    internal suspend fun fetchLiveSyncKeys(source: LiveSyncCredentialSource, log: (String) -> Unit = { Log.i(LOG_TAG, it) }): LiveSyncCredentialSource? =
        try {
            source.liveSyncCredentials()
            log("/livesync/credentials status=200: Live Sync on")
            source
        } catch (e: TouchpointException) {
            val outcome =
                when (e) {
                    is TouchpointException.HttpFailure -> "status=${e.statusCode}"
                    is TouchpointException.TransportFailure -> "transport failure"
                    else -> e.javaClass.simpleName
                }
            log("/livesync/credentials failed ($outcome): Live Sync off, chat only")
            null
        }

    /** The logcat tag of the Live Sync key fetch: `adb logcat -s AcmeLiveSync`. */
    const val LOG_TAG = "AcmeLiveSync"

    // ---- The Live Sync keys, typed on the sign-in screen and kept on this device.

    /** True once the saved keys have been read at launch: the sign-in screen waits for it, so it never saves empty fields over keys that hadn't loaded yet. */
    var keysLoaded by mutableStateOf(false)
        private set

    /** Reads the saved keys into the settings; a key seeded over adb wins over a saved one. */
    suspend fun loadSavedKeys(context: Context) {
        val saved = runCatching { AndroidStores.secureStore(context).get(KEYS_ITEM) }.getOrNull()
        val keys = saved?.let { runCatching { org.json.JSONObject(it.decodeToString()) }.getOrNull() }
        if (keys != null && !settings.hasLiveSyncKeys) {
            settings.deploymentKey = keys.optString("deploymentKey")
            settings.liveSyncApiKey = keys.optString("apiKey")
        }
        keysLoaded = true
    }

    /** Saves the keys as one item, readable while the device is unlocked, on this device only. Empty fields keep the saved keys. */
    suspend fun setKeys(context: Context, deploymentKey: String, apiKey: String) {
        // Field by field: one left empty keeps its saved key, rather than wiping it.
        val dk = deploymentKey.trim().ifEmpty { settings.deploymentKey }
        val ak = apiKey.trim().ifEmpty { settings.liveSyncApiKey }
        if (dk.isEmpty() && ak.isEmpty()) return
        val item = org.json.JSONObject().put("deploymentKey", dk).put("apiKey", ak).toString().encodeToByteArray()
        AndroidStores.secureStore(context).set(KEYS_ITEM, item, SecureStoreAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY)
        settings.deploymentKey = dk
        settings.liveSyncApiKey = ak
    }

    suspend fun forgetKeys(context: Context) {
        AndroidStores.secureStore(context).delete(KEYS_ITEM)
        settings.deploymentKey = ""
        settings.liveSyncApiKey = ""
    }

    private const val KEYS_ITEM = "acme.livesync.keys"

    /** Live Sync keys are available: from the backend for a signed-in user, or typed or seeded. */
    val hasLiveSyncKeys: Boolean get() = backendKeys != null || typedKeys

    /** Typed or seeded keys count only without a real sign-in: with one, the backend is the only source. */
    private val typedKeys: Boolean get() = !realSignIn && settings.hasLiveSyncKeys
    val savedDeploymentKey: String get() = settings.deploymentKey
    val savedApiKey: String get() = settings.liveSyncApiKey

    /** Where a flow's Views open from, in live mode. */
    fun connectInstanceUrl(): String? = settings.connectInstanceUrl.takeIf { it.isNotBlank() }

    /** Voice mode can place a call: live mode configured, Live Sync keys present, a number to call. */
    /**
     * Voice mode: in the app by default — the SDK's in-app call through the backend's
     * `StartWebRTCContact` — or, with "voice by phone" set, the demo's older stand-in: Connect rings the
     * customer's phone. Either way Live Sync binds to the call.
     */
    val voiceAvailable: Boolean
        get() = settings.isUsable && (!settings.voiceByPhone || (hasLiveSyncKeys && Regex("^\\+[1-9][0-9]{7,14}$").matches(settings.callbackNumber)))

    /** The phone-call stand-in instead of the in-app call. */
    val voiceByPhone: Boolean get() = settings.voiceByPhone

    /** The SDK's in-app voice transport, for live mode. */
    // The bank lets the customer share its screen when the agent asks, on live calls.
    fun voiceTransport(context: Context): ConnectVoiceTransport = ConnectVoiceTransport.live(context, allowsScreenShare = true)

    /** Has Connect call the customer into the Live Sync voice flow; the contact id to bind Live Sync to. */
    suspend fun placeCall(attributes: Map<String, String>): String {
        if (!voiceAvailable) throw TouchpointException.InvalidConfiguration("voice mode needs live mode, the Live Sync keys and a number to call (Settings)")
        return LiveDemoBackend(settings, signIn = signIn).startOutboundCall(settings.callbackNumber, attributes)
    }

    /**
     * Live Sync credentials for the coordinator. For a signed-in user, the broker over the demo
     * backend that fetched them at sign-in (an invalidated key is fetched again from the backend).
     * Otherwise the typed or seeded keys, as the development backend's literals behind a real broker,
     * so the client takes them through the same source a production host's backend feeds.
     */
    fun liveSyncCredentials(): LiveSyncCredentialSource? =
        backendKeys ?: if (!typedKeys) {
            null
        } else {
            CredentialBroker(DevelopmentBackend(DevelopmentBackend.LiveSyncLiterals(settings.deploymentKey.trim(), settings.liveSyncApiKey.trim())), InMemorySecureStore())
        }

    /**
     * Seeds the settings from a one-shot file, so a live run is not typed in by hand on a phone. The
     * developer's machine writes it into this app's private storage over adb's *stdin* —
     *
     *     adb shell run-as com.acmebank.demo.app sh -c 'mkdir -p no_backup && cat > no_backup/live-seed.json' < seed.json
     *
     * — never on a command line: adbd logs the command line of every shell it runs, and launch extras
     * on an `am start` land in the device log in plain text. The file is read once and deleted before
     * anything is parsed, the values are held in memory only, and `run-as` works on debuggable builds
     * alone. Returns whether live mode is now usable.
     */
    fun seed(context: Context): Boolean {
        val file = File(context.noBackupFilesDir, SEED_FILE)
        if (!file.exists()) return false
        val text = runCatching { file.readText() }.getOrNull()
        file.delete()
        val seed = runCatching { org.json.JSONObject(text ?: return false) }.getOrNull() ?: return false
        fun take(key: String, set: (String) -> Unit) = seed.optString(key).takeIf { it.isNotBlank() }?.let(set)
        take("endpoint") { settings.endpoint = it }
        take("region") { settings.region = it }
        take("accessKeyId") { settings.accessKeyId = it }
        take("secretAccessKey") { settings.secretAccessKey = it }
        take("sessionToken") { settings.sessionToken = it }
        take("liveSyncDeploymentKey") { settings.deploymentKey = it }
        take("liveSyncApiKey") { settings.liveSyncApiKey = it }
        take("liveSyncContactId") { pendingContactId = it }
        take("callbackNumber") { settings.callbackNumber = it }
        take("voiceByPhone") { settings.voiceByPhone = it == "true" }
        take("showcase") { settings.showcase = it == "true" }
        take("connectInstanceUrl") { settings.connectInstanceUrl = it }
        take("userPoolId") { settings.userPoolId = it }
        take("clientId") { settings.clientId = it }
        take("identityPoolId") { settings.identityPoolId = it }
        return settings.isUsable
    }

    /**
     * A contact to bind Live Sync to directly — a phone call to the Live Sync number, where no chat
     * starts in the app to supply one (the demo pastes it on Settings). Taken once.
     */
    @Volatile private var pendingContactId: String? = null

    fun takeContactId(): String? = pendingContactId.also { pendingContactId = null }

    private const val SEED_FILE = "live-seed.json"

    /** Whether the build or a seed configured live mode: the app starts in it. */
    val configured: Boolean get() = settings.isUsable

    /** Deliberately not persisted, and redacted from [toString]. Starts from the build's live.properties. */
    class Settings {
        var endpoint by mutableStateOf(BuildConfig.LIVE_ENDPOINT)
        var region by mutableStateOf(BuildConfig.LIVE_REGION.ifEmpty { "ca-central-1" })

        /** The backend's identity pool and the user pool's app sign-in client: with all three, demo users sign in. */
        var userPoolId by mutableStateOf(BuildConfig.LIVE_USER_POOL_ID)
        var clientId by mutableStateOf(BuildConfig.LIVE_CLIENT_ID)
        var identityPoolId by mutableStateOf(BuildConfig.LIVE_IDENTITY_POOL_ID)
        var accessKeyId by mutableStateOf("")
        var secretAccessKey by mutableStateOf("")
        var sessionToken by mutableStateOf("")

        /** Live Sync: the ACXD deployment key and the *application* API key (not the programmatic one). */
        var deploymentKey by mutableStateOf("")
        var liveSyncApiKey by mutableStateOf("")

        val hasLiveSyncKeys: Boolean get() = deploymentKey.isNotBlank() && liveSyncApiKey.isNotBlank()

        /** Voice mode: the phone the backend calls (E.164), and only if it is on the backend's allowlist. */
        var callbackNumber by mutableStateOf("")

        /** Voice by phone call (the older demo) rather than in the app. */
        var voiceByPhone by mutableStateOf(false)

        /**
         * The showcase flow without Live Sync keys: cards, sign-in, the view and the agent all work;
         * only its Live Sync dispute cannot drive the screens.
         */
        var showcase by mutableStateOf(false)

        /** The Connect instance a flow's Views load Connect's renderer from. Blank: guide cards are shown, not opened. */
        var connectInstanceUrl by mutableStateOf(BuildConfig.LIVE_CONNECT_INSTANCE_URL)

        val hasEndpoint: Boolean get() = runCatching { URI(endpoint).host != null }.getOrDefault(false) && region.isNotBlank()

        val hasCognito: Boolean get() = userPoolId.isNotBlank() && clientId.isNotBlank() && identityPoolId.isNotBlank()

        /** Temporary credentials only: a long-term key pair pasted by habit is refused, not used. */
        val isComplete: Boolean
            get() = hasEndpoint && accessKeyId.isNotBlank() && secretAccessKey.isNotBlank() && sessionToken.isNotBlank()

        /** Live mode can reach the backend: a demo user can sign in, or temporary credentials are set. */
        val isUsable: Boolean get() = hasEndpoint && (hasCognito || isComplete)

        override fun toString(): String = "LiveMode.Settings(endpoint=$endpoint, region=$region, credentials=<redacted>)"
    }

    val settings: Settings = Settings()

    fun backendAndTransport(context: Context): Pair<TouchpointBackend, ContactTransport>? {
        if (!settings.isUsable) {
            throw TouchpointException.InvalidConfiguration("live mode needs the demo backend's endpoint, and a signed-in demo user or temporary credentials including a session token (Settings → Live mode)")
        }
        // Sign-in on the flow's "Authenticate Customer": the identity provider's page comes back on the
        // app's own scheme, and the demo backend hands the result to Connect.
        val backend = LiveDemoBackend(settings, flow(), signIn)
        return backend to ConnectChatTransport.live(context, settings.region, ChatAuthentication(AUTHENTICATION_REDIRECT, backend))
    }

    @Composable
    fun SettingsSection() {
        // While it is on screen, a screen share sends black frames: these are credentials, and a share
        // could otherwise show them to the agent.
        Column(Modifier.fillMaxWidth().secureFromScreenShare()) {
            Text(stringResource(R.string.live_header), style = MaterialTheme.typography.titleSmall)
            field(stringResource(R.string.live_endpoint), settings.endpoint, KeyboardType.Uri) { settings.endpoint = it }
            field(stringResource(R.string.live_region), settings.region, KeyboardType.Ascii) { settings.region = it }
            field(stringResource(R.string.live_access_key), settings.accessKeyId, KeyboardType.Ascii) { settings.accessKeyId = it }
            field(stringResource(R.string.live_secret_key), settings.secretAccessKey, KeyboardType.Password) { settings.secretAccessKey = it }
            field(stringResource(R.string.live_session_token), settings.sessionToken, KeyboardType.Password) { settings.sessionToken = it }
            field(stringResource(R.string.live_deployment_key), settings.deploymentKey, KeyboardType.Ascii) { settings.deploymentKey = it }
            field(stringResource(R.string.live_livesync_key), settings.liveSyncApiKey, KeyboardType.Password) { settings.liveSyncApiKey = it }
            field(stringResource(R.string.live_callback_number), settings.callbackNumber, KeyboardType.Phone) { settings.callbackNumber = it }
            // Switch to voice rings this phone through /voice/call, rather than calling in the app.
            Row(
                Modifier.fillMaxWidth().toggleable(settings.voiceByPhone, role = Role.Switch) { settings.voiceByPhone = it }.testTag("live.voiceByPhone"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.live_voice_by_phone), Modifier.weight(1f))
                Switch(settings.voiceByPhone, onCheckedChange = null)
            }
            Text(stringResource(R.string.live_footer), style = MaterialTheme.typography.bodySmall)
        }
    }

    /**
     * The keyboard learns from what it is told is text, so the input type is what keeps a secret out
     * of its suggestions and dictionary — masking alone only changes what is drawn. Autocorrect is off
     * for every field: none of them is prose.
     */
    @Composable
    private fun field(label: String, value: String, type: KeyboardType, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (type == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = type),
            // Credentials and keys are never in a frame the agent sees, even while the customer shares the app.
            modifier = Modifier.fillMaxWidth().redactedFromScreenShare(),
        )
    }
}

/**
 * A [TouchpointBackend] over the shared demo backend: `POST /chat/start`, SigV4-signed for API
 * Gateway.
 */
internal class LiveDemoBackend(
    private val settings: LiveMode.Settings,
    private val flow: String = "default",
    private val signIn: DemoSignIn? = null,
) : TouchpointBackend, AuthenticationRelaying {
    /** The signed-in user's credentials, or the seeded or typed ones when nobody signs in. */
    private suspend fun credentials(): DemoAwsCredentials {
        if (signIn != null) {
            return try {
                signIn.credentials()
            } catch (e: DemoSignIn.Failure) {
                throw TouchpointException.InvalidConfiguration(e.message ?: "sign-in failed")
            }
        }
        if (!settings.isComplete) {
            throw TouchpointException.InvalidConfiguration("live mode needs a signed-in demo user or temporary credentials including a session token (Settings → Live mode)")
        }
        return DemoAwsCredentials(settings.accessKeyId, settings.secretAccessKey, settings.sessionToken, expiresAt = null)
    }

    override suspend fun startChatContact(request: ChatContactRequest): ChatContactCredentials {
        val body =
            JSONObject().apply {
                put("displayName", request.displayName)
                put("attributes", JSONObject(request.attributes))
                put("flow", flow)
            }
        return parsing {
            val reply = post("/chat/start", body)
            ChatContactCredentials(
                reply.getString("contactId"),
                reply.getString("participantId"),
                reply.getString("participantToken"),
                reply.optString("expiresAt").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
        }
    }

    /**
     * Demo-only: has Connect call [destination] into the Live Sync voice flow (`POST /voice/call`) and
     * returns the contact id. Not a [TouchpointBackend] call: the SDK's voice is in-app; this is the
     * demo's older stand-in, a phone call the assistant drives the app from. The backend dials only the
     * numbers on its own allowlist.
     */
    suspend fun startOutboundCall(destination: String, attributes: Map<String, String>): String =
        parsing { post("/voice/call", JSONObject().put("destinationPhoneNumber", destination).put("attributes", JSONObject(attributes))).getString("contactId") }

    /**
     * The in-app call: `POST /voice/start`, the backend's `StartWebRTCContact` into the Live Sync voice
     * flow — the demo backend's contract. The request's own client token, so a retried request cannot start
     * a second contact.
     */
    override suspend fun startVoiceContact(request: VoiceContactRequest): VoiceContactCredentials =
        parsing {
            val body =
                JSONObject().put("displayName", request.displayName).put("attributes", JSONObject(request.attributes))
                    .put("clientToken", request.clientToken)
                    .apply { if (request.screenShare) put("screenShare", true) }
            val reply = post("/voice/start", body)
            VoiceContactCredentials(
                reply.getString("contactId"),
                reply.getString("participantId"),
                reply.getString("participantToken"),
                reply.getJSONObject("meeting").toString(),
                reply.getJSONObject("attendee").toString(),
                expiresAt = null,
            )
        }

    /**
     * `POST /livesync/credentials` with `{}`: the deployment's Live Sync keys, the demo backend's
     * contract. A backend with no key configured answers 503 ([TouchpointException.HttpFailure]).
     */
    override suspend fun liveSyncCredentials(): LiveSyncCredentials =
        parsing {
            val reply = post("/livesync/credentials", JSONObject())
            credentialsOf(reply.optString("deploymentKey"), reply.optString("apiKey"))
        }

    companion object {
        /** The reply's keys, or [TouchpointException.MalformedPayload] when either is missing. */
        internal fun credentialsOf(deploymentKey: String?, apiKey: String?): LiveSyncCredentials {
            val dk = deploymentKey?.trim().orEmpty()
            val ak = apiKey?.trim().orEmpty()
            if (dk.isEmpty() || ak.isEmpty()) throw TouchpointException.MalformedPayload("demo backend Live Sync keys")
            return LiveSyncCredentials(dk, ak, expiresAt = null)
        }
    }

    /**
     * `POST /auth/complete`: the backend calls `UpdateParticipantAuthentication` with what the sign-in
     * redirect carried — the demo backend's contract. Connect then tells the chat whether it worked.
     */
    override suspend fun completeAuthentication(completion: AuthenticationCompletion) {
        val body =
            JSONObject().put("state", completion.state).apply {
                completion.code?.let { put("code", it) }
                completion.error?.let { put("error", it) }
                completion.errorDescription?.let { put("errorDescription", it) }
            }
        post("/auth/complete", body)
    }

    private inline fun <T> parsing(block: () -> T): T =
        try {
            block()
        } catch (e: org.json.JSONException) {
            throw TouchpointException.MalformedPayload("demo backend reply")
        }

    /** One SigV4-signed POST to the demo backend; the reply object, or a TouchpointException. */
    private suspend fun post(route: String, body: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val endpoint = URI(settings.endpoint.trimEnd('/'))
            val path = (endpoint.rawPath ?: "") + route
            val text = body.toString()
            val creds = credentials()
            val headers =
                SigV4.sign(
                    "POST", endpoint.rawAuthority, path, mapOf("content-type" to "application/json"), text,
                    creds.accessKeyId, creds.secretAccessKey, creds.sessionToken, settings.region, "execute-api", Instant.now(),
                )
            val connection = (endpoint.resolve(path).toURL().openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                headers.filterKeys { it != "host" }.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                connection.outputStream.use { it.write(text.encodeToByteArray()) }
                val status = connection.responseCode
                val reply = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.readText()
                if (status != 200) throw TouchpointException.HttpFailure(status, reply)
                JSONObject(reply ?: "{}")
            } catch (e: java.io.IOException) {
                throw TouchpointException.TransportFailure(e.javaClass.simpleName)
            } finally {
                connection.disconnect()
            }
        }
}

/** Signature Version 4 for one signed POST with no query string. */
internal object SigV4 {
    private val amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val day = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    fun sign(
        method: String,
        host: String,
        path: String,
        headers: Map<String, String>,
        body: String,
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String?,
        region: String,
        service: String,
        now: Instant,
    ): Map<String, String> {
        val stamp = amzDate.format(now)
        val date = day.format(now)
        val all = sortedMapOf<String, String>()
        headers.forEach { (k, v) -> all[k.lowercase()] = v.trim() }
        all["host"] = host
        all["x-amz-date"] = stamp
        if (!sessionToken.isNullOrEmpty()) all["x-amz-security-token"] = sessionToken
        val signedNames = all.keys.joinToString(";")
        val canonicalHeaders = all.entries.joinToString("") { "${it.key}:${it.value}\n" }
        val canonical = listOf(method, path.ifEmpty { "/" }, "", canonicalHeaders, signedNames, hex(sha256(body.encodeToByteArray()))).joinToString("\n")
        val scope = "$date/$region/$service/aws4_request"
        val toSign = listOf("AWS4-HMAC-SHA256", stamp, scope, hex(sha256(canonical.encodeToByteArray()))).joinToString("\n")
        var key = hmac("AWS4$secretAccessKey".encodeToByteArray(), date)
        for (part in listOf(region, service, "aws4_request")) key = hmac(key, part)
        all["authorization"] = "AWS4-HMAC-SHA256 Credential=$accessKeyId/$scope, SignedHeaders=$signedNames, Signature=${hex(hmac(key, toSign))}"
        return all
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hmac(key: ByteArray, message: String) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message.encodeToByteArray())

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
