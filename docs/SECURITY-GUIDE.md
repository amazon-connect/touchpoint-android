# Security guide for integrators

What the Touchpoint Android SDK protects on its own, and what your app and your backend must do.

If you discover a potential security issue in this project, notify AWS Security through the
[vulnerability reporting page](https://aws.amazon.com/security/vulnerability-reporting/). Please do
**not** create a public GitHub issue.

The SDK runs inside your app. It holds a customer's conversation, short-lived credentials and, when
you turn them on, a live view of your app (screen sharing) and a channel through which an assistant
can act in it (Live Sync). Everything below is either a default you can rely on or a duty that is
yours.

## Your backend owns every credential

- **Implement `TouchpointBackend` against a backend you operate.** It starts contacts
  (`StartChatContact`, `StartWebRTCContact`) and vends Live Sync credentials. The SDK has no way to
  take an AWS key or a Connect API key in a release build, and never needs one.
- **Authenticate the customer at your backend** before it starts a contact. A contact's
  attributes (`ChatContactRequest.attributes`) are passed to your contact flow as they are; never put
  secrets in them.
- **Forward `VoiceContactRequest.clientToken`** to `StartWebRTCContact` as `ClientToken`. A request
  that times out at your gateway and is retried then cannot start a second call.
- **Sign-in (Authenticate Customer):** the device cannot call `UpdateParticipantAuthentication`
  (it is an IAM API). Your `AuthenticationRelaying` sends what the redirect carried (code, state,
  error) to your backend, which calls it.
- **Keeping the participant token off the device (optional).** Pass a custom chat client to
  `ConnectChatTransport.live(…, customChatClient)` to route every Participant Service call through
  your backend, as web's `customChatClient` does. Your `startChatContact` may then return no
  participant token. The connection token and socket URL still reach the device, because the chat
  SDK opens the socket itself. Sign-in additionally needs the client to implement
  `TouchpointSignInSocketProviding`.
- `DevelopmentBackend` is for debug builds only. It isn't published: its source is in
  `touchpoint-development/`, to copy into your app's `src/debug/` source set, so a release build
  cannot contain it. `Scripts/check-release-symbols.sh` fails any SDK release artifact that carries
  it.

## Data on the device

- Conversation text, interactive content and citations are stored encrypted (AES-256-GCM, each field
  bound to its message), with a key that is itself sealed by an AndroidKeyStore key.
- That key never leaves secure hardware: **StrongBox** where the phone has it, else the TEE. It works
  only while the device is unlocked. It is not bound to a biometric, because the SDK reads it when a
  notification is tapped and when a conversation resumes in the background.
- Everything lives under `noBackupFilesDir`: nothing goes into a backup or onto another device.
  A lost or invalidated key resets the store; it never crashes.
- Metadata stays in the clear so the store can be queried: contact ids, participant ids and roles,
  server message ids and sequence numbers, timestamps, delivery state and attachment ids and types.
- **Pruning is yours to call.** Conversations idle longer than the store's `retention` (default 30
  days) are removed, with their stored chat credentials, when you call
  `session.pruneExpiredConversations()`. The SDK never prunes on its own, so call it at launch.
- **Wiping.** `session.deleteAllConversations()` removes every row, mints a new field key and forgets
  every stored chat credential. End the active contact first.
- **Credentials on the device.** The secure store keeps each live chat's participant credentials (so
  `resume(contactId)` works) and the current Live Sync key pair. Voice credentials are never stored.
  Chat credentials go when the contact ends or its conversation is pruned or wiped; after a lost
  connection they stay, because the contact may still be live. A refresh replaces the Live Sync pair;
  otherwise it stays until the service refuses it or you call `broker.invalidateLiveSyncCredentials()`.
- **Signing out.** `deleteAllConversations()` doesn't touch Live Sync. When the user signs out, also
  call `broker.invalidateLiveSyncCredentials()` and the consent controller's `reset()`. Consent is
  stored per deployment, not per user, so without `reset()` the next user on the device inherits it.

