# Amazon Connect Customer Touchpoint SDK for Android

**Status: developer preview.** The public API may change before 1.0; each release's changes are in
[CHANGELOG.md](CHANGELOG.md).

Native Android SDK for Amazon Connect Customer Touchpoint: chat, outbound voice, and Live Sync, as
separately linkable Gradle modules so an app pays only for the modalities it uses. It is the
counterpart of the Touchpoint iOS SDK and follows the same design, and it matches Touchpoint for
the web's behaviour and options.

| Module | What it adds | Links |
| --- | --- | --- |
| `touchpoint-core` | Configuration, credential brokering, session coordination, durable-store logic, event model. Pure Kotlin/JVM | — |
| `touchpoint-core-android` | Android platform adapters every product needs (Keystore-backed secure store, framework SQLite) and the shared string resources. Comes in transitively; hosts never declare it | — |
| `touchpoint-chat` | Chat with an Amazon Connect contact, transcript UI, and new-message reporting for the host | `amazon-connect-chat-android` |
| `touchpoint-voice` | Outbound WebRTC calling and screen sharing | Amazon Chime SDK (its full media, and its machine-learning library) |
| `touchpoint-voice-telecom` | Opt-in: an in-app call that goes on in the background, through Jetpack core-telecom and a call notification. Adds core-telecom's ConnectionService and permissions, and a phone-call foreground service, to the host's manifest | — |
| `touchpoint-livesync` | Live Sync bound to a live contact: the client, its consent, and Compose declarations of what each screen can do | — |

A chat-only consumer never links the WebRTC media stack, and a Live-Sync-only consumer links neither
external SDK. `Scripts/check-build-graph.sh` enforces that on every `make check` by reading each
module's resolved release classpath, which is its transitive closure, so an upstream dependency
pulling Chime in would fail the gate too.

## Requirements

For a host: Android 12 (API 31) or later, and Kotlin 2.2 or later — the SDK's class metadata is held
at 2.2 whatever compiler builds it, and its standard library at 2.3.21, the Amazon Connect chat SDK
3.0's. An app that links `touchpoint-chat` also needs core library desugaring, which the chat SDK 3.0
(on the AWS SDK for Kotlin) requires:

```kotlin
android {
    compileOptions { isCoreLibraryDesugaringEnabled = true }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
```

For building the SDK:
JDK 17 and the Android SDK (platform 36), with `sdk.dir` in `local.properties` or `ANDROID_HOME`
pointing at it. The Gradle wrapper is checked in and pins Gradle by checksum.

## Installing

The libraries are on Maven Central, under `software.aws.connect`. Declare the products the app uses;
each brings `touchpoint-core`, `touchpoint-core-android` and `touchpoint-ui` with it.

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("software.aws.connect:touchpoint-chat:0.1.1")
    // Only as the app needs them:
    implementation("software.aws.connect:touchpoint-voice:0.1.1")
    implementation("software.aws.connect:touchpoint-voice-telecom:0.1.1") // calls that go on in the background
    implementation("software.aws.connect:touchpoint-livesync:0.1.1")
}
```

### From a release bundle

A release is also available as a zip, `touchpoint-android-<version>.zip`, laid out as a Maven
repository. Check its SHA-256 against the one you were given, then unzip it into the project (here
`third_party/touchpoint-repo/`) and list it before Maven Central:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri("third_party/touchpoint-repo") }
        google()
        mavenCentral()
    }
}
```

The dependencies are the same as above. The SDK's own modules come from the bundle, and everything
it depends on (the Amazon Connect Chat SDK, the Amazon Chime SDK, Compose) from Maven Central. The
coordinates are the same as Maven Central's, so moving to it later is removing the `maven { … }` line
and the folder.

## Developer guide

The SDK is a client. The contact centre it talks to is yours: an Amazon Connect instance, its
contact flows and, for the assistant, an Agentic CX Designer application that a flow connects to. Most
integration problems come from that side, so each feature below ends with what it needs there.

