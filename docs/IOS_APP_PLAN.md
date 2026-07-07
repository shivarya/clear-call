# ClearCall — iOS app: build & deploy plan (for a developer with a Mac)

This is a self-contained plan for building the **native iOS ClearCall app** and running it on a test iPhone. It mirrors the existing Android app (`mobile/`) against the **same backend** (`https://shivarya.dev/clear_call/`), with one deliberate difference: **no DeepFilterNet / custom noise model** — iOS uses **Apple's built-in Voice Isolation**, which is system-level and excellent, so the app just makes it easy to turn on.

Written so another developer (or Claude Code on a Mac) can execute it end-to-end. The backend is already iOS-ready (see "Backend contract"); nothing on the server or Android side needs to change first, except providing an Apple APNs key.

**Updated 2026-07-07** for Android-side changes shipped since this plan was first written — they change what "parity" means for iOS:
1. **"Isolate a voice" (Tier B) v1** — Android now ships a real (if v1/beta) implementation: DeepFilterNet3 + an enrolled-speaker gate (WeSpeaker embeddings + Silero VAD via sherpa-onnx, zero training). iOS does **not** need to build an equivalent — see §7a for why Apple's Voice Isolation already covers most of the same ground, and what it doesn't.
2. **"Phone mic with earbuds" mode** — Android now avoids Bluetooth SCO with earbuds connected (routes call audio as media/A2DP so the phone's own mic captures the near end instead of the earbud's noisier one). iOS has the same underlying SCO-vs-A2DP tradeoff; §7b sketches the iOS analog (not yet built — flag it for the Mac session that starts P5).
3. **Calling verified end-to-end + device-registration hardening (§6a)** — the first real 2-device call now works. Two backend/registration lessons the iOS app must respect: (a) a device is only callable once it has an **active `devices` row**, and registration must be **retried on every app launch** (not just at sign-in) or a transient failure leaves the user permanently uncallable; (b) **field-name mismatch — the *deployed* production backend still requires `fcmToken`** (the P4.5 backend that also accepts `pushToken` + adds the `platform` column is committed but **not yet deployed** — deploy it before the iOS app). Android currently bridges this by sending **both** `fcmToken` and `pushToken`; the iOS app should do the same until the P4.5 backend is live, then it can send `pushToken` + `platform:"ios"` alone. Register the PushKit token on launch and on token change.
4. **In-call audio output picker** — Android added a manual earpiece/speaker/Bluetooth/wired picker (it lacked one). **iOS gets this for free**: CallKit's system call UI and the Control Center / route button handle audio routing; do **not** build a custom picker — optionally surface `AVRoutePickerView` if a bring-your-own-UI screen needs it.

---

## 0. What's the same vs different from Android

| Area | Android (built) | iOS (this plan) |
|---|---|---|
| Backend / API | shared PHP API | **same API, no changes** |
| Sign-in | Google (Credential Manager) | Google Sign-In iOS SDK → same `POST /auth/google` |
| Add friends | code / QR scan / gallery | code / QR scan (VisionKit) / gallery (PHPicker+Vision) |
| Copy code | clipboard | `UIPasteboard` |
| App lock | BiometricPrompt | **LocalAuthentication (Face ID / Touch ID / passcode)** |
| Call transport | LiveKit Android | **LiveKit Swift SDK** |
| Incoming-call UI | self-managed Telecom + full-screen notif | **CallKit** |
| Ring wake | FCM data push | **PushKit VoIP push (APNs direct, NOT FCM)** |
| Device register | FCM token → `/devices/register`, retried every launch | PushKit token → same endpoint, **retry every launch** (§6a) |
| In-call audio route | manual picker (earpiece/speaker/BT/wired) | **CallKit system UI handles it** — no custom picker |
| Noise removal | DeepFilterNet3 on uplink | **Apple Voice Isolation (system)** — no custom model |
| Isolate a voice / suppress other talkers | v1 speaker gate (WeSpeaker + Silero VAD via sherpa-onnx; enrolled d-vector, zero training) | **not built — likely not needed**, see §7a |
| Earbuds: avoid noisy earbud mic | media-audio mode (no SCO; A2DP out + phone mic in) | **not built yet**, see §7b |
| Call history | none (removed) | **none** — same parity decision, don't build one |

