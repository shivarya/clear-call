---
name: clearcall-dev
description: Run ClearCall locally — start the PHP API and build/install the Android app on an emulator or device, with dev login for two test users. Use to develop or test clear-call.
---

Run the ClearCall backend + Android app for local development. 1:1 calling app: PHP+MySQL API, native Kotlin/Compose app, LiveKit for media, FCM for ring signaling.

## One-time setup

1. **Server env**: `cd "c:\Users\Ash\Documents\Projects\apps\clear-call\server" ; copy .env.example .env` — set `DB_*`, a `JWT_SECRET`, and `ALLOW_DEV_LOGIN=true`. LiveKit keys are needed for real media; `GOOGLE_CLIENT_ID`/`FCM_*` can stay empty locally (dev login + `pushSkipped` cover testing).
2. **Vendor deps**: `cd "c:\Users\Ash\Documents\Projects\apps\clear-call\server" ; composer install` (firebase/php-jwt + google/apiclient; `vendor/` is gitignored).
3. **Database** (XAMPP MySQL, root / no password):
   ```powershell
   & "D:\xampp\mysql\bin\mysql.exe" -u root -e "CREATE DATABASE IF NOT EXISTS clear_call CHARACTER SET utf8mb4"
   Get-Content "c:\Users\Ash\Documents\Projects\apps\clear-call\server\database\schema.sql" | & "D:\xampp\mysql\bin\mysql.exe" -u root clear_call
   ```
   If pulling newer server code, also apply any files in `server/database/migrations/` the same way.

## Run

1. **PHP API** — **bind `0.0.0.0`, never `localhost`** (see gotcha):
   ```powershell
   cd "c:\Users\Ash\Documents\Projects\apps\clear-call\server" ; php -S 0.0.0.0:8010
   ```
   Verify: `Invoke-RestMethod http://localhost:8010/health`  (port 8010 — 8000 is taken by ps5-tracker's task on this machine).
2. **Android app** (debug talks to `http://localhost:8010` via `adb reverse`):
   ```powershell
   cd "c:\Users\Ash\Documents\Projects\apps\clear-call\mobile" ; .\gradlew.bat :app:assembleDebug
   adb reverse tcp:8010 tcp:8010          # per device — re-run if requests start failing
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   adb shell am start -n dev.shivarya.clearcall/com.clearcall.MainActivity
   ```
3. **Dev login** — debug builds show "Login as Dev 1 / Dev 2" on the sign-in screen whenever the Google web client ID or `ALLOW_DEV_LOGIN` isn't wired. Call tests need two users; log in as Dev 1 on one device/emulator and Dev 2 on another.

## Gotchas (both cost real debugging time — see clear-call/CLAUDE.md)

- **`php -S` must bind `0.0.0.0`, not `localhost`.** `localhost` resolves ambiguously between IPv4/IPv6 and, combined with `adb reverse`, causes intermittent `"unexpected end of stream"` that looks exactly like an app bug (host `curl` always works, confusing the diagnosis). If `adb reverse` seems to silently stop forwarding **and PHP's request log shows nothing for the failing calls**, check the bind address first.
- **`applicationId` (`dev.shivarya.clearcall`) ≠ Kotlin `namespace` (`com.clearcall`).** `adb shell am start` needs the fully-qualified class `dev.shivarya.clearcall/com.clearcall.MainActivity`, not `.MainActivity`.
- **Biometric lock**: the app gates behind a fingerprint/PIN on launch; it *fails open* if the emulator has no screen lock. To test the lock, set one: `adb shell locksettings set-pin 1234` (clear with `adb shell locksettings clear --old 1234`).
- **Noise suppression (DeepFilterNet3)** runs on the mic uplink; a debug-only in-call overlay shows live stats + an A/B bypass switch. It safely bypasses if anything goes wrong — it can never break a call.

## Notes

- The `calls` table is live state (busy checks, idempotent end, missed-call reconciliation), not just a log — the app has no history screen but the backend still records calls.
- Not verified on the emulator (need two real devices / real audio): DFN3 audio quality, incoming-call/two-way audio, real Google Sign-In. See CLAUDE.md.