### How the pieces fit

```
TouchpointBackend ─▶ CredentialBroker ─▶ TouchpointSession ◀─ ConversationStore
   (your server)                              │
                                     transports per modality
                           ConnectChatTransport · ConnectVoiceTransport
                                              │
         SDK composables: TouchpointChatSheet / TouchpointChatView · TouchpointVoiceView · TouchpointVoiceRail
```

- **One session holds one active contact.** One session can carry both modalities
  (`mapOf(Modality.CHAT to chat, Modality.VOICE to voice)`). A call ends any chat on that session
  first. If you want a chat and a call at once, give each modality its own `TouchpointSession`
  over the same broker and store.
- **The store is the transcript cache.** It is encrypted at rest with a Keystore-held key and
  survives process death. `resume(contactId)` reopens a chat without starting a new contact.
- **The session and the Live Sync client hold resources until you close them.** Kotlin has no
  deinit, so call `close()` on each when you are done with it. The UI is Jetpack Compose
  composables.

### 1. Your backend

Implement `TouchpointBackend` against a service you run. It authenticates your user, calls
Amazon Connect and relays the result. Only the calls for the features you ship need to work.
Throw `TouchpointException.InvalidConfiguration` from the rest.

```kotlin
import com.amazon.connect.touchpoint.core.*

class MyBackend(private val api: MyApiClient) : TouchpointBackend {   // your authenticated client
    override suspend fun startChatContact(request: ChatContactRequest): ChatContactCredentials {
        val r = api.startChat(request.displayName, request.attributes)
        // StartChatContact returns no expiry: the token lives as long as the contact.
        return ChatContactCredentials(r.contactId, r.participantId, r.participantToken, expiresAt = null)
    }

    override suspend fun startVoiceContact(request: VoiceContactRequest): VoiceContactCredentials {
        // Your service returns StartWebRTCContact's ConnectionData.Meeting and .Attendee as JSON.
        val r = api.startVoice(request.displayName, request.attributes, request.clientToken, request.screenShare)
        return VoiceContactCredentials(r.contactId, r.participantId, r.participantToken,
            meetingJson = r.meeting, attendeeJson = r.attendee, expiresAt = null)
    }

    override suspend fun liveSyncCredentials(): LiveSyncCredentials {
        val k = api.liveSyncKeys()
        return LiveSyncCredentials(k.deploymentKey, k.apiKey, k.expiresAt)
    }
}
```

What the backend must get right:

- **Authenticate every route.** Anyone who can call it can start contacts on your instance and
  use agent capacity.
- **Scope its IAM role to the instance and the flows it starts.** Grant `connect:StartChatContact`
  and `connect:StartWebRTCContact` on the instance ARN and on each contact flow ARN. If you use
  sign-in, also grant `connect:UpdateParticipantAuthentication` on the instance. Grant nothing
  else.
- **Choose the contact flow on the server.** The app sends a display name and attributes, never a
  flow or instance id. Attributes reach the flow as contact attributes, so bound their size and
  keep secrets out of them.
- **Pass `SupportedMessagingContentTypes`** to `StartChatContact`:
  - `text/plain`
  - `text/markdown`
  - `application/json`
  - `application/vnd.amazonaws.connect.message.interactive`
  - `application/vnd.amazonaws.connect.message.interactive.response`

  Without the last one, Connect rejects every choice the customer taps with a bare
  `ValidationException`.
- **Forward `VoiceContactRequest.clientToken` as `ClientToken`**, so a retried request doesn't
  start a second call.
- **Map Connect's `LimitExceededException` to a retryable error**, such as a 429. Contacts that
  are never ended count against the instance's concurrency limit.
- **Use a current AWS SDK.** Older ones, such as some bundled with Lambda runtimes, lack
  `StartWebRTCContact`.
- **To keep the participant token off the device,** pass your own client to
  `ConnectChatTransport.live(context, region, authentication, customChatClient)`. This is web's
  `customChatClient`, and `ChatContactCredentials.participantToken` is then `null`.