**Why no DeepFilterNet on iOS:** iOS's Voice Isolation runs system-wide for any VoIP app using a `.playAndRecord` / `.voiceChat` audio session (which LiveKit uses). It's on-device, high quality, and user-toggled in Control Center. Re-implementing DFN would fight the OS for the voice-processing audio unit. So the iOS app *surfaces* Voice Isolation (a one-tap nudge to the mic-mode picker) rather than shipping its own model.

---

## 1. Prerequisites

- A **Mac** with **Xcode 15+**.
- An **Apple ID**. For on-device testing a free account works (7-day provisioning). For **APNs push (incoming calls) and TestFlight you need the paid Apple Developer Program ($99/yr)** — APNs auth keys and TestFlight are not available on a free account.
- Access to the ClearCall **Google Cloud** project (to add an iOS OAuth client) and the **backend `.env`** on the cPanel host (to add the APNs key — via the `clearcall-deploy-api` skill / the private connect_ssh helper).
- The test iPhone(s).

---

## 2. Backend contract (already iOS-ready — from P4.5)

No server changes needed except APNs config. Endpoints the app uses (all return `{success,data,message}`; Bearer JWT from sign-in):

- `POST /auth/google {idToken}` → `{token, user{id,name,email,userCode,avatarUrl}}`
- `POST /devices/register {pushToken, platform:"ios", deviceLabel}` — **send the PushKit VoIP token as `pushToken` and `platform:"ios"`**. The backend routes iOS devices to APNs VoIP automatically (`utils/push.php`).
- `GET /auth/me`, `POST /contacts {userCode}`, `GET /contacts`, `DELETE /contacts/{id}`
- `POST /calls {calleeUserId}` → `{callId, roomName, livekitUrl, token, ringTimeoutSeconds}`
- `POST /calls/{id}/answer` → `{callId, roomName, livekitUrl, token}` · `POST /calls/{id}/decline|cancel|end`

**LiveKit tokens** already grant `canUpdateOwnMetadata` (for the answer signal, below).

---

## 3. Signaling model — READ THIS FIRST (the one non-obvious part)

