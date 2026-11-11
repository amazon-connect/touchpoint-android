# Changelog

All notable changes to the Amazon Connect Customer Touchpoint SDK for Android. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions [Semantic Versioning](https://semver.org/).

## [0.1.1] - 2026-11-11

The first release on Maven Central. 0.1.0 was tagged on GitHub but never published; 0.1.1 has the
same libraries, and is the first built, signed and uploaded by the repository's release workflow.
What the SDK gives an app is under 0.1.0.

## [0.1.0] - 2026-11-10

The first release: Amazon Connect Customer Touchpoint on Android, with Touchpoint for the web's
features and options, drawn to the Touchpoint design guide.

### Requirements

- Android 12 (API 31) or later, and Kotlin 2.2 or later.
- An app that links `touchpoint-chat` needs core library desugaring, which the Amazon Connect chat
  SDK 3.0 requires.
- Floors: the Kotlin standard library 2.3.21, coroutines 1.11.0 and OkHttp 5.3.2. Gson 2.8.9 and
  Guava 33.7.2-android or later are published as dependency constraints (CVE-2022-25647,
  CVE-2026-102554), so a build resolves the fixed versions without declaring either.

### Chat

- Chat with an Amazon Connect contact, on the Amazon Connect chat SDK 3.0: a durable, encrypted
  transcript; resume; delivery and read receipts; reconnection that resends what wasn't
  acknowledged. Each new message is reported to the app (`onIncomingMessages`), which decides
  whether to notify. There is no remote push.
- `TouchpointChatSheet` (half, full, and a floating card from 768 wide) and `TouchpointChatView`.
  `TouchpointLaunchButton` opens it: the assistant's mark and the app's words, or the mark alone.
- What the assistant sends, drawn with no code from the app: choices, cards, carousels, a date
  input, video (played in its card), interim lines and markdown. The app registers its own
  modalities by name.
- Sign-in (Authenticate Customer) in an ephemeral browser tab, Connect Views as guides, and opt-in
  attachments (`attachmentsEnabled`) while an agent answers.
- Touchpoint for the web's options: the welcome screen, participant names and avatars, bubbles and
  their styles, message times and receipts, the header's brand, title (`headerTitle`), style and
  notice (`headerNotice`), `hiddenElements`, `addedElements` (a header Close that asks before ending
  a live conversation), `icons`, and the app's own words (`copy`).
- Settings with the app's own rows (`settingsRows`), and Restart conversation and Download transcript.
- A custom chat client (`customChatClient`), so the participant token can stay on the app's backend.
- `TouchpointTheme` (light, dark, system, an accent) and `TouchpointBanner` for errors, warnings and
  successes. The SDK's strings are Android resources in English and Touchpoint for the web's eleven
  other languages.

### Voice

- In-app calls over WebRTC (the Amazon Chime SDK), with a full screen and a mini.
- A call the app holds (`TouchpointVoiceCall`): it builds its own request, or the app does
  (`onStartCall`), and its rail, full screen and mini share it.
- Screen sharing of the app's own window (`call.screenShare`, `startScreenShare`, `stopScreenShare`),
  with redaction of what the app marks.
- A scripted call (`ConnectVoiceTransport.scripted`), with no audio and no network, for demos and
  screen recordings.
- `touchpoint-voice-telecom`, opt-in: calls that go on in the background, through Jetpack
  core-telecom.

### Live Sync

- The client, bound to a live contact, and the customer's consent (`LiveSyncConsent`), which governs
  what the app shares. Actions follow the customer's request; to keep the action socket closed until
  consent, create the client with `autoConnect = false`.
- Compose declarations of what each screen can do: reversible actions only, with
  `confirmationRequired` to ask the customer first.
- Every payload is checked on the device against the schema the app declared, before any handler.

### Security

- An integrator guide (`docs/SECURITY-GUIDE.md`).
- Security events for the app (`TouchpointSecurityEvents`).
- Credentials in the Keystore, StrongBox-backed where the phone has it.
- Sign-in's participant calls follow no redirect, and a sign-in socket that isn't `wss` is refused.
