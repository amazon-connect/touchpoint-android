# Acme Bank — Touchpoint SDK demo

A fictional bank app that integrates the Touchpoint SDK the way a customer would: by module
dependency, through the public API only. Everything in it is synthetic. It is not part of the
published SDK.

```sh
./gradlew :acme-bank:installDebug      # on an attached device or emulator
./gradlew :acme-bank:test              # fixture dates, palette contrast, scripted agent, SigV4
./gradlew :acme-bank:connectedDebugAndroidTest   # the UI tests (also part of `make connected`)
```

**Modes.** Offline (default) talks to a scripted agent and needs nothing provisioned. Live (debug
builds only, Settings → Support mode) reaches a real Amazon Connect agent through the demo backend.
Deploy the demo backend from `Demo/backend` in [touchpoint-ios](https://github.com/amazon-connect/touchpoint-ios), run `npm run app-config`, and save the Android part as `demo/acme-bank/live.properties`.
Until it merges, the backend is in [touchpoint-ios#7](https://github.com/amazon-connect/touchpoint-ios/pull/7).
The file is gitignored and holds identifiers only (see `live.properties.example`); a debug build
with it starts in live mode. The app signs the demo user in to the backend's Cognito user pool, gets
temporary credentials that can call the demo API and nothing else, and gets the Live Sync keys from
the backend after every sign-in. Without the file, temporary IAM credentials can be entered on the
Settings screen; they are kept in memory only. The release variant compiles a stub in its place, so
no live-mode code or field ships in a release APK. A production app never carries AWS credentials —
its own backend vends the participant token.

**Design.** The support dock is a pill that expands into a
docked panel with the SDK's `TouchpointChatView`; the bank's screens shrink to the space above it and
stay usable. Suggested-reply chips are the demo's own (a chip is a shortcut for typing); in live mode,
Support opens in voice: the SDK's `TouchpointVoiceView`, an in-app call through the demo backend's
`POST /voice/start`, with Live Sync bound to the call (the older phone-call demo, where Connect
rings the customer, is the `voiceByPhone` seed key, with `callbackNumber`); nothing fakes rich cards. The bank's screens are Material 3 — the
host's choice; the SDK links no Material.

**Voice calls and Live Sync.** Live Sync lets the assistant move the bank's screens while the
customer talks to it. The SDK has a safety gate for this: once the Live Sync client is attached to
the support session, the SDK pauses Live Sync as soon as a human agent joins the contact, so a person
on the contact is never overridden by the assistant; actions that arrive while paused are dropped.
Chat uses the gate. Voice calls (the in-app call and the phone call) do not attach it. On an in-app
WebRTC call the SDK counts Connect's own side of the call as a human agent, so with the gate attached
Live Sync pauses the moment the call connects, while only the AI agent is talking, and the screens
never move. The iOS demo does not attach its Live Sync client to the session either, so both demos
behave the same. The cost: if a human agent joins a voice call, the assistant is not paused and the
screens can keep moving while the agent is on. In chat a human agent still pauses it. This should be
revisited once the SDK can tell a call's AI agent from a human agent; voice can then use the gate
like chat.

To watch Live Sync in a debug build, open Settings → Live Sync (debug): it shows the connection
state and a log of each step (`gate → attached` or `gate → not attached (voice)`, `state → …`,
`context → …`, `dispatch → …`, and each action the assistant sends). Debug builds also write the same
lines to logcat under the tag `AcmeLiveSync`; release builds do not, and the lines never hold keys:

```sh
adb logcat -v time AcmeLiveSync:I *:S
```

On a working voice call the log shows `gate → not attached (voice)`, `state → Connected`, the
actions as the assistant sends them, and no `dispatch → SUSPENDED`.

**Tests.** The unit tier pins the fixture's relative dates ("about $240, last Tuesday" must keep
matching) and measures the palette against WCAG AA. The instrumented tier signs in, opens Support,
sends a message and waits for the scripted reply with the Accessibility Test Framework checking
every interaction; it also covers minimise-and-reopen, a second chat after sign-out, back
navigation and larger text. `Scripts/check-competing-pins.sh` is the realistic dependency-conflict
check and runs in `make check`.