`DevelopmentBackend` takes literal Live Sync keys, so you can try Live Sync before your backend
exists. It isn't published to Maven Central: its source is in this repository's
`touchpoint-development/` module. To use it, copy `DevelopmentBackend.kt` into your app's
`src/debug/` source set. Code there is compiled only into debug builds, so release code that refers
to it doesn't compile. None of the SDK's published artifacts contains it; `make check` fails any
that does.

### 2. Chat

```kotlin
import com.amazon.connect.touchpoint.chat.ConnectChatTransport
import com.amazon.connect.touchpoint.chat.ui.TouchpointChatSheet
import com.amazon.connect.touchpoint.core.*
import com.amazon.connect.touchpoint.core.android.AndroidStores

suspend fun makeChatSession(context: Context, backend: MyBackend): TouchpointSession {
    val keys = AndroidStores.secureStore(context)                // Keystore-backed
    val store = AndroidStores.conversationStore(context, keys)   // one per process
    val chat = ConnectChatTransport.live(context, region = "us-west-2")   // your instance's Region
    return TouchpointSession(CredentialBroker(backend, keys), store, mapOf(Modality.CHAT to chat))
}

@Composable
fun SupportButton(session: TouchpointSession) {
    var showChat by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Button(onClick = {
        showChat = true
        if (!session.isActive) scope.launch {
            try {
                session.startChat(ChatContactRequest("Jordan", mapOf("screen" to "accounts")))
            } catch (e: TouchpointException) {
                // Your backend refused or was unreachable: tell the customer.
            }
        }
    }) { Text("Chat with us") }
    TouchpointChatSheet(session, visible = showChat, onDismiss = { showChat = false })
}
```

- **`TouchpointLaunchButton` is what the customer taps to open it**, as web's and iOS's launch
  button: the assistant's mark and your words on the chat's surface, or the mark alone without a
  `label`. `icon` puts your brand's mark in place of the sparkle. Where it sits, and what a tap
  opens, are yours: `TouchpointLaunchButton(onClick = { showChat = true }, label = "Talk to AI Assistant")`.
- **`TouchpointChatSheet` presents the chat over your screen.** Its `windowSize` picks HALF, FULL
  or MINI, and its default is full on a phone and a half panel on a tablet. `collapseToMini`
  makes the header's close drop it to a floating card. To place the chat yourself, use
  `TouchpointChatView`; the keyboard inset (`Modifier.imePadding()`) is then yours to apply.
- **The session reconnects on its own** and resends unacknowledged messages.
- **`end()` ends the contact.** `resume(contactId)` reopens a chat that is still live after the
  process died. Ended chats and calls can't be resumed.
- **`onStartConversation` turns on the header's restart** and the Start new conversation button.
  `onContactEnded` tells you when a contact ends, and why.
- **`settingsRows` adds your own rows** to the settings, after the SDK's (Restart conversation,
  Download transcript): `TouchpointSettingsRow("Call us", prominent = true) { startCall() }`. A tap
  closes the settings, then runs the row.
- **Ending asks first.** With `addedElements = setOf(TouchpointAddedElement.HEADER_CLOSE)`, the
  header's close on a live conversation shows a confirmation card over the panel; End conversation
  ends it and closes the chat. Its words are yours to set with
  `TouchpointChatOptions(copy = TouchpointChatCopy(…))`.
- **`headerNotice`** is a line under the header, for words the customer must see, such as "This
  chat is recorded."

**Customising.**
- `TouchpointTheme` sets the colours, corner radii and font. Start from `TouchpointTheme.Light`,
  `Dark` or `system()`, and set a brand with `withAccent(color)`.
- `TouchpointChatOptions` matches the web SDK's options: bubbles, participant names and avatars,
  timestamps, the welcome screen, the header's brand, title, style and notice, `hiddenElements`,
  `addedElements`, `icons`, `copy` and `attachmentsEnabled`.