Android currently detects "answered" by the callee *joining the LiveKit room*. iOS **cannot** use that shortcut, because a PushKit-woken app must join the room **while still ringing** (to listen for a cancel — you can't send a second VoIP push to cancel, it would penalize the app). So implement the cross-platform model the backend is already prepared for:

- **Answer signal = a participant attribute.** The callee, on accepting in CallKit, sets `state=answered` on its LiveKit participant (`localParticipant.set(attributes:)`). The caller marks the call *connected* when it sees the callee's attribute flip to `answered` — **not** on mere room-join. (The Android callee already advertises this attribute; when you build iOS, also switch the **Android caller** to read the attribute instead of room-join, and 2-device test both directions. This is the deferred "P4.5 flip" — do it here, with iOS, where it can be tested.)
- **Cancel-before-answer = a LiveKit room data message.** The caller publishes a `{"type":"cancel"}` data message; the ringing callee (already in the room) receives it and tears down the CallKit call. (For an Android callee this still also arrives as the FCM `cancel` push — keep both.)
- **PushKit rule (mandatory):** every VoIP push **must** immediately `CXProvider.reportNewIncomingCall`. If you ever receive a VoIP push and don't report a call, iOS will throttle/stop delivering them. So the ring push always pops CallKit first, then the app connects to the room in ringing state.

---

## 4. Tech stack / dependencies (Swift Package Manager)

New Xcode project `clear-call/ios/` (SwiftUI app, bundle id **`dev.shivarya.clearcall`**, deployment target iOS 16+ for `DataScannerViewController`).

- **LiveKit Swift SDK** — `github.com/livekit/client-sdk-swift` (Room, CallKit + PushKit integration; see `livekit-examples/swift-example-collection`).
- **Google Sign-In** — `github.com/google/GoogleSignIn-iOS`.
- **CallKit, PushKit, LocalAuthentication, AVFoundation, VisionKit, PhotosUI, Vision** — system frameworks.
- QR **generation** — `CoreImage` `CIQRCodeGenerator` (no dependency).

Capabilities to enable in the target: **Push Notifications**, **Background Modes → Voice over IP + Audio**, and (for CallKit) nothing extra.

---

## 5. Screens (parity with Android, minus history & minus DFN)

- **SignInView** — "Sign in with Google" (GoogleSignIn) → `POST /auth/google` → store JWT in Keychain.
- **HomeView** — your 8-char `userCode` with a **copy** button (`UIPasteboard.general.string`) and your **QR** (`CIQRCodeGenerator`); "Add a friend" with a text field + **scan** (VisionKit `DataScannerViewController`) + **gallery** (`PHPickerViewController` → `Vision` `VNDetectBarcodesRequest`) — decode → prefill the field, don't auto-submit; contacts list with a call button.
- **CallView** — dialing / incoming / active states; mute; speaker (CallKit/`AVAudioSession`); a **"Reduce background noise"** button that opens the mic-mode picker (see §7). No custom NS UI.
- **SettingsView** — biometric lock is always-on (like Android); optionally a note about Voice Isolation. **No history screen.**
- **App lock** — gate the root view behind `LAContext.evaluatePolicy(.deviceOwnerAuthentication, ...)` on launch (Face ID / Touch ID / passcode), same "fail open if no passcode set" + "cold-start only" policy as Android's `BiometricGate`. The CallKit incoming UI is a system surface, so it's naturally not gated — calls stay answerable while locked.

---

## 6. Calling flow (LiveKit + CallKit + PushKit)

- **Register**: on launch after sign-in, `PKPushRegistry` for `.voIP` → on token, `POST /devices/register {pushToken:<hex>, platform:"ios"}`. Re-register on token change.
- **Outgoing**: `POST /calls` → start a `CXStartCallAction` → connect to the LiveKit room (ringback) → mark connected when the callee's `state=answered` attribute appears → `AVAudioSession` activated via `CXProvider didActivate` (LiveKit `AudioManager` engine availability toggled there per LiveKit's CallKit guide).
- **Incoming**: VoIP push → **immediately** `provider.reportNewIncomingCall(...)` → on `CXAnswerCallAction`, `POST /calls/{id}/answer`, join the room, set `state=answered`; on `CXEndCallAction` before answering, `POST /calls/{id}/decline`. If a `cancel` data message / stale ring arrives while ringing, end the CallKit call.
- **End**: `CXEndCallAction` → `POST /calls/{id}/end` (or `/cancel` if never answered).

## 6a. Device registration — don't repeat Android's bug

A user is only reachable once they have an **active `devices` row**; `POST /calls` rejects with **409 "Callee has no registered device"** otherwise. Android shipped two bugs here that cost real debugging (2026-07-07) — build iOS to avoid both:

- **Register on every launch, not just at sign-in.** Android originally registered only inside the sign-in flow, wrapped in a silent try/catch, so a transient failure (token not ready, a network blip) left the user *permanently* uncallable with no retry. Fix was to also register on every authenticated app start (the backend upserts by token, so it's idempotent). On iOS: call `/devices/register` from `PKPushRegistry`'s `didUpdate pushCredentials` **and** on every launch once signed in.
- **Field name**: the **deployed** production backend still requires `fcmToken` (the committed P4.5 backend that accepts `pushToken` + adds `devices.platform` is **not yet deployed**). Until it is, send **both** `fcmToken` and `pushToken` (as Android now does); once P4.5 is live you can send `pushToken` + `platform:"ios"` alone. Deploy P4.5 before shipping iOS — APNs routing needs the `platform` column and `utils/push.php`/`apns.php`.
- **Surface failures.** A call that can't be placed should show the user *something* — Android's tap looked like a dead button until the 409 was surfaced as a toast. Do the equivalent in `CallView` (an alert/inline error) when `POST /calls` fails.

---

## 7. Voice Isolation (the iOS "noise removal")

- With LiveKit's `.playAndRecord`/`.voiceChat` session active, **Voice Isolation is available system-wide**; the user enables it in **Control Center → Mic Mode → Voice Isolation** during a call.
- Surface it in-app: a "Reduce background noise" button calls `AVCaptureDevice.showSystemUserInterface(.microphoneModes)` to pop the mic-mode picker, and you can read `AVCaptureDevice.activeMicrophoneMode` to show whether it's on. Apple keeps the final toggle user-controlled — the app can nudge, not force.
- That's the whole feature. No model, no capture hook, no enrollment.

---

## 7a. Do we need Android's "Isolate a voice" gate on iOS?

Probably not, and if it's ever wanted, it's a much smaller lift than it was on Android. Context:

- **Why Android needed custom engineering at all:** no ready-made on-device model exists that
  takes "here's a reference sample of speaker X" and suppresses everyone else — that had to be
  assembled from a speaker-embedding model (WeSpeaker) + VAD + a hysteresis gate (see
  `docs/VOICE_ISOLATION_TIER_B_PLAN.md`). It's real engineering specifically because Android has
  no OS-level equivalent.
- **Apple already ships most of the underlying capability.** Voice Isolation (§7) is Apple's own
  system-level model for "keep the phone owner's voice, suppress everything else including
  nearby talkers and room noise" — during a `.voiceChat` session it actively down-weights other
  people's speech, not just steady-state noise. For the common case (suppress background chatter
  around *you*, the phone owner), turning it on already delivers what Android needed the gate for.