## Data in transit

- Every connection is HTTPS or WSS. The SDK declares no network-security configuration and no
  cleartext permission: your app's own posture applies (cleartext is blocked by default from
  `targetSdk` 28).
- **No certificate pinning.** AWS rotates its certificates on its own schedule, with no pin set
  published for third-party SDKs (the Connect chat SDK, the Chime SDK and Touchpoint for the web pin
  nothing either), and pinning your own backend from inside a library would tie your rotations to
  our releases. On a supported device an app does not trust user-installed CAs by default. If you
  need pinning, it belongs to your app.
- **Do not turn on verbose HTTP logging in a release build.** The Live Sync protocol carries its API
  key in the WebSocket URL's query string. That is a property of the service, not something the SDK
  can change, so an OkHttp logging interceptor at `BODY` or `HEADERS` level would write it to the log.

## Logging

The SDK's library modules log nothing. Every type that carries conversation content, tokens or
personal details prints `<redacted>` in its `toString`, so a string template in your own logging
cannot leak them. Failures carry a status and a length, never a response body.

## Permissions and your manifest

The SDK's own manifests declare nothing; `Scripts/check-manifests.sh` enforces it, and also holds
what the SDK's dependencies merge into yours to a reviewed list: the `INTERNET` permission (OkHttp 5,
through the chat SDK 3.0 and Live Sync, and the Chime SDK for voice), OkHttp 5's unexported
androidx.startup initializer, and voice's removal of the Chime SDK's `CAMERA`. The exception is `touchpoint-voice-telecom`, an opt-in module
for calls that continue in the background: it adds a non-exported call service and the phone-call
foreground-service permissions.

The SDK never asks for a runtime permission unless you allow it: `PermissionPolicy.NEVER` is the
default. Pass a policy that names `MICROPHONE` to let the SDK ask when the customer starts a call.
It posts no notifications and needs no notification permission: new messages reach your app
through `TouchpointSession.onIncomingMessages`, and whether to notify is yours.

## Sign-in

- **Choose the redirect.** Pass it as `ChatAuthentication.redirectUri`, in one of two forms:
  - **An https link on a domain you own, with a path** (for example
    `https://bank.example/touchpoint/auth`). This is the stronger choice: no other app can claim it.
    The domain's Digital Asset Links (`/.well-known/assetlinks.json`) must name your app; the sign-in
    tab checks them before it hands the redirect back.
  - **A custom scheme your app owns** (for example `acmebank-touchpoint://auth`).
  - Plain `http`, or an https link without a path, is refused.