- The SDK's text is Android string resources, all prefixed `touchpoint_`. A string your app
  redefines wins. The SDK ships English and the web SDK's eleven other languages, and follows the
  device language.

**Connect setup**

- You need an instance with chat enabled, an inbound chat contact flow, a queue, a routing profile
  that takes chat, and agents in that routing profile.
- Start the flow with a **Set logging behavior** block that enables flow logs. They are the
  quickest way to see where a contact went.
- Messages from **Send message** blocks appear as the assistant's.
- The chat socket runs while your app's process does. If the process is killed, call
  `resume(contactId)` when the app comes back; the session then catches up from the server
  transcript.

### 3. The assistant: choices, cards and custom modalities

Build the assistant in Agentic CX Designer and hand the contact to it with the contact flow's
**Agentic CX** flow block (`ConnectParticipantWithAgenticCX` in flow JSON). The SDK renders what the
assistant sends without any code from you:
- choices, as suggested replies;
- the default modalities: `DefaultCard`, `DefaultCarousel`, `DefaultDateInput` and `DefaultVideo`;
- interim "one moment" lines;
- markdown.

To render your own modality, register a composable for it by name:

```kotlin
val modalities: Map<String, TouchpointModalityContent> = mapOf(
    "OrderSummary" to { modality, actions ->
        OrderSummaryCard(modality.data, enabled = actions.fillSlot != null) { confirmed ->
            actions.fillSlot?.invoke("confirmed", if (confirmed) "yes" else "no")
        }
    },
)

TouchpointChatSheet(session, visible, onDismiss, modalities = modalities)
```

- The actions are `null` once the message is no longer the newest one.
- If your modality's name clashes with a default modality, the default wins.

**Agentic CX Designer setup**

- The block names a workspace, an application and an **alias**. If you set them by id (in flow
  JSON, `AgentConfiguration.WorkspaceId`, `ApplicationId` and `Alias`), `Alias` is the
  deployment's alias, not the environment name.
- Check that Agentic CX Designer and its flow block are available in your instance's Region.
- Context variables set in the block reach the application. Define them in the application
  first. A block takes up to 10. Pass what the assistant should know, such as the current screen.
- Contacts talk to the deployed build, so build and deploy the application after every change.
  If a new deployment gives you a new alias, update the block. For Live Sync, also update the
  deployment key your backend vends.
- When the block returns, the contact flow continues along one of four branches: Default, Error,
  Idle chat timeout or Escalation.
  - For a human agent, route Escalation through **Set working queue** to **Transfer to queue**.
  - Give every branch an ending, or the conversation stops with no explanation.

### 4. Sign-in (Authenticate Customer)

When the flow reaches an **Authenticate Customer** block, the chat shows a sign-in card. The SDK
opens Amazon Cognito's hosted sign-in in an ephemeral browser tab. It uses Chrome's Auth Tab
where the browser has it, and a Custom Tab otherwise. The result goes to your backend, which
completes it with Connect.

```kotlin
class MyBackend(...) : TouchpointBackend, AuthenticationRelaying {
    override suspend fun completeAuthentication(completion: AuthenticationCompletion) {
        api.completeSignIn(completion)   // calls UpdateParticipantAuthentication
    }
}

val chat = ConnectChatTransport.live(context, "us-west-2", ChatAuthentication("myapp-touchpoint://auth", backend))
```