- **The one thing Apple's feature can't do:** target an *arbitrary enrolled speaker* — Android's
  gate lets you enroll anyone's voice (not just the owner) and keep only that one. Voice Isolation
  is owner-only by design; there's no Apple API to condition it on a third-party voice sample.
  This only matters if a user wants ClearCall to isolate *someone else's* voice on their phone
  (e.g., handing the phone to another speaker) — a narrow case.
- **If that case ever matters:** the same sherpa-onnx approach ports directly — it's a Kotlin/JVM
  wrapper over a portable ONNX runtime; the underlying models (WeSpeaker CAM++, Silero VAD) have
  official ONNX exports usable from Swift via ONNX Runtime's C/Objective-C API, or re-implemented
  against Apple's own Sound Analysis / SNClassifySoundRequest for VAD. Until then: **don't build
  it for iOS** — surfacing Voice Isolation (§7) is the right-sized feature.

## 7b. Do we need Android's "phone mic with earbuds" mode on iOS?

Likely yes, eventually — the underlying problem is platform-agnostic (Bluetooth earbuds negotiate
a voice-call HFP/SCO link with a narrowband, noisier mic; the phone's own mic is better), but it
hasn't been built or tested on iOS yet. Sketch for whoever picks this up:

- **The iOS lever is `AVAudioSession` category options, not a LiveKit audio-type switch.** With
  category `.playAndRecord`, the option `.allowBluetoothA2DP` lets *output* go over a connected
  Bluetooth accessory's high-quality A2DP profile while *input* stays on the built-in mic — the
  same SCO-avoidance trick as Android's `AudioType.MediaAudioType`, just expressed as a session
  option instead of an audio mode. The option `.allowBluetooth` (HFP) is what pulls in the
  earbud's mic; simply omitting it (while still allowing A2DP for output) is the fix.
- **Caveat, unverified — check on a real device before shipping:** CallKit-managed calls
  configure the audio session on the app's behalf via `provider(_:didActivate:)`, and some LiveKit
  Swift SDK / CallKit integration examples default to `.allowBluetooth` for hands-free compatibility.
  Confirm LiveKit's Swift SDK lets you override the category options it applies (or apply your own
  after LiveKit configures the session, inside `didActivate`) before assuming this drops in cleanly.
- **Same UX shape as Android:** a Settings toggle (default on) + an in-call indicator when the
  mode is active; test echo (no hardware voice-call AEC in this path — confirm WebRTC's own AEC
  still runs) and A2DP latency same as the Android checklist in `docs/VOICE_ISOLATION_TIER_B_PLAN.md`.
- Not yet built. Flag for the Mac session that starts P5 — small addition once CallKit/LiveKit
  wiring (§6) exists, since it's purely an `AVAudioSession` configuration detail.

---

## 8. Push setup (APNs — needed for incoming calls)

