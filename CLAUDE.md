# CLAUDE.md — ClearCall

Guidance for Claude Code when working inside `clear-call/`. A 1:1 internet calling app where **both parties run the app** and each side's mic is cleaned on-device with deep-learning noise suppression (DeepFilterNet3) — clear calls in any noisy environment. Exists because no third-party app can process WhatsApp's/the dialer's audio without root (verified dead end; `clear-mic-router/` is the routing-only companion for those).

**Full plan**: `~/.claude/plans/hashed-hugging-umbrella.md`. **Status**: P0 (backend) built + curl-verified locally on 2026-07-05. P1 (mobile app, first call) not started.

## Terminal Command Rules

**CRITICAL**: Always combine directory change and command in a single line, absolute Windows paths.

## Layout

| Sub-app | Path | Stack |
|---------|------|-------|
| Server | `server/` | PHP 8.0+ + MySQL front-controller REST API (clone of ps5-tracker/diet-plan conventions) |
| Mobile | `mobile/` | (P1, not started) native Kotlin + Compose, applicationId `dev.shivarya.clearcall`, minSdk 31 |

Production target: `https://shivarya.dev/clear_call/` (deploy under `~/public_html/shivarya.dev/clear_call` — the docroot, NOT `~/public_html/`). Not deployed yet.

## Commands

### Server (`server/`)
```powershell
# Local MySQL is the XAMPP service (D:\xampp\mysql\bin\mysql.exe), root, no password
& "D:\xampp\mysql\bin\mysql.exe" -u root -e "CREATE DATABASE IF NOT EXISTS clear_call CHARACTER SET utf8mb4"
Get-Content database\schema.sql | & "D:\xampp\mysql\bin\mysql.exe" -u root clear_call
cd "c:\Users\Ash\Documents\Projects\apps\clear-call\server" ; php -S localhost:8010   # port 8010 — 8000 is taken by ps5-tracker's task
```
`.env` already exists locally with `ALLOW_DEV_LOGIN=true` and placeholder LiveKit keys. `vendor/` was copied from `diet-plan/server/vendor` (firebase/php-jwt + google/apiclient) — no composer run needed locally.

## Architecture

- **Signaling model** (no websockets on shared PHP hosting): ring/cancel/declined travel as FCM v1 high-priority data pushes; **"answered" is detected by the remote participant joining the LiveKit room** (caller sits in-room during ringback).
- **`utils/livekit.php`** — LiveKit access tokens are plain HS256 JWTs (iss=API key, sub=`u{userId}`, `video` grant); minted with firebase/php-jwt. Callee's token is only minted at `/answer`, never pushed through FCM.
- **`utils/fcm.php`** — FCM v1 via google/apiclient service-account assertion; data values stringified; `UNREGISTERED` responses deactivate the device row; **gracefully skips when FCM env is unset** (`pushSkipped:true`) so call flows stay curl-testable.
- **`controllers/callsController.php`** — the core: busy checks both parties (fresh ring or answered-unended), records `busy`/`failed(no_device|push_failed)` attempts for history, `reapStaleRinging()` lazily converts abandoned rings to `missed`, all transitions guarded by role (caller/callee) and idempotent.
- **Auth**: Google Sign-In (`/auth/google`, keyed by immutable `google_sub`) → own 30-day JWT (`utils/jwt.php`, copied verbatim from diet-plan). Dev backdoor `POST /auth/login {as:1|2}` seeds/logs in two test users (call tests need two). Contact discovery is by 8-char `user_code` (unambiguous alphabet) — no phone/contact upload (Play Store privacy).

## Endpoints

`GET /health` · `POST /auth/google|login` · `GET /auth/me` · `DELETE /auth/account` · `POST /devices/register` · `POST|GET /contacts`, `DELETE /contacts/{id}` · `POST /calls`, `POST /calls/{id}/answer|decline|cancel|end`, `GET /calls?limit=`

## Environment (`server/.env`)
```
DB_* · JWT_SECRET · JWT_EXPIRES_IN · GOOGLE_CLIENT_ID · GOOGLE_ALLOWED_AUDIENCES
ALLOW_DEV_LOGIN (never true in prod) · LIVEKIT_URL/API_KEY/API_SECRET
FCM_PROJECT_ID · FCM_SERVICE_ACCOUNT_PATH (chmod 600, .htaccess-denied) · RING_TIMEOUT_SECONDS=45
```

## Key P1+ facts (verified research, don't re-derive)

- LiveKit Android SDK hook for our NS: `CustomAudioProcessingFactory` / `AudioProcessorOptions(capturePostProcessor=…)` — 10ms capture frames post-AEC.
- NS engine: `io.github.kaleyravideo:android-deepfilternet:0.0.8` (Maven Central, DFN3, real-time). Probe the actual frame format (rate/bands/int16-vs-float) with a logging-only processor BEFORE writing the adapter.
- Incoming calls: FCM → Telecom **self-managed ConnectionService** + `Notification.CallStyle` full-screen intent.
- LiveKit Cloud free tier: 5,000 participant-min/month hard cap (≈2,500 1:1 call minutes); self-hostable later.
- Tier B ("only my voice"): ONE generic speaker-conditioned model (VoiceFilter-Lite style) trained developer-side; users enroll on-device (d-vector) — users never need a GPU.
