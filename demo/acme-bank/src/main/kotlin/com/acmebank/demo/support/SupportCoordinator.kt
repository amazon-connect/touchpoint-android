package com.acmebank.demo.support

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.acmebank.demo.DemoData
import com.amazon.connect.touchpoint.voice.telecom.TouchpointBackgroundCalls
import com.amazon.connect.touchpoint.core.ChatContactCredentials
import com.amazon.connect.touchpoint.core.ChatContactRequest
import com.amazon.connect.touchpoint.core.CredentialBroker
import com.amazon.connect.touchpoint.core.LiveSyncCredentials
import com.amazon.connect.touchpoint.core.Modality
import com.amazon.connect.touchpoint.core.TouchpointBackend
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TouchpointSession
import com.amazon.connect.touchpoint.core.VoiceContactCredentials
import com.amazon.connect.touchpoint.core.VoiceContactRequest
import com.amazon.connect.touchpoint.core.android.AndroidStores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Builds and owns the Touchpoint session for the support dock. This is the integration an app would
 * write: a backend, a broker, a store, a transport, one session.
 *
 * Its work runs in its own scope, held by the application, never in a screen's: a rotation or a
 * sign-out that disposes the screen must not cancel a start midway — live, that would orphan a
 * contact the backend has already created — nor an end the customer asked for. Main thread.
 */
class SupportCoordinator(private val context: Context) {
    enum class Mode {
        /** Scripted agent, no network. The default; what the UI test runs against. */
        OFFLINE,

        /** A real Amazon Connect agent through the shared demo backend. Debug builds only. */
        LIVE,
    }

    var session by mutableStateOf<TouchpointSession?>(null)
        private set

    /** Bumps whenever [session] becomes a different object; the dock keys its chat view on it. */
    var sessionGeneration by mutableIntStateOf(0)
        private set
    var mode by mutableStateOf(Mode.OFFLINE)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    /** The offline transport's agent console, once offline mode has built its session; read it with [mode] OFFLINE. */
    var offlineConsole by mutableStateOf<AgentConsoleControls?>(null)
        private set

    /** Slow motion (Settings, offline): the script takes three times as long, so a recording catches each state. */
    var slowMotion by mutableStateOf(false)

    /** Which screen the customer is on, sent as a contact attribute so the flow can tailor its introduction. */
    var currentScreen: String = "accounts"

    /** Live Sync rides on the chat contact: once a live chat starts, its contact id binds the socket. */
    var liveSync: LiveSyncCoordinator? = null

    /** Voice mode (debug demo): Connect calls the customer, and Live Sync binds to that call. */
    sealed interface Voice {
        data object Idle : Voice

        data object Calling : Voice

        data object OnCall : Voice

        data class Failed(val reason: String) : Voice
    }

    var voice by mutableStateOf<Voice>(Voice.Idle)
        private set

    /** Which face the panel shows: voice (the demo's call) or the SDK's chat. */
    var voiceMode by mutableStateOf(false)

    /** Voice offline is the SDK's scripted call (no audio, no network); live, where live mode has voice. */
    val voiceOffered: Boolean get() = mode == Mode.OFFLINE || LiveMode.voiceAvailable

    /** In-app voice (the SDK's), unless the live demo is set to call the customer's phone instead. */
    val inAppVoice: Boolean get() = mode == Mode.OFFLINE || !LiveMode.voiceByPhone

    /** The in-app call's transport, once live mode has built the session. */
    var voiceTransport: com.amazon.connect.touchpoint.voice.ConnectVoiceTransport? = null
        private set

    /**
     * Opens support: the chat, with voice one tap away ("Switch to voice") where a
     * call can be placed. A call already under way stays the face it was.
     */
    fun openSupport() {
        if (voiceMode && (voice == Voice.Calling || voice == Voice.OnCall || session?.let { it.isActive && it.modality == Modality.VOICE } == true)) return
        voiceMode = false
        openChat()
    }

    /** From voice to the chat, or back; a call already placed stays bound. */
    fun switchMode(toVoice: Boolean) {
        voiceMode = toVoice
        if (toVoice) {
            if (!inAppVoice && voice != Voice.Calling && voice != Voice.OnCall) startVoiceCall()
        } else {
            openChat()
        }
    }