- **The sign-in page opens over `https` only, in an ephemeral sign-in tab.** It has its own cookies
  and keeps nothing after it closes. Where the browser supports it (Chrome's Auth Tab), the tab hands
  the redirect straight back to the SDK.
- **Older browsers open a Custom Tab instead.** The redirect then reaches your app's activity, which
  must declare it and hand it to `TouchpointSession.completeAuthentication`:
  - for a scheme, as an intent filter;
  - for an https link, as a verified App Link (`android:autoVerify="true"`).
- **The SDK accepts the redirect only if its scheme, host, path and `state` all match** what Connect
  sent out. Another app can claim a custom scheme; that is why the `state` check is enforced, and why
  a mismatch fails the sign-in (and is reported, see below).

## Guides (Connect Views)

- Pass your instance as `connectInstanceUrl`. Only an `https` Amazon Connect instance is used: a host
  ending in `.my.connect.aws` or `.awsapps.com`. Anything else is treated as no instance, and the
  guide says it cannot load.
- The renderer is Connect's own script, loaded from that instance into a locked-down WebView:
  - no file or content access, no new windows, and no navigation at all;
  - a Content-Security-Policy that keeps every fetch on the instance and runs no inline script
    except the page's own nonced boot script;
  - one bridge method, with the answer capped at 64 KB;
  - the page's storage and cookies are deleted when it closes.

## Live Sync

- **Consent governs sharing.** The SDK shows `LiveSyncConsentSheet` (or your own, from
  `LiveSyncConsentController.needsPrompt`) and posts no screen context until the customer allows
  it. They can stop at any time (`LiveSyncConsentRevoke`).
- **The caller drives the actions; consent does not gate them.** As on Touchpoint for the web, the
  assistant acts on what the customer asks for, by voice or text, through the actions the current
  screen declares. The SDK does not check consent when an action arrives. If no action may run
  before the grant, keep the action socket closed until then (`autoConnect = false`, below).
- **Declare only reversible actions.** An action is advertised only while its screen is composed,
  and the device acts only on what the screen declares. Declare what the customer can undo: filling
  in a form, filtering, selecting, navigating. Never declare a payment, a transfer, a submission or
  anything else that cannot be undone; leave that last step to the customer's own tap in your UI.
- **`confirmationRequired = true` adds a prompt to a reversible action** the customer should see
  coming, such as changing a saved setting. It does not make an irreversible action safe to declare.
  - `LiveSyncClient` holds each invocation of it until the customer answers. Show
    `TouchpointActionConfirmation(client)` once, and the SDK asks: your `confirmationMessage`, or
    else the action's description, with Allow and Cancel.
  - A refusal drops it. So does a second request while one is waiting, a human agent joining,
    consent being withdrawn, the contact ending, or its screen going away. Each one counts in
    `diagnostics.declinedConfirmations` and is reported to `onError`.
  - A host routing `onAction` by hand sets `confirmationRequirements` itself. With
    `TouchpointActionAggregator`'s main constructor, pass `confirmations` the current client's.
- **The SDK checks each payload against the schema you declared, on the device**, because the Live
  Sync engine does not. An invocation of an action not in the context you last advertised, or a
  payload its schema does not allow, is refused before the customer is asked and before any handler
  runs: counted in `LiveSyncClient.diagnostics` (`rejectedInvocations`), reported to `onError` as
  `InvalidPayload` (a keyword and a JSON Pointer, never a value), and raised as the
  `LiveSyncPayloadRejected` security event. A held action the customer allows is checked again
  against what is advertised then. The rules are iOS's, so one contract serves both apps:
  - The subset: `type`, `enum`, `const`, `properties`, `required`, `additionalProperties`, `items`
    (one schema), `minItems`/`maxItems`, `minLength`/`maxLength`, the numeric bounds, and
    `format: "date"`. Annotations (`title`, `description`, `$schema`, `examples`…) are ignored.
  - **Fail-closed:** any other keyword or format (`pattern`, `anyOf`, `$ref`, `format: "email"`…), a
    malformed value, or a kind-specific keyword without a `type` makes the schema unsupported, and
    every payload against it is refused. You hear it when you advertise it, as
    `InvalidConfiguration`.
  - Avoid what the engine rewrites (also reported when advertised): a node without a `type`, a
    `type` list, `type: "null"`, and a top-level property named `items` or `value`.
  - A `null` for an optional property counts as not sent. Set `additionalProperties: false` to
    refuse keys you did not declare.
  - After consent is re-granted, the client asks for your context again (`onContextNeeded`; an
    aggregator built on the client handles it). Until it comes, every invocation is refused.
- **Still validate every argument in your handler.** The schema says what may come, not that it is
  true: an amount within bounds can still be the wrong amount. Treat every payload as untrusted
  input from the network.
- Make every field optional, and keep schemas to what the screen needs.
- **While a human agent is on the contact, nothing the assistant asks for runs.** Dispatch is
  suspended, and invocations are dropped and counted.
- **The protocol's names are the service's.** The Live Sync socket and its messages carry the
  service's own field and host names (for example `nlx-api-key`, `type=voice-plus`). They are not
  secrets and not configurable; the key's value is what to protect.
- **Rotate Live Sync credentials** from your backend, short-lived where you can. By default the SDK
  keeps a pair until less than 20% of its lifetime remains (to `expiresAt`; change it with
  `CredentialBroker(refreshFraction = …)`), or until the service refuses it, then asks your backend
  again, so a rotated key reaches the next socket without an app update. If a refresh fails, the
  unexpired pair is kept. An open socket keeps the key it opened with:
  `LiveSyncClient.rotateCredentials()` forces a fresh key and reconnects.
- **The socket and consent.** Only the context post waits for consent. The receive-only action
  socket opens when the client is created, and its URL carries the Live Sync keys and the contact id.
  To keep it closed until consent, create the client with `autoConnect = false` and call `connect()`
  after the grant. Revoking consent closes the socket and clears what was advertised; granting again
  doesn't reopen it, so call `connect()` after a re-grant.

## Screen sharing

- Off unless you opt in (`ConnectVoiceTransport.live(allowsScreenShare = true)` and
  `VoiceContactRequest.screenShare`). The agent asks, the customer confirms, and it stops when the
  call ends or the window goes.
- Only your app's own window is sent, copied a few frames a second. Other apps, notifications and
  the system bars are never captured, and no MediaProjection is used.
- A window with `FLAG_SECURE` sends nothing while the flag is set.
- **Mark what the agent must never see**, even on a screen that is otherwise fine to share: a card
  number, a balance, a one-time code. Use `Modifier.redactedFromScreenShare()` on a composable, or
  `TouchpointScreenShareRedaction.redact(view)` for a View.
  - It is painted black in every frame before the frame leaves the device.
  - Masks are padded, and also cover where the element was in the previous frame, so nothing shows
    while it scrolls.
- **Mark a whole screen that must not be shared** with `Modifier.secureFromScreenShare()` or
  `TouchpointScreenShareRedaction.secure(view)`. While it is on show, every frame goes out black.
  `stop(view)` ends either mark.

- **Size:** `touchpoint-voice` carries the Amazon Chime SDK's full media build (about 5 MB of
  native code on arm64), because sending a screen needs its video encoder. It is the same build
  Connect's own in-app calling sample ships.