With a Custom Tab, the redirect comes to your activity. Declare an intent filter for the scheme
(or a verified App Link for https) and pass the redirect on:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    val redirect = intent.dataString ?: return
    if (redirect.startsWith("myapp-touchpoint://auth")) lifecycleScope.launch { session.completeAuthentication(redirect) }
}
```

**Connect setup**

- A Cognito user pool must be **associated with the instance**. In the console, that's
  Applications → Customer authentication. From the CLI, run `aws connect
  create-integration-association --integration-type COGNITO_USER_POOL`.
- The pool needs a domain for its hosted sign-in page, and a public app client (no secret) with:
  - the authorization-code grant;
  - the `openid` scope;
  - your redirect URI as its callback URL.
- In the block:
  - choose the pool, the client and a Customer Profiles template (Store by default;
    `ObjectTypeName: Cognito-attributes` in flow JSON);
  - set a timeout;
  - branch on Success, Opted out, Timeout and Error.
- Your backend passes `state`, and either `code` or `error` (with `errorDescription`), from
  `AuthenticationCompletion` to `UpdateParticipantAuthentication`.
- The customer has 60 seconds to tap the sign-in card before it expires.
- For the redirect URI, use either a custom scheme your app owns, or an `https` URL with a path on
  a domain whose Digital Asset Links name your app. The SDK checks the scheme, host, path and
  `state` of every redirect. The [security guide](docs/SECURITY-GUIDE.md#sign-in) has the details.

### 5. Guides (Connect Views)

A **Show view** block sends a guide card. If you pass your instance URL, the card opens the view
over the chat, and the customer's answer goes back to the flow.

```kotlin
TouchpointChatSheet(session, visible, onDismiss, connectInstanceUrl = "https://your-instance.my.connect.aws")
```

- The URL must be your instance's `https` access URL, on `*.my.connect.aws` or `*.awsapps.com`.
  Without it, the card shows but doesn't open.
- AWS-managed views (`arn:aws:connect:<region>:aws:view/form:1`, for example) need no
  `CreateView`. Branch the flow on the action names the view returns (`Next`, `Cancel`), and add a
  timeout.

### 6. Attachments

Attachments are off by default, as in web 1.0.6: set
`TouchpointChatOptions(attachmentsEnabled = true)`. The paperclip then appears while a human agent is
on the contact. The customer picks a file through
the system picker, so the SDK needs no storage permission. A file the agent sends is saved where
the customer chooses.

- The instance needs **attachments storage** (an S3 bucket in its data storage settings).
  Without it, Connect refuses the upload, and the agent workspace has no paperclip either.
- Files are limited to 8 MB (`AttachmentUpload.MAX_BYTES`). The picker offers web's types: JPEG,
  PNG, WebP, MP4, AVI, QuickTime and PDF. Which of these Connect accepts is set on the instance.

### 7. New-message notifications

The SDK posts no notifications and has no remote push. It reports each new message from the
other side, and your app decides what to do:

```kotlin
session.onIncomingMessages = { incoming ->
    // On the session's own thread. Generic text: the notification shows on a locked phone.
    if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
        notifier.show(title = "Support replied", contactId = incoming.contactId)
    }
}
```

- `POST_NOTIFICATIONS`, and asking for it, are your app's to handle.
- When the customer taps a notification after the process died, call `session.resume(contactId)`
  rather than starting a new contact.
- Messages that arrived while the connection was down are reported when it comes back, as one
  call with `IncomingMessages.Origin.CATCH_UP`.

### 8. Voice

Voice places an outbound WebRTC call into your instance, over the Amazon Chime SDK.

```kotlin
import com.amazon.connect.touchpoint.voice.*

val voice = ConnectVoiceTransport.live(context)
val voiceSession = TouchpointSession(broker, store, mapOf(Modality.VOICE to voice))
val call = TouchpointVoiceCall(voiceSession, voice, displayName = "Jordan",
    permissionPolicy = PermissionPolicy(setOf(Permission.MICROPHONE)),   // the views may ask for it
    attributes = mapOf("screen" to "accounts"))
call.start()