    /** Has Connect ring the customer's phone into the Live Sync voice flow, then binds Live Sync to the call. */
    fun startVoiceCall(): Job =
        scope.launch {
            if (voice == Voice.Calling) return@launch
            voice = Voice.Calling
            voice =
                try {
                    val id = LiveMode.placeCall(mapOf("screen" to currentScreen, "app" to "AcmeBank", "platform" to "android"))
                    val ls = liveSync
                    // A voice contact: no dispatch gate (LiveSyncCoordinator.bind says why).
                    if (ls != null && ls.isConfigured) ls.bind(id, currentScreen, gate = false)
                    Voice.OnCall
                } catch (e: TouchpointException) {
                    Voice.Failed(describe(e))
                }
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val build = Mutex()
    private val starting = Mutex()

    /** Starts a chat unless one is live or about to be. Idempotent for the dock's expand action. */
    fun openChat(): Job =
        scope.launch {
            lastError = null
            starting.withLock {
                try {
                    var session = ensureSession()
                    if (session.isActive) return@withLock
                    // The contact flow is fixed when the session is built, and the showcase needs the
                    // Live Sync keys, which can arrive (or go) on the sign-in screen after launch. Only
                    // here, about to start a new contact on an idle session, is it safe to rebuild for
                    // the other flow.
                    if (mode == Mode.LIVE && builtFlow != LiveMode.currentFlow()) {
                        session.close()
                        replace(null)
                        session = ensureSession()
                    }
                    session.startChat(
                        ChatContactRequest(DemoData.CUSTOMER_NAME, mapOf("screen" to currentScreen, "app" to "AcmeBank", "platform" to "android")),
                    )
                    // The contact exists: bind Live Sync to it so the assistant can drive the screens.
                    val id = session.contactId
                    val ls = liveSync
                    if (mode == Mode.LIVE && id != null && ls != null && ls.isConfigured) ls.bind(id, currentScreen)
                } catch (e: TouchpointException) {
                    lastError = describe(e)
                }
            }
        }

    /** Sends [text] as the customer typed it — a suggested reply. Refused quietly with no contact: the composer says why. */
    fun send(text: String): Job =
        scope.launch {
            try {
                session?.send(text)
            } catch (_: TouchpointException) {
            }
        }

    /** Ends the contact, after any start in flight: an end must not race the start it follows. */
    fun endChat(): Job = scope.launch { starting.withLock { session?.end() } }

    /**
     * Switches mode. An in-flight start is waited for, then the existing contact, if any, is ended: a
     * transport belongs to one mode, and an orphaned start would be a real contact nobody owns.
     */
    fun changeMode(newMode: Mode): Job =
        scope.launch {
            if (newMode == mode) return@launch
            starting.withLock {
                session?.end()
                session?.close()
                replace(null)
                lastError = null
                liveSync?.disconnect()
                mode = newMode
            }
        }

    /** The session the voice view shows, built if need be. */
    suspend fun voiceSession(): TouchpointSession = ensureSession()

    /**
     * The in-app call the bank holds: the dock's rail and the chat's tray show it,
     * the full-screen view takes it over, and none of them hangs it up by going away. Made with the
     * session, for the session.
     */
    var voiceCall by mutableStateOf<com.amazon.connect.touchpoint.voice.TouchpointVoiceCall?>(null)
        private set

    /** Call us: the in-app call, its microphone already granted. */
    fun callUs() {
        scope.launch {
            val session = ensureSession()
            val transport = voiceTransport ?: return@launch
            val call = voiceCall?.takeIf { it.session === session } ?: com.amazon.connect.touchpoint.voice.TouchpointVoiceCall(session, transport, onStartCall = { startInAppCall() }, scope).also { voiceCall = it }
            call.start()
        }
    }

    /**
     * The voice view's start: the SDK's in-app call, into the Live Sync voice flow, ending a chat that is
     * still open first (one contact at a time); then Live Sync binds to the call, as it does to a chat.
     */
    suspend fun startInAppCall() {
        val session = ensureSession()
        // Under the same lock as a chat's start, so a chat opening meanwhile cannot race it.
        val id =
            starting.withLock {
                if (session.isActive) session.end()
                session.startVoice(VoiceContactRequest(DemoData.CUSTOMER_NAME, mapOf("screen" to currentScreen, "app" to "AcmeBank", "platform" to "android"), screenShare = true))
                session.contactId
            } ?: return
        val ls = liveSync
        // A voice contact: no dispatch gate (LiveSyncCoordinator.bind says why). Chat keeps it.
        if (mode == Mode.LIVE && ls != null && ls.isConfigured) ls.bind(id, currentScreen, gate = false)
    }

    private suspend fun ensureSession(): TouchpointSession =
        build.withLock {
            session?.let { return@withLock it }
            val keys = AndroidStores.secureStore(context)
            val store = AndroidStores.conversationStore(context, keys)
            val (backend, transport) = when (mode) {
                Mode.OFFLINE -> OfflineBackend to ScriptedChatTransport(pace = { if (slowMotion) SLOW_MOTION else 1.0 })
                Mode.LIVE -> LiveMode.backendAndTransport(context) ?: throw TouchpointException.InvalidConfiguration("live mode is available in debug builds only")
            }
            builtFlow = if (mode == Mode.LIVE) LiveMode.currentFlow() else ""
            offlineConsole = (transport as? ScriptedChatTransport)?.console
            // Live mode calls in the app; offline, the SDK's scripted call stands in, for walkthroughs and recordings.
            val voice = if (mode == Mode.LIVE) LiveMode.voiceTransport(context) else com.amazon.connect.touchpoint.voice.ConnectVoiceTransport.scripted()
            voiceTransport = voice
            val transports = if (voice != null) mapOf(Modality.CHAT to transport, Modality.VOICE to voice) else mapOf(Modality.CHAT to transport)
            TouchpointSession(CredentialBroker(backend, keys), store, transports).also { s ->
                // A reply while the customer is away: the bank's to announce, never the SDK's.
                s.onIncomingMessages = notifier::onIncoming
                replace(s)
                // The bank opts in to calls that go on in the background, as an app would: the
                // system shows the call, and its notification comes back here or hangs up.
                if (voice != null && mode == Mode.LIVE) background = TouchpointBackgroundCalls.attach(context, s, displayName = com.acmebank.demo.PartnerBrand.current.bankName, smallIcon = android.R.drawable.sym_call_outgoing)
            }
        }

    private val notifier = ReplyNotifier(context)

    /** The contact flow the live session was built for. */
    private var builtFlow = ""

    private companion object {
        const val SLOW_MOTION = 3.0
    }

    private var background: TouchpointBackgroundCalls? = null

    private fun replace(next: TouchpointSession?) {
        if (next !== session) {
            sessionGeneration++
            voiceCall = null
            background?.close()
            background = null
        }
        session = next
    }

    private fun describe(e: TouchpointException): String =
        when (e) {
            is TouchpointException.InvalidConfiguration -> e.detail
            is TouchpointException.ContactNotFound -> "That conversation is no longer on this device."
            is TouchpointException.HttpFailure -> "The demo backend answered ${e.statusCode}."
            is TouchpointException.TransportFailure -> e.detail
            is TouchpointException.CredentialVendingFailed -> "Could not start a chat: ${e.underlying}."
            else -> "Something went wrong (${e.javaClass.simpleName})."
        }
}

/** Offline mode: placeholder credentials, because the scripted transports never use them. */
object OfflineBackend : TouchpointBackend {
    override suspend fun startChatContact(request: ChatContactRequest): ChatContactCredentials =
        ChatContactCredentials("offline-${UUID.randomUUID()}", "customer", "offline", expiresAt = null)

    override suspend fun startVoiceContact(request: VoiceContactRequest): VoiceContactCredentials =
        VoiceContactCredentials("offline-voice-${UUID.randomUUID()}", "customer", "offline", "{}", "{}", expiresAt = null)

    override suspend fun liveSyncCredentials(): LiveSyncCredentials = throw TouchpointException.InvalidConfiguration("Live Sync needs live mode")
}
