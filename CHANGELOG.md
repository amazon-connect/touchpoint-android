# Changelog

All notable changes to the Amazon Connect Touchpoint SDK for Android. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- **A voice call that builds its own request**, as iOS's: `TouchpointVoiceCall(session, voice,
  displayName, permissionPolicy, allowsScreenShare, attributes)`. `allowsScreenShare` sets both of
  the share's switches, the transport's and the request's; `permissionPolicy` lets the call's views
  ask for the microphone. The `onStartCall` constructor stays for a host that builds its own request.
- **The call's screen share:** `call.screenShare` (a `StateFlow`), `call.startScreenShare(window)` and
  `call.stopScreenShare()`, as iOS's.
- **`TouchpointLaunchButton`**, as web's `LaunchButton` and iOS's: the design guide's pill (the
  assistant's sparkle, then the host's words, on the chat's surface under the Float shadow), or the
  mark alone. `icon` takes the brand's launch icon. The demo's own pill is now this.
- **Demo:** the partner bank look is held light, as the partner's web configuration asks, with dark
  system-bar icons to match.
- **`LiveSyncConsent(consent)`:** the consent in one line, as iOS's `.liveSyncConsent`. It reads the
  earlier decision and shows the SDK's consent sheet only when there is none.
- A developer guide in the README, one section per feature: what the app, the backend, Amazon
  Connect and Agentic CX Designer each need, and a setup checklist, as the iOS SDK's.

### Fixed

- **How to get `DevelopmentBackend`, said correctly.** The README and the security guide told you
  to declare `touchpoint-development` with `debugImplementation`, but it isn't published to Maven
  Central. Its source is in the repository: copy `DevelopmentBackend.kt` into your app's
  `src/debug/` source set.
- **Messages from one sender sit together**, in the design guide's rhythm: spacing between items
  (4dp within one sender's run, 24dp between senders) plus each item's own vertical padding (4dp for
  a text message, 8dp for cards and inputs, none at a name-and-avatar header or a time or receipt
  under a message). Two text messages in a run are 12dp apart, where every message was 32dp apart.
- **A picked card or date stays shown** when a reply in the same minute takes its message's time.
- **Live Sync consent, said precisely.** The README and the security guide promised that the client
  "sends nothing" before consent. Consent governs sharing: no screen context is posted until the
  grant. It does not gate actions, which follow the caller as on Touchpoint for the web, and the
  action socket opens when the client is created. To keep it closed until the grant, use
  `autoConnect = false` and `connect()` after it. No behaviour changed.
- **Security floors on two transitive dependencies of `touchpoint-chat`:** Gson 2.8.9 or later
  (CVE-2022-25647; the Connect chat SDK 3.0 brings 2.8.5 through Retrofit) and Guava 33.7.2-android
  or later (CVE-2026-102554; Media3 1.11.1 brings 33.3.1-android). They are dependency constraints,
  published with the module, so your build resolves the fixed versions without declaring either.
- **Sign-in:** the participant calls behind it never follow a redirect, so a participant or
  connection token never reaches a host a redirect names, and a sign-in socket that is not `wss` is
  refused.
- **Keyboard:** the first send from the welcome screen keeps the composer focused and the keyboard
  up, where the composer moved and the keyboard dropped.
- A disabled main button (sign-in while it works) keeps a readable label.
- In a call, someone leaving stops their speaking ring at once, with no half-second off-delay.
- On a panel 768 and wider, the end confirmation also covers the floating header.
- Carousel cards are no wider than the transcript.

### Changed: the Touchpoint design guide

The chat and voice UI follow the Touchpoint design guide, over Touchpoint web 1.0.5 where the two
differ.

- **Type and spacing:** the guide's scale — Medium 16/20 for messages and controls, Large 24/32 for the
  welcome message (Extra Large 32/40 on a tablet), Small 12/16 for times and receipts. The transcript
  sits 12 in from the panel's edges and the assistant's words 4 further; paragraphs 12 apart; 16
  between a message and the cards or input under it; room under the last message.
- **Header:** the brand is 32, and without one the guide's assistant sparkle, followed by the title
  (`headerTitle`: `null` is "AI Assistant", an empty string none; `TouchpointHiddenElement.HEADER_BRAND`
  leaves the brand out). No Restart while the settings are open; Restart is the main button after a
  failure.
- **Messages:** the customer's words in `primary`, in a 40-tall bubble held 16 from the far side; a
  human agent's in the guide's bubble under their avatar and name by default (`agentMessageBubble`
  and the new `agentParticipantInfo`, both on); the assistant's plain (the new
  `assistantMessageBubble`, off). Participant info is the guide's two forms: avatar and name over
  the first message of a run from one sender, as web's, or (`showParticipantNames = false`) the avatar
  beside it, with its room kept on the run's later messages; 20 avatars, the assistant's
  the sparkle. Times and receipts read "time • receipt", 8 in from their message, with a 12 tick.
  "Typing..." under the agent's avatar and name.
- **Welcome screen:** left-aligned, held with the composer and choices in the middle of the panel; not
  in a half-height panel, where the greeting is the first message. No logo by default
  (`welcomeScreenLogo` now off). Its choices step aside while the composer is focused.
- **Choices** sit under the message that offers them, their rules edge to edge.
- **Composer:** text 24 in; outlined in the focus colour while focused; caret in `primary`, selection
  in the new `accentEffect` theme colour; grows to 40 % of the panel and scrolls past it; Attach a
  ghost button; a disabled Send in the guide's subtle fill.
- **Rich messages:** cards' rows 16 apart; a carousel runs to the panel's edges, and once a card is
  picked the cards are the guide's disabled ones; the date input sends with the main Send; a video's
  poster under the guide's overlay, with a 40 cover-up play button.
- **Loading:** the guide's glowing dot in place of web's two-disc loader, in the chat and the call.
- **Settings:** Call us (now a host settings row; see below), last and in
  the main style, in place of a button fixed under the chat.
- **Sheet:** a top handle on the half-height sheet expands it to the whole screen (a tap or a drag
  up); a drag down collapses it. The floating card, the voice mini and a launcher wear the guide's
  Float shadow (12, black at 8 %).
- **Voice:** the mini and the rail are the guide's: the microphone and Speakers side by side as subtle
  buttons, then screen sharing (its screen-with-arrow icon) and the red close, 12 apart; while the
  call connects, the loading dot in the controls' place. Web's ripples are the guide's volume rings —
  an inner ring while someone speaks and an outer one sized by how loud they are
  (`VoiceCallState.userVolume`, `agentVolume`), slow to fade. The full-screen call has Speakers beside
  the microphone, not in the header, and the sparkle in its orb.
- **Icons:** the guide's sparkle and a phone (`TouchpointIcon.SPARKLE`, `PHONE`).
- **From the designer's answers:** the half-height sheet wears the Float shadow (its window reaches
  above it for the shadow, so it no longer blurs what is behind); icon avatars sit on `primary5`;
  the settings are Restart conversation and Download transcript (End conversation is gone, in chat
  and in a call); the full-screen call no longer shows where it is heard.
- **From the Android recording's review:** a wide brand is 32 tall and as wide as it is in every
  form; the composer grows to six lines; "Thinking..." shimmers with more contrast; a date's slashes
  are `primary20`; the voice mini and rail snap to the nearer side when let go.

### Added: a header Close that asks first, opt-in attachments

- **Header Close asks first:** with `TouchpointAddedElement.HEADER_CLOSE`, a tap while the
  conversation is live asks "Are you sure?"; End conversation ends it and closes the chat, and
  Cancel leaves both. Once the conversation has ended, Close only closes. Settings still have no End
  conversation.
- **Attachments are opt-in:** Attach shows only with the new `TouchpointChatOptions.attachmentsEnabled`
  (off by default), and still only while an agent is answering.

### Added: host wording, a header notice, settings rows

- **End confirmation over the panel:** a card on a veil over the chat, not a window of its own. It takes every touch beside the card, drops the keyboard, and Back cancels it.
- **The host's words:** `TouchpointChatOptions.copy` (`TouchpointChatCopy`), for the end
  confirmation's title, body and buttons.
- **A notice under the header:** `TouchpointChatOptions.headerNotice`, centred over a hairline
  ("Please note that this chat is being recorded.").
- **Video plays in its card:** a video message (HLS or a file) plays inline with Media3 and its own
  controls, rather than being handed to another app, which most phones cannot stream from. It pauses in
  the background and is released when the card leaves the screen. Media3's WAKE_LOCK is stripped
  from the host's manifest, as it is never used.
- **Host settings rows:** `settingsRows` (`TouchpointSettingsRow`, prominent or quiet, no icon)
  replaces `onCallUs`. A tap closes the settings, then runs the row's action.
- **Corners:** the sheet is drawn whole and clipped once, so a dark header no longer shows a light
  rim round its rounded corners.

### Changed: no deprecated dependencies

- **Amazon Connect chat SDK 3.0.0**: 2.0.x made its participant-service calls through
  the AWS Mobile SDK for Android, which is deprecated; 3.0 uses the AWS SDK for Kotlin. A custom chat
  client (`ConnectChatTransport.live` with `customChatClient`) subclasses 3.0's `AWSClient`, whose methods take and return
  the AWS SDK for Kotlin's `connectparticipant` types. A region name is checked before use, as 3.0
  accepts any string: a well-formed name outside the isolated and China partitions.
- **Host requirements:** Kotlin 2.2 or later (2.1 is deprecated as a language version), and core
  library desugaring in an app that links `touchpoint-chat`, as the chat SDK 3.0 requires.
- **Floors:** the Kotlin stdlib 2.3.21, coroutines 1.11.0 and OkHttp 5.3.2, the chat SDK 3.0's. OkHttp
  5 adds an androidx.startup initializer and the INTERNET permission to the merged manifest; the
  AWS Mobile SDK's `org.apache.http.legacy` library is gone from it.
- **ABI:** compiled at language version 2.2, interface default methods are JVM default methods; the
  `DefaultImpls` classes are gone.
- **No deprecated calls:** the platform paint through `nativePaint`, a guide card's state saved by
  position, and the Compose test rules' `v2`.

## [0.1.0] - 2026-10-30

The first release, with Touchpoint web 1.0.5's features on Android, at parity with the iOS SDK.

### Chat

- Chat with an Amazon Connect contact: a durable, encrypted transcript; resume; attachments;
  delivery and read receipts; each new message reported to the host (`onIncomingMessages`), which
  decides whether to notify — no remote push.
- Web's UI and options: the welcome screen, choices and modalities (cards, carousels, date input,
  video, the host's own), sign-in (Authenticate Customer), Connect Views as guides, message times,
  the header style, participant info, bubbles and their styles, and the half, full and floating
  sheet (from 768 wide, web's tall floating card; its header draws a wordmark brand whole).
- A custom chat client (web's `customChatClient`), so the participant token stays on the host's
  backend.
- Web's `hiddenElements`, `addedElements` (a header Close) and `icons` overrides.
- A header title (`headerTitle`, iOS's), for a brand mark beside the name.
- Web 1.0.5-6: `TouchpointBanner` (error, warning, success) for errors and for a custom modality's
  own messages, the theme's `warningSecondary` and `successSecondary`, and Start new conversation in
  the main style.

### Voice

- In-app calls over WebRTC, with web's full screen and mini.
- A call the host holds (`TouchpointVoiceCall`), with its rail, and a full screen and mini that
  adopt it. The rail and the mini drag from web's handle, 8 inside every edge.
- A scripted call (`ConnectVoiceTransport.scripted`), with no audio and no network, for demos and
  screen recordings.
- Minimise on the full-screen call (`onMinimise`), as iOS's: back to the host's rail, the call going on.
- Screen sharing of the app's own window, with redaction.
- `touchpoint-voice-telecom`: calls that go on in the background.

### Live Sync

- The client, bound to a live contact, and the customer's consent.
- Every payload checked on the device against the schema the app declared, before any handler, as
  iOS's (a fail-closed JSON Schema subset); unusable schemas reported when advertised.
- Compose declarations of what each screen can do: reversible actions only, with
  `confirmationRequired` to ask the customer first.

### Security

- An integrator guide (`docs/SECURITY-GUIDE.md`).
- Host-consumable security events.
- StrongBox-backed storage where the phone has it.