1. **Apple Developer → Keys → create an APNs Auth Key (`.p8`)**; note the **Key ID** and your **Team ID**. Enable the key for APNs.
2. Put the `.p8` on the cPanel host in `~/secrets/clear_call/` (chmod 600), and set in the host `.env` (via the `clearcall-deploy-api` skill):
   ```
   APNS_KEY_PATH=/home/<account>/secrets/clear_call/AuthKey_XXXXXXXXXX.p8
   APNS_KEY_ID=XXXXXXXXXX
   APNS_TEAM_ID=XXXXXXXXXX
   APNS_BUNDLE_ID=dev.shivarya.clearcall
   APNS_ENV=sandbox          # development builds; TestFlight/App Store use "production"
   ```
   The backend's `utils/apns.php` already sends VoIP pushes on the `<bundleid>.voip` topic with `apns-push-type: voip` once these are set.
3. **Sandbox vs production**: a plain Xcode "Run on device" build uses the **sandbox** APNs environment; TestFlight/App Store use **production**. Match `APNS_ENV`, or VoIP pushes silently won't arrive.

---

## 9. Google Sign-In (iOS) setup

1. Google Cloud Console → Credentials → create an **OAuth client → iOS**, bundle id `dev.shivarya.clearcall`. It yields an iOS client ID and a **reversed-client-id URL scheme** — add that URL scheme to the target's Info.
2. The server verifies ID tokens against the **web** client ID already configured (`GOOGLE_CLIENT_ID`); add the iOS client ID to `GOOGLE_ALLOWED_AUDIENCES` on the host `.env` if the server enforces audience. Send the ID token to `POST /auth/google` exactly like Android.

---

## 10. Build & run on a test iPhone

1. Open `clear-call/ios/` in Xcode; select the target → **Signing & Capabilities** → your Team (personal team works for local device runs); ensure bundle id `dev.shivarya.clearcall`; add the **Push Notifications** + **Background Modes (Voice over IP, Audio)** capabilities.
2. Plug in the iPhone, trust the Mac, select it as the run destination, press **Run**. First run: on the iPhone, **Settings → General → VPN & Device Management → trust your developer certificate**.
3. Free-account caveat: the provisioning profile **expires after 7 days** — re-run from Xcode to refresh. Also, **APNs requires the paid program**, so on a free account you can test everything *except* real incoming-call push (you can still test outgoing + the whole UI; simulate the callee side on Android).

## 11. Deploy to testers (TestFlight — needs paid program)

1. Bump build number; **Product → Archive** → **Distribute App → App Store Connect / TestFlight**.
2. In App Store Connect, add internal testers; they install via the **TestFlight** app.
3. TestFlight uses **production** APNs — set `APNS_ENV=production` on the host `.env` (and use a distribution provisioning profile). Mismatched environments = no VoIP delivery.

---

## 12. Verification (2-device matrix)

Run iPhone ↔ Pixel, both directions:

- Sign in on both (Google); add each other by code / QR scan / gallery.
- **Outgoing iPhone → Pixel** and **Pixel → iPhone**: ring, answer, two-way audio; lock-screen ring on each; decline / cancel / timeout / busy.
- **Answer signal**: confirm the caller connects only when the callee *accepts* (not on ring-join) — verifies the participant-attribute flip on both platforms.
- **Cancel before answer**: caller hangs up while ringing → callee's CallKit/Telecom UI dismisses (data message on iOS, FCM on Android).
- **Voice Isolation**: enable it on the iPhone mid-call; background chatter near the iPhone mic should drop on the Pixel side. Confirm `activeMicrophoneMode == .voiceIsolation`.
- Cold-start ring (app swiped away), and (paid) Doze/locked delivery.

## 13. Risks / gotchas

- **PushKit strictness**: always `reportNewIncomingCall` on every VoIP push or iOS blacklists the app — use LiveKit's example pattern.
- **APNs sandbox vs production** topic/env mismatch is the most common "pushes silently don't arrive" cause.
- **Double audio processing**: WebRTC's own AEC/NS vs Apple Voice Isolation — should compose since LiveKit uses `.voiceChat`, but verify on a real iPhone it isn't double-processed / muffled.
- **The participant-attribute answer flip must land on Android too** (currently Android caller uses room-join) — do it here and 2-device test, then update `mobile/`'s `CallManager`/`LiveKitSessionManager` accordingly. See `clear-call/CLAUDE.md` P4.5.
- **Mac access is intermittent** (this is built on a borrowed Mac) — keep the backend contract frozen and the iOS app thin; batch Mac sessions.
