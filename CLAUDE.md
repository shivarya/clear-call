# CLAUDE.md — ClearCall

Guidance for Claude Code when working inside `clear-call/`. A 1:1 internet calling app where **both parties run the app** and each side's mic is cleaned on-device — DeepFilterNet3 on Android, Apple Voice Isolation on iPhone — clear calls in any noisy environment, cross-platform. Exists because no third-party app can process WhatsApp's/the dialer's audio without root (verified dead end; `clear-mic-router/` is the routing-only companion for those).

**Full plan**: `~/.claude/plans/hashed-hugging-umbrella.md`. **Status (2026-07-06)**: P0 (backend) done. **P1 (Android app, first real call) DONE and verified end-to-end on the emulator**, including a real outgoing call against live LiveKit Cloud infrastructure (mic capture confirmed active). iOS app (P5) and DeepFilterNet integration (P2) not started.

## Terminal Command Rules

**CRITICAL**: Always combine directory change and command in a single line, absolute Windows paths.

## Layout

| Sub-app | Path | Stack |
|---------|------|-------|
| Server | `server/` | PHP 8.0+ + MySQL front-controller REST API (clone of ps5-tracker/diet-plan conventions) |
| Mobile | `mobile/` | Native Kotlin + Compose, applicationId `dev.shivarya.clearcall`, namespace `com.clearcall`, minSdk 31. **applicationId ≠ namespace** — `adb shell am start` needs the fully-qualified class: `dev.shivarya.clearcall/com.clearcall.MainActivity`, not the `.MainActivity` shorthand. |

Production target: `https://shivarya.dev/clear_call/` (deploy under `~/public_html/shivarya.dev/clear_call` — the docroot, NOT `~/public_html/`). Not deployed yet.

## Commands

### Server (`server/`)
```powershell
# Local MySQL is the XAMPP service (D:\xampp\mysql\bin\mysql.exe), root, no password
& "D:\xampp\mysql\bin\mysql.exe" -u root -e "CREATE DATABASE IF NOT EXISTS clear_call CHARACTER SET utf8mb4"
Get-Content database\schema.sql | & "D:\xampp\mysql\bin\mysql.exe" -u root clear_call
cd "c:\Users\Ash\Documents\Projects\apps\clear-call\server" ; php -S 0.0.0.0:8010   # NOT localhost — see gotcha below. Port 8010, 8000 is taken by ps5-tracker's task.
```
`.env` has real LiveKit Cloud credentials (`LIVEKIT_URL/API_KEY/API_SECRET`) as of 2026-07-06 — calls mint real, working tokens. `GOOGLE_CLIENT_ID` and `FCM_*` are still unset (console setup pending — see `HANDOFF.md`); `ALLOW_DEV_LOGIN=true` covers testing until then. `vendor/` was copied from `diet-plan/server/vendor` (firebase/php-jwt + google/apiclient) but is **gitignored** — real deploys run `composer install` on the host.