## Security events

The SDK sends none of these anywhere. They carry no conversation content, tokens or links. Collect
them into your own monitoring:

| What happened | `TouchpointSecurityEvents.events` | Also |
| --- | --- | --- |
| A sign-in redirect refused | `SignInRedirectRejected` | `session.events`: `ContactEvent.Failed(InvalidConfiguration)`; the contact goes on |
| A guide page's navigation blocked | `GuideNavigationBlocked` | `session.events`, as above |
| A guide instance that is not a Connect instance | `GuideInstanceRefused` | — |
| A Live Sync action declined | `LiveSyncActionDeclined` | `LiveSyncClient.onError`: `ActionHandlerFailed(action, "declined by user")`; `diagnostics.declinedConfirmations` |
| A Live Sync action invoked without being declared | `LiveSyncActionUnrouted` | `onError`: `ActionHandlerFailed`; the aggregator's `unroutedInvocations` |
| Live Sync consent granted or withdrawn | `LiveSyncConsentChanged` | `onError`: `ConsentNotGranted` on withdrawal |
| Screen sharing started or stopped | `ScreenShareStarted` / `ScreenShareStopped` | the call's `screenShare` state |
| An invocation while a human agent is on the contact | — | `diagnostics.suppressedInvocations` |

## Dependencies

The SDK declares minimum versions (floors) of its dependencies, not exact pins. Your build can still
force a lower version, because Gradle gives the consumer the last word; don't go below the floors in
`gradle/libs.versions.toml`. Amazon Connect's chat SDK 3.0 brings the AWS SDK for Kotlin's
`connectparticipant` client, with its smithy runtime and OkHttp 5; `Scripts/check-build-graph.sh`
keeps the AWS SDK for Kotlin to `touchpoint-chat` and the deprecated AWS Mobile SDK out of every
module. The sign-in listener's participant calls are made directly, over HTTPS with the participant's
bearer token (they are not SigV4-signed), and never follow a redirect.
