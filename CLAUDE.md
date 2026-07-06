# CLAUDE.md — ClearCall

Guidance for Claude Code when working inside `clear-call/`. A 1:1 internet calling app where **both parties run the app** and each side's mic is cleaned on-device — DeepFilterNet3 on Android, Apple Voice Isolation on iPhone — clear calls in any noisy environment, cross-platform. Exists because no third-party app can process WhatsApp's/the dialer's audio without root (verified dead end; `clear-mic-router/` is the routing-only companion for those).

**Full plan**: `~/.claude/plans/hashed-hugging-umbrella.md`. **Status (2026-07-06)**: P0 (backend) done and **deployed live** at `https://shivarya.dev/clear_call/`. **P1 (Android app, first real call) DONE and verified end-to-end**, including a real outgoing call against live LiveKit Cloud infrastructure (mic capture confirmed active). **Google Sign-In is fully wired** (real Web client ID in both the app and server, Android client registered with the debug-keystore SHA-1) — verified reaching real Credential Manager/Google Play Services on the emulator; end-to-end sign-in itself needs a device with a real Google account (the Pixel has two, emulator has none). Only the native iOS app (P5) remains; everything else through P4.5 is built (see below).

**P1.5 (contact-add UX + biometric lock) DONE, emulator-verified.** (1) QR **scan** (CameraX + ML Kit `barcode-scanning`) via `ui/ScanQrScreen.kt` + **gallery decode** (system `PickVisualMedia`) — both prefill the "add friend" code field, never auto-submit; `ui/QrDecoder.kt` holds the shared `BarcodeScanner`. (2) **Copy** button for own code. (3) **Biometric app-lock** — `security/BiometricGate.kt` (ported from `voice-recorder`), `MainActivity` is now a `FragmentActivity`, the gate wraps `AppRoot()` (blocks SignIn too), **fails open** if no device lock is enrolled, re-locks only on cold start; `IncomingCallActivity` is left ungated so calls stay answerable. (4) **History screen removed** client-side (`HistoryScreen.kt` + `listCalls()`/`CallHistoryItem` deleted) — the backend `calls` table/`GET /calls` are untouched (still needed for busy-check/idempotent-end/missed reconciliation). **Gotcha: CameraX is pinned to `1.3.4`** — 1.4+/1.6+ require AGP 8.9.1+, this app is on AGP 8.7.3; also added `androidx.lifecycle:lifecycle-runtime-compose` for `LocalLifecycleOwner`. LiveKit 2.26.1 pulls no `androidx.camera`, so no conflict.

**P2 (on-device noise suppression) DONE, emulator-verified.** DeepFilterNet3 (`io.github.kaleyravideo:android-deepfilternet:0.0.8`, bundled 48 kHz model, `libdf.so` for all 4 ABIs incl. x86_64) runs in LiveKit's `capturePostProcessor`. `audio/`: `NoiseProcessor` interface, `DfnNoiseProcessor` (wraps `NativeDeepFilterNet`; **resamples the capture-rate hop to 48 kHz and back** by integer ratio — the emulator captures at 16 kHz so it upsamples ×3; rates not dividing 48 kHz safe-bypass), `CaptureProcessorBridge` (implements `AudioProcessorInterface`; ring buffers reconcile WebRTC's 10 ms frame with the model frame; `AtomicBoolean` A/B bypass; watchdog auto-bypass on realtime-budget overrun; any exception → permanent bypass — **NS can never break a call**; first frame logs the probe format). `NoiseSuppression` is the app-scoped owner (reused across calls). Debug-only in-call overlay (`ui/NoiseDebugOverlay.kt`) + `ui/SettingsScreen.kt` on/off + strength. **Probe result on emulator: numBands=1, mono, float32, capture 16 kHz/160-frame, model frameLength=960.** Not verified: audio *quality* and the Pixel's real capture rate (48 kHz → no resampling) — needs a 2-device run on hardware.