// Full screen, with a Minimise that keeps the call going…
TouchpointVoiceView(call, onClose = { showCall = false }, onMinimise = { showCall = false })
// …and the floating rail for a minimised call, draggable from its handle.
TouchpointVoiceRail(call, draggable = true)
```

The call builds its contact request itself. To build your own, pass
`onStartCall = { voiceSession.startVoice(request) }` instead of `displayName`. `call.start()` shows no
system prompt: without the microphone it fails, so ask for it first, or let the call's views ask
(`permissionPolicy`).

`TouchpointVoiceMini` is the guide's voice mini on its own. Shown, it starts a call, and it takes
the chat's place rather than sitting inside it.

**App setup**

- Declare `RECORD_AUDIO` and `MODIFY_AUDIO_SETTINGS` in your manifest.
- The SDK asks for the microphone only if you allow it with
  `permissionPolicy = PermissionPolicy(setOf(Permission.MICROPHONE))`. Otherwise, ask for it
  yourself first. Without it, the view says so and can offer chat (`onContinueWithChat`).
- **To keep a call going in the background,** add `touchpoint-voice-telecom` and attach it to the
  session:

  ```kotlin
  TouchpointBackgroundCalls.attach(context, voiceSession, displayName = "Example Bank", smallIcon = R.drawable.ic_call)
  ```

  The system then treats the call as a call, through Jetpack core-telecom. A foreground service
  keeps the microphone running, and a call notification returns to the app or hangs up. The
  module adds its service and permissions to your manifest. Play's foreground-service declaration
  is yours to make.

**Connect setup**

- The backend calls `StartWebRTCContact` with a voice contact flow. The instance's Region must
  offer in-app WebRTC calling, and Agentic CX Designer too if the assistant answers.
- To have the assistant answer, use the same Agentic CX flow block as chat. Its speech recognition
  engine defaults to Amazon Connect agentic voice.
- For a human agent to take the call, the queue's routing profile must take voice as well as
  chat.
- `session.contactId` is set once the call reaches `TouchpointVoiceCall.Phase.CONNECTED`. Read it
  from `call.phase`.

### 9. Screen share

The customer can share this app's screen with the agent during a call, never the whole device.

```kotlin
// One switch: it turns screen sharing on in the transport and asks your backend for it.
val call = TouchpointVoiceCall(voiceSession, voice, displayName = "Jordan", allowsScreenShare = true)

// The rail and the full-screen view offer the share. For your own control:
call.screenShare.collectAsState()   // AVAILABLE once the agent asks, then STARTING and SHARING
call.startScreenShare(activity.window)

Text(balance, Modifier.redactedFromScreenShare())   // painted black in every shared frame
PinEntry(Modifier.secureFromScreenShare())          // blanks the whole frame while on screen
```

- With `screenShare = true`, your backend must pass
  `AllowedCapabilities: {Customer: {ScreenShare: "SEND"}}` to `StartWebRTCContact`.
- The agent needs the **Video calls – Access** permission (`VideoContact.Access`) in a security
  profile, and starts the session from the agent workspace. The SDK's call views then ask the
  customer before anything is sent.
- **Only the app's own window is copied.** Other apps, notifications and the system bars are never
  sent, and a `FLAG_SECURE` window sends nothing. There is no MediaProjection and no system
  prompt, so the SDK asks the customer itself.
- The share stops when the call ends.

The [security guide](docs/SECURITY-GUIDE.md#screen-sharing) covers redaction in detail.

### 10. Live Sync

Live Sync lets the assistant act on your app during a chat or call: open a screen, filter a list,
fill a form. Each screen declares the actions available while it is on screen. The SDK advertises
the visible set, and runs an action's handler when the assistant invokes it.

```kotlin
import com.amazon.connect.touchpoint.livesync.*

@Serializable
data class TransactionFilter(val amount: Double? = null, val date: String? = null)