**⚠️ Critical dev-server gotcha (cost significant debugging time 2026-07-06): bind PHP to `0.0.0.0`, never `localhost`.** `php -S localhost:8010` on this Windows machine resolves ambiguously between `127.0.0.1` and `::1`, and `adb reverse`'s forwarding can intermittently pick the "wrong" family relative to whatever PHP actually bound — the app then sees the TCP connection accepted and instantly closed with no data, which OkHttp reports as `"unexpected end of stream on http://localhost:8010/..."`. Direct `curl localhost:8010` from the host always worked fine throughout (curl and PHP resolve consistently on the same machine), which made this look like an app bug — it wasn't. Binding `0.0.0.0` (explicit IPv4 wildcard) fixed both the `adb reverse` path (physical device) and the `10.0.2.2` alias path (emulator) simultaneously. If `adb reverse tcp:8010 tcp:8010` ever seems to silently stop forwarding (curl from host works, app gets "unexpected end of stream", **and PHP's request log shows nothing at all** for the failing calls), check the PHP bind address first before suspecting adb/emulator state.

### Mobile (`mobile/`)
```powershell
cd "c:\Users\Ash\Documents\Projects\apps\clear-call\mobile" ; .\gradlew.bat :app:assembleDebug
adb reverse tcp:8010 tcp:8010   # per device (emulator AND physical) — re-run if requests start failing
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n dev.shivarya.clearcall/com.clearcall.MainActivity
```
`ApiClient` (`net/ApiClient.kt`) sends `Connection: close` and retries once on any transient I/O failure — defends against `php -S`'s single-threaded/no-keepalive behavior locally and ordinary mobile-network blips in production; a real 4xx/5xx API error never retries.

## Architecture

- **Signaling model** (no websockets on shared PHP hosting): ring/cancel/declined travel as FCM v1 high-priority data pushes; **"answered" is detected by the remote participant joining the LiveKit room** (caller sits in-room during ringback). `LiveKitSessionManager.remoteJoined` is a `SharedFlow` fed by LiveKit's own event flow — note the event-collection extension is `io.livekit.android.events.collect`, not the stdlib `kotlinx.coroutines.flow.collect` (the `Room.events` type needs LiveKit's own collector).
- **`CallManager`** (`call/CallManager.kt`) is the single orchestrator — every path (our own UI buttons, Telecom's `on*` callbacks for Bluetooth/Android-Auto-triggered answer/reject/disconnect, FCM ring/cancel/declined) funnels through the same idempotent entry points (`answerCurrentCall`, `declineCurrentCall`, `hangup`), so it never matters who asked for the state change first.
- **`utils/livekit.php`** — LiveKit access tokens are plain HS256 JWTs (iss=API key, sub=`u{userId}`, `video` grant); minted with firebase/php-jwt. Callee's token is only minted at `/answer`, never pushed through FCM.
- **`utils/fcm.php`** — FCM v1 via google/apiclient service-account assertion; data values stringified; `UNREGISTERED` responses deactivate the device row; **gracefully skips when FCM env is unset** (`pushSkipped:true`) so call flows stay curl-testable.
- **`controllers/callsController.php`** — the core: busy checks both parties (fresh ring or answered-unended), records `busy`/`failed(no_device|push_failed)` attempts for history, `reapStaleRinging()` lazily converts abandoned rings to `missed`, all transitions guarded by role (caller/callee) and idempotent.
- **Auth**: Google Sign-In (`/auth/google`, keyed by immutable `google_sub`) → own 30-day JWT (`utils/jwt.php`, copied verbatim from diet-plan). Dev backdoor `POST /auth/login {as:1|2}` seeds/logs in two test users (call tests need two) — mobile `SignInScreen` shows "Login as Dev 1/2" buttons whenever `BuildConfig.GOOGLE_WEB_CLIENT_ID` is blank. Contact discovery is by 8-char `user_code` (unambiguous alphabet) — no phone/contact upload (Play Store privacy).
- **Debug-only network security config** (`mobile/app/src/debug/res/xml/network_security_config.xml` + matching manifest override) allows cleartext HTTP to `localhost`/`10.0.2.2` only — release builds have no such override and only ever talk to `https://`.

## Endpoints

`GET /health` · `POST /auth/google|login` · `GET /auth/me` · `DELETE /auth/account` · `POST /devices/register` · `POST|GET /contacts`, `DELETE /contacts/{id}` · `POST /calls`, `POST /calls/{id}/answer|decline|cancel|end`, `GET /calls?limit=`

## Environment (`server/.env`)
```
DB_* · JWT_SECRET · JWT_EXPIRES_IN · GOOGLE_CLIENT_ID · GOOGLE_ALLOWED_AUDIENCES
ALLOW_DEV_LOGIN (never true in prod) · LIVEKIT_URL/API_KEY/API_SECRET
FCM_PROJECT_ID · FCM_SERVICE_ACCOUNT_PATH (chmod 600, .htaccess-denied) · RING_TIMEOUT_SECONDS=45
```

## Verified working (2026-07-06, emulator + real LiveKit Cloud)

Dev login → session persistence → contacts fetch/display → QR code generation → **outgoing call placement with a real LiveKit token, real room connect, and live microphone capture confirmed** → clean hangup (`POST /calls/{id}/cancel`) → state reset → navigation back to Home. Not yet tested: incoming call / two-way audio between two signed-in devices (needs a second device signed in — try Pixel + emulator, or two emulators).

## Key P2+ facts (verified research, don't re-derive)

- LiveKit Android SDK hook for our NS: `CustomAudioProcessingFactory` / `AudioProcessorOptions(capturePostProcessor=…)` — 10ms capture frames post-AEC.
- NS engine: `io.github.kaleyravideo:android-deepfilternet:0.0.8` (Maven Central, DFN3, real-time). Probe the actual frame format (rate/bands/int16-vs-float) with a logging-only processor BEFORE writing the adapter.
- LiveKit Cloud free tier: 5,000 participant-min/month hard cap (≈2,500 1:1 call minutes); self-hostable later.
- iPhone (P5, later): separate native Swift app — LiveKit Swift SDK + CallKit + PushKit (VoIP pushes need direct APNs, NOT FCM). Backend needs `devices.platform` + `utils/apns.php` first (P4.5).
- Tier B ("only my voice"): ONE generic speaker-conditioned model (VoiceFilter-Lite style) trained developer-side; users enroll on-device (d-vector) — users never need a GPU.