**P4 (Tier B — target-speaker extraction) — seam only.** Corrected design: keep only the voice matching a provided *reference sample* (any speaker, not just the phone owner — like Teams voice isolation / VoiceFilter-Lite), removing all other voices + background. `SuppressionEngine{OFF,DFN3,TARGET_SPEAKER}` drives `NoiseSuppression`; `SpeakerConditionedProcessor.setTargetEmbedding` + `TargetVoiceProfile.computeEmbedding` are documented stubs that compose DFN3 until the two developer-side models land (a **speaker-encoder** sample→d-vector, and a **target-speaker separation** model conditioned on it, real-time on-device via TFLite). `Prefs.targetVoiceName/targetVoiceEmbedding` store one local profile. Settings: General vs "Isolate a voice · beta" + a record-a-sample affordance (coming soon). **No off-the-shelf on-device real-time target-speaker-extraction model exists — the models are a genuine ML build, not a library drop-in.**

**P3 (polish) DONE.** Real **release keystore** (`clearcall-release.keystore`, creds in gitignored `mobile/keystore.properties`; falls back to debug when absent) — **release SHA-1 `01:49:A3:D5:30:30:2E:96:62:A5:BC:A3:44:FE:53:27:01:D0:FB:CF`** (register this for the release Google OAuth Android client; back the keystore up — losing it blocks Play updates). Battery-optimization prompt in Settings; in-call speaker/earpiece toggle (`setCommunicationDevice`, needs a device to verify BT interplay). App launcher icon already branded; call busy/missed/declined races already idempotent.