@Composable
fun TransactionsScreen(model: TransactionsModel) {
    TouchpointScope("transactions") {
        TouchpointAction("filter_transactions", "Filter the list by amount or date", TransactionFilter.serializer()) { filter ->
            model.apply(filter)
        }
        Transactions(model)
    }
}
```

Once the contact exists, bind a client to it and wrap your root in the declarations:

```kotlin
val client = LiveSyncClient(
    LiveSyncConfiguration(region = "us-west-2", conversationId = contactId, channel = LiveSyncChannel.from(Locale.getDefault())),
    credentials = broker,
)
client.attach(chatOrVoiceSession)
val aggregator = TouchpointActionAggregator(client)
val consent = LiveSyncConsentController(
    client, ConsentStore(AndroidStores.secureStore(context), deploymentId = "support-assistant"),
    ConsentPrompt(dataDescription = "which screen you're on and what you can do there", recipient = "Example Bank's assistant"),
)
TouchpointLiveSync(aggregator) {
    RootScreen()
    LiveSyncConsent(consent)               // reads the earlier decision; asks only when there is none
    TouchpointActionConfirmation(client)   // for actions declared with confirmationRequired
}
```

For your own consent UI, call `consent.load()`, collect `consent.needsPrompt`, and call
`consent.grant()` or `consent.deny()`.

How it behaves:

- **Consent governs sharing.** Until the customer grants consent, the client posts nothing about
  your screens: no context, so the assistant learns nothing of what the app shows or can do. The
  decision is kept per deployment.
- **The caller drives the actions.** As on Touchpoint for the web, the assistant acts on what the
  customer asks for, by voice or text, through the actions the current screen declares. Consent is
  not a gate on those actions. To keep the action socket closed until the grant, create the client
  with `autoConnect = false` and call `connect()` after it (the
  [security guide](docs/SECURITY-GUIDE.md#live-sync) explains).
- **Agent present, assistant paused.** Invocations are suspended while a human agent is on the
  contact (`attach(session)`).
- **Payloads are checked on the device** against the schema you declared. The SDK supports a
  strict subset of JSON Schema and fails closed on anything else; the
  [security guide](docs/SECURITY-GUIDE.md#live-sync) lists it. Declare only actions the customer
  can undo, never a payment or a submission, and still check business rules in the handler.
- **Bind to the right contact.** For chat, bind after `startChat` returns. For voice, bind when the
  call reaches `CONNECTED`. A client bound to an old contact stays connected and receives nothing.
- **The app's lifecycle is yours to pass on.** Call `client.enterBackground()` and
  `enterForeground()` from `ProcessLifecycleOwner`, and `close()` when the contact is done.
- **Keep each screen's context small.** Live Sync accepts up to 8 KB of context per update, so
  declare only the actions the screen needs, with short descriptions and schemas.
- **One client per contact.** If your composition outlives contacts, build the aggregator with
  `TouchpointActionAggregator(post = …)`, and call `readvertise()` after switching clients.

**Agentic CX Designer setup**

- The contact flow's Agentic CX flow block must point at the application that has the **Live Sync
  node**. Live Sync then follows the same contact.
- In that node, declare each action your app advertises as a custom action. Give it the same name
  and schema, and scope tags that match your `TouchpointScope` names. The assistant doesn't
  reliably use an action the node doesn't know.
- Your backend vends two values from the application's settings, under **Access**: the
  **deployment key** (the same value as the block's alias) and the **API key**. A Live Sync
  *script* key doesn't work here: the service refuses the context request with 403, so the
  assistant never learns what the screen can do.
- The keys don't expire on their own. Return `expiresAt` if you rotate them on a schedule. If you
  return `null`, the SDK keeps them until the service refuses them or you call
  `rotateCredentials()`.
- The SDK builds the channel as `connect-<language>` from the locale you pass. If the application
  supports one language, pass that locale rather than the device's.
- Unlike chat and voice, Live Sync needs no AWS credentials on the backend, only the two keys.

**When the assistant talks but the app doesn't move**, check these in order:
1. The contact id the client is bound to.
2. Whether the action is in the advertised set on the current screen. A scope applied inside the
   action, or a screen that declares nothing, leaves it out.
3. `client.onError`, including `ContactMayBeStale`, and `client.diagnostics`, in particular
   `rejectedInvocations` and `suppressedInvocations`.

`TouchpointLiveSyncOverlay` shows these on screen. It's a debug aid, in English.

### Setup checklist

| Feature | Amazon Connect | Agentic CX Designer | Your backend | Your app |
| --- | --- | --- | --- | --- |
| Chat | Chat-enabled instance, inbound chat flow, queue, routing profile, agents | — | `StartChatContact` with the interactive content types | `ConnectChatTransport` |
| Assistant | Flow with the Agentic CX flow block | Deployed application and its alias | — | `modalities` for custom modalities |
| Sign-in | Cognito pool associated with the instance; Authenticate Customer block with Customer Profiles config | — | `UpdateParticipantAuthentication` | `ChatAuthentication`; a redirect intent filter or App Link |
| Guides | Show view block | — | — | `connectInstanceUrl` |
| Attachments | Attachments storage (S3) | — | — | — |
| Voice | Voice contact flow; routing profile that takes voice; Region with WebRTC calling | — | `StartWebRTCContact`, `ClientToken` | `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`; `touchpoint-voice-telecom` for the background |
| Screen share | Agent security profile with Video calls – Access | — | `AllowedCapabilities.Customer.ScreenShare = SEND` | `TouchpointVoiceCall(…, allowsScreenShare = true)`; redaction |
| Live Sync | Flow whose Agentic CX flow block uses the Live Sync application | Live Sync node declaring the actions and scope tags; deployed build | Vends the deployment key and API key | `TouchpointAction`, consent, bind on contact, lifecycle |

## Developing

```sh
make hooks      # once per clone: pre-commit (lint + secret scan) and pre-push (make check)
make check      # build, test, lint, scan, dependency graph, manifests, string resources, symbols, API
make check-jvm  # the JVM tier, lint and scan — the fast loop, and all a machine without the SDK can run
make connected  # the instrumented tier on an attached device or emulator
```

`make check` needs no device and no credentials; it needs the Android SDK and network access for
Gradle's dependency fetch. The gates it runs, and the requirement each one enforces:

| Gate | What it holds |
| --- | --- |
| `Scripts/lint.sh` | No `!!`, unsafe cast, `TODO()`, `error()`, `require`/`check`, process exit, or thrown precondition exception in library code. No literal `dp`/`sp` in Compose code outside the metrics type |
| `Scripts/scan-secrets.sh` | No credential, internal hostname, account identifier, internal link, or literal ACXD hostname in any tracked or untracked file; the pre-commit form reads what is staged, from the index |
| `Scripts/check-build-graph.sh` | Chat does not link Chime; core, core-android and Live Sync link neither external SDK; voice links Chime's full media, never the audio-only no-video-codecs build (screen sharing sends video). Nothing depends on `touchpoint-development`. Fails on any unresolved dependency and any module without a rule |
| `Scripts/check-manifests.sh` | A packaged library manifest declares nothing but reviewed removals, and what each module's dependencies merge into the host's manifest is exactly the reviewed list — so voice strips Chime's CAMERA, and a dependency bump cannot add a host permission unseen |
| `Scripts/check-string-resources.sh` | Every packaged resource of every type carries `touchpoint_`. String values are present, translations have defaults, and UI code shows no translatable literal |
| `Scripts/check-release-symbols.sh` | No release artifact contains `DevelopmentBackend` |
| `Scripts/test-scripts.sh` | Every gate above fires on its must-fail fixture and passes its must-pass one |

Securing an integration — what the SDK protects, and what an app and its backend must do — is in
[docs/SECURITY-GUIDE.md](docs/SECURITY-GUIDE.md). To contribute, see [CONTRIBUTING.md](CONTRIBUTING.md).

## Security

To report a potential security issue, use the AWS
[vulnerability reporting page](https://aws.amazon.com/security/vulnerability-reporting/); please do
**not** create a public GitHub issue. What the SDK protects, and what your app and backend must do, is
in the [security guide](docs/SECURITY-GUIDE.md).

## Licence

See [LICENSE](LICENSE), and [NOTICE](NOTICE) for third-party notices.
