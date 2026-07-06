# ClearCall

A 1:1 internet calling app where **both parties run the app**, so each side's microphone
is cleaned **on-device** before the audio is ever sent — giving clear calls in noisy
environments. Because the cleaning happens per-sender on the uplink, the person you're
talking to always receives already-clean audio.

Third-party apps can't process the audio of WhatsApp or the system dialer without root
(a verified dead end). Owning both ends of the call is what makes real, deep-learning
noise suppression possible — it runs on the phone, never on a server, and no GPU is
required on the user's device.

> **Status:** Backend is live; the Android app makes real 1:1 calls over LiveKit with
> Google Sign-In. On-device DeepFilterNet3 noise suppression and the native iOS app are
> the next phases (see [Roadmap](#roadmap)).

## Features

- **App-to-app 1:1 calling** over WebRTC (LiveKit transport), with a native
  full-screen incoming-call UI via Android's self-managed Telecom `ConnectionService`.
- **Add friends by short code or QR** — no phone-number or contact-book upload. Share
  your 8-character code (with a one-tap **copy** button) or your QR; add others by
  typing their code, **scanning their QR with the camera**, or **decoding a QR from an
  image in your gallery**.
- **Biometric app lock** — the whole app is gated behind a fingerprint / face / device
  PIN on launch. Incoming calls remain answerable without unlocking, like a normal
  dialer.
- **Google Sign-In** via Android Credential Manager (ID-token verified server-side).
- **Push-based ringing** — FCM high-priority data messages wake the callee; call state
  (ring / answer / decline / cancel / end) is coordinated by the backend.

## Architecture

```
┌─────────────────┐        ┌──────────────────────┐        ┌─────────────────┐
│  Android app    │  HTTPS │   PHP REST backend   │  FCM   │  Android app    │
│ (Kotlin/Compose)│◄──────►│  (shivarya.dev)      │───────►│    (callee)     │
└────────┬────────┘        └──────────┬───────────┘        └────────┬────────┘
         │                            │                             │
         │       mint LiveKit JWT     │                             │
         └────────────► LiveKit Cloud (WebRTC media) ◄──────────────┘
```

- **Signaling without websockets** (shared PHP hosting can't hold sockets open):
  ring / cancel / declined are delivered as FCM data pushes; "answered" is detected
  when the remote participant joins the LiveKit room.
- **LiveKit access tokens** are plain HS256 JWTs minted in PHP.
- **On-device noise suppression** (roadmap) hooks LiveKit's `capturePostProcessor`,
  which hands us 10 ms mic frames after WebRTC's own AEC/AGC.

## Tech stack

| Layer | Tech |
|-------|------|
| Mobile | Native **Kotlin + Jetpack Compose**, minSdk 31, `applicationId dev.shivarya.clearcall` |
| Calling | **LiveKit** Android SDK (WebRTC) + Android **Telecom** self-managed ConnectionService |
| Auth | Google Sign-In (Credential Manager + Google Identity Services) |
| Push | **Firebase Cloud Messaging** (v1) |
| QR | ZXing (generate) · **CameraX + ML Kit Barcode Scanning** (scan/decode) |
| Lock | **AndroidX Biometric** |
| Backend | **PHP 8** front-controller REST API + **MySQL**, `firebase/php-jwt`, `google/apiclient` |

## Project structure

```
clear-call/
├── mobile/                         # Android app (Kotlin/Compose)
│   └── app/src/main/java/com/clearcall/
│       ├── MainActivity.kt         # entry point + biometric gate + screen router
│       ├── call/                   # CallManager, LiveKit session, ringtone
│       ├── telecom/                # self-managed ConnectionService (incoming/outgoing)
│       ├── push/                   # FCM messaging + incoming-call notification
│       ├── auth/  net/  core/      # Google Sign-In, API client, prefs/state
│       ├── security/               # BiometricGate
│       └── ui/                     # Compose screens (SignIn, Home, Call, ScanQr, …)
└── server/                         # PHP REST API → https://shivarya.dev/clear_call/
    ├── index.php  .htaccess  config/  utils/  controllers/  database/schema.sql
```

## Building & running

### Backend (local)

```bash
# MySQL (schema in server/database/schema.sql), then:
cd server
cp .env.example .env          # fill in DB, JWT secret, LiveKit + Google + FCM creds
composer install
php -S 0.0.0.0:8010           # bind 0.0.0.0, NOT localhost (see note below)
```

### Mobile

```bash
cd mobile
./gradlew :app:assembleDebug
adb reverse tcp:8010 tcp:8010                       # reach the local server
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds talk to `http://localhost:8010` and expose two dev-login buttons (so two
test users can call each other without real Google accounts). Release builds talk to
the production API over HTTPS and require Google Sign-In.

> **Local dev note:** bind the PHP dev server to `0.0.0.0`, never `localhost` — the
> latter resolves ambiguously between IPv4/IPv6 and, combined with `adb reverse`, causes
> intermittent "unexpected end of stream" errors that look like app bugs but aren't.

## Configuration & secrets

None of these are committed (all are gitignored); supply your own:

- `server/.env` — DB credentials, `JWT_SECRET`, `LIVEKIT_URL/API_KEY/API_SECRET`,
  `GOOGLE_CLIENT_ID`, `FCM_PROJECT_ID` + service-account path.
- `mobile/app/google-services.json` — your Firebase project's config.
- An FCM service-account JSON, kept outside the web root on the server.
- A Google Cloud OAuth **Web** client ID (used as both the app's `serverClientId` and
  the server's `GOOGLE_CLIENT_ID`) plus an **Android** client registered with the app's
  package name and signing SHA-1.

## Roadmap

- ✅ Backend live; Android real calls; Google Sign-In; add-by-code/QR (scan + gallery);
  copy code; biometric lock.
- ⏳ **DeepFilterNet3** on-device noise suppression in the LiveKit capture hook.
- ⏳ In-call audio routing / device picker; call edge-case polish; release keystore.
- ⏳ "Isolate a voice" — target-speaker extraction from a provided sample (on-device; no GPU). Seam shipped; models to build — see [docs/VOICE_ISOLATION_TIER_B_PLAN.md](docs/VOICE_ISOLATION_TIER_B_PLAN.md).
- ⏳ Native **iOS** app (Swift/SwiftUI, CallKit + PushKit, Apple Voice Isolation) — full build+deploy plan in [docs/IOS_APP_PLAN.md](docs/IOS_APP_PLAN.md).

## License

Personal project — no license granted for reuse at this time.