**P4.5 (backend iOS-readiness) DONE, curl-verified.** `devices.platform ENUM('android','ios')` (+ migration); `/devices/register` takes `platform` + platform-neutral `pushToken` (`fcmToken` alias kept). `utils/apns.php` (ES256 `.p8` VoIP sender, HTTP/2, skips gracefully unconfigured) + `utils/push.php` (routes each callee device FCM vs APNs); `callsController` ring/cancel/declined go through `Push::sendToUser`. LiveKit token grants `canUpdateOwnMetadata`; the Android callee advertises `state=answered` as a participant attribute — the cross-platform answer signal a **future iOS caller** will read. **Android callers keep the verified room-join answered detection; flipping to the attribute signal is deferred to land WITH P5** (it exists for iOS's ring-join model and needs a 2-device test against the iOS side). APNs real delivery needs an Apple `.p8` + device.

## Terminal Command Rules

**CRITICAL**: Always combine directory change and command in a single line, absolute Windows paths.

## Skills (`.claude/skills/`, load when launched inside `clear-call/`)

- `clearcall-dev` — run the PHP API + Android app locally (php -S 0.0.0.0:8010, gradle, adb reverse, dev login for two test users).
- `clearcall-deploy-api` — deploy/update the PHP backend on the shivarya.dev cPanel host (infra connection details live only in the private monorepo, not this public repo).
- `clearcall-release` — build the signed release APK/AAB, verify the signature, report the release SHA-1.

## Further plans (`docs/`)

- `docs/VOICE_ISOLATION_TIER_B_PLAN.md` — how to build the target-speaker "isolate a voice" models (the P4 seam is shipped; the two ML models are the remaining work).
- `docs/IOS_APP_PLAN.md` — full build + on-device-deploy plan for the native iOS app (P5), for a developer with a Mac. Mirrors Android but uses Apple Voice Isolation instead of DeepFilterNet.

## Layout

| Sub-app | Path | Stack |
|---------|------|-------|
| Server | `server/` | PHP 8.0+ + MySQL front-controller REST API (clone of ps5-tracker/diet-plan conventions) |
| Mobile | `mobile/` | Native Kotlin + Compose, applicationId `dev.shivarya.clearcall`, namespace `com.clearcall`, minSdk 31. **applicationId ≠ namespace** — `adb shell am start` needs the fully-qualified class: `dev.shivarya.clearcall/com.clearcall.MainActivity`, not the `.MainActivity` shorthand. |

**Production: LIVE at `https://shivarya.dev/clear_call/`** (deployed under `~/public_html/shivarya.dev/clear_call` — the docroot, NOT `~/public_html/`). Verified: `/health` 200, unauthenticated `/contacts` → 401 JSON (routing reaches the app, not the portfolio SPA), dev-login → 410 (correctly disabled), `.env`/`composer.json` → 403, plain `http://` → 301 to `https://`.

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

## Production deployment (shared cPanel host)

**Connection details (SSH host/user) are intentionally not repeated here** — this file is public. See the private monorepo root's `CLAUDE.md` / `scripts/connect_ssh.ps1` (not part of this repo) for how to reach the host; the same credentials as the other shivarya.dev apps (diet-plan, ps5-tracker, etc.) apply.

| | |
|---|---|
| Deploy dir | `~/public_html/shivarya.dev/clear_call` |
| DB | database `clear_call`, user `clearcall` — **no account-prefix needed on this host** (unlike the older prefixed convention documented for some other apps here; verified empirically 2026-07-06 that connecting as plain `clearcall`/`clear_call` works directly). Created via `uapi Mysql create_database/create_user/set_privileges_on_database` over SSH — no need to touch the cPanel web UI. |
| FCM service-account key | kept **outside the webroot entirely** in a private `secrets/` dir under the account home (not just `.htaccess`-denied, for defense in depth), `chmod 700` dir / `600` file. |
| Redeploy | `tar czf` the `server/` dir (`--exclude=./vendor --exclude=./.env`) → `scp` → extract into the deploy dir → `composer install --no-dev --optimize-autoloader` on the host. Never overwrite the host's `.env` from a local copy — it holds the real production DB password and JWT secret, generated only once during initial deploy. |
| Note | The cPanel account's `uapi Mysql list_databases`/`list_users` output shows **short, unprefixed** names for both databases and users — trust that live output over older per-project docs when they conflict. |

Google Sign-In does **not** use a redirect URI at all in this app's flow: the Android Credential Manager gets an ID token directly from Google Play Services and the app POSTs it to `POST /auth/google` for server-side verification — there is no OAuth redirect/callback route anywhere in this codebase. The "Web application" OAuth client in Google Cloud Console exists only to mint a Client ID (used as `serverClientId` on Android and `GOOGLE_CLIENT_ID` on the server); if the console UI insists on a redirect URI to save, any placeholder under `https://shivarya.dev/clear_call/` works since it's never invoked.

## Verified working (2026-07-06, emulator + real LiveKit Cloud + live production API)

Dev login → session persistence → contacts fetch/display → QR code generation → **outgoing call placement with a real LiveKit token, real room connect, and live microphone capture confirmed** → clean hangup (`POST /calls/{id}/cancel`) → state reset → navigation back to Home. Not yet tested: incoming call / two-way audio between two signed-in devices (needs a second device signed in — try Pixel + emulator, or two emulators).

## Key P2+ facts (verified research, don't re-derive)

- LiveKit Android SDK hook for our NS: `CustomAudioProcessingFactory` / `AudioProcessorOptions(capturePostProcessor=…)` — 10ms capture frames post-AEC.
- NS engine: `io.github.kaleyravideo:android-deepfilternet:0.0.8` (Maven Central, DFN3, real-time). Probe the actual frame format (rate/bands/int16-vs-float) with a logging-only processor BEFORE writing the adapter.
- LiveKit Cloud free tier: 5,000 participant-min/month hard cap (≈2,500 1:1 call minutes); self-hostable later.
- iPhone (P5, later): separate native Swift app — LiveKit Swift SDK + CallKit + PushKit (VoIP pushes need direct APNs, NOT FCM). Backend needs `devices.platform` + `utils/apns.php` first (P4.5).
- Tier B ("only my voice"): ONE generic speaker-conditioned model (VoiceFilter-Lite style) trained developer-side; users enroll on-device (d-vector) — users never need a GPU.
