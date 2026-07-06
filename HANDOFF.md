# ClearCall — Work Handoff (2026-07-05)

This is your catch-up doc. It covers **what got built**, **what I verified**, **what YOU need to do manually** (console signups + tests I can't do for you), and **what's next**. The full architecture/plan lives in `~/.claude/plans/hashed-hugging-umbrella.md` and `CLAUDE.md`.

---

## TL;DR

We concluded the Clear Mic Router app can't be pushed further without root — Android forbids any app from cleaning WhatsApp's/the phone dialer's audio. The full fix is a **new calling app where both people install it**, so we own the whole audio pipeline and can insert real deep-learning noise removal (DeepFilterNet3) on each side's mic before it's sent. That's **ClearCall**, in the new `clear-call/` folder.

**Today I built and verified the entire backend (Phase 0).** Every endpoint works locally against your XAMPP MySQL. The next phase is the Android app itself (first real call), which I have not started.

---

## What's built and VERIFIED working

The PHP backend at `clear-call/server/` — a clone of your proven ps5-tracker/diet-plan server patterns. I imported the schema into a local `clear_call` MySQL database, ran the server on `localhost:8010`, and passed the **full curl matrix**:

| Tested | Result |
|--------|--------|
| `GET /health` | ✅ healthy |
| `POST /auth/login {as:1}` and `{as:2}` (dev login, two test users) | ✅ issues JWTs, generates 8-char user codes |
| `POST /devices/register` (FCM token) | ✅ upsert |
| `POST /contacts {userCode}` | ✅ adds contact + auto-creates the reverse row (mutual) |
| `GET /contacts` | ✅ user2 sees user1 without adding back |
| `POST /calls {calleeUserId}` | ✅ mints a real LiveKit JWT, returns room + token (push "skipped" since FCM not configured yet — by design) |
| Busy rejection (call while a call is open) | ✅ 409 |
| `POST /calls/{id}/answer` | ✅ mints callee token |
| `POST /calls/{id}/end` (+ repeat) | ✅ idempotent |
| `POST /calls/{id}/decline` | ✅ |
| `POST /calls/{id}/cancel {reason:timeout}` → status `missed` | ✅ |
| Wrong-party guards (only caller can cancel, etc.) | ✅ 403 |
| No-token request | ✅ 401 |
| `GET /calls` history with direction/peer/status | ✅ |

So: **auth, contacts, call signaling, LiveKit token minting, and call history all work end to end.** The only piece not exercised is the actual FCM push delivery and real LiveKit room audio, because both need cloud accounts (below).

### Files created
```
clear-call/
├── CLAUDE.md                 # project guide
├── HANDOFF.md                # this file
├── .gitignore                # secrets + build artifacts excluded
└── server/
    ├── index.php             # front controller / router
    ├── .htaccess             # cPanel rewrite, HTTPS, secrets denied, Authorization forwarded
    ├── .env                  # LOCAL dev config (dev-login on, placeholder LiveKit keys)
    ├── .env.example          # template for production
    ├── composer.json         # firebase/php-jwt + google/apiclient
    ├── vendor/               # copied from diet-plan (no composer run needed)
    ├── config/config.php     # env + constants
    ├── config/database.php   # PDO singleton (copied from ps5-tracker)
    ├── utils/response.php     # Response class + getBearerToken (copied from diet-plan)
    ├── utils/jwt.php          # JWTHandler (copied from diet-plan)
    ├── utils/livekit.php      # NEW — LiveKit access-token mint
    ├── utils/fcm.php          # NEW — FCM v1 sender (skips gracefully if unconfigured)
    ├── controllers/authController.php     # Google Sign-In + dev login + user codes
    ├── controllers/devicesController.php  # FCM token registration
    ├── controllers/contactsController.php # add/list/remove by user code
    ├── controllers/callsController.php    # the core: create/answer/decline/cancel/end/history
    └── database/schema.sql   # users, devices, contacts, calls
```

The local MySQL `clear_call` DB is already created and has test data in it (3 calls, 2 users) from the verification run. You can wipe it anytime by re-importing `schema.sql` after a `DROP DATABASE`.

---

## WHAT YOU NEED TO DO MANUALLY

These are the cloud-account and device steps I can't do for you. **None are needed until we build the app (P1); do them whenever you're ready to continue.** Roughly 30–40 min total.

### 1. LiveKit Cloud account (the call transport) — free, no card
1. Go to https://cloud.livekit.io → sign up (GitHub/Google login is fine).
2. Create a project (name it `clearcall`).
3. Project Settings → **Keys** → create an API key. Copy three things:
   - the WebSocket URL, looks like `wss://clearcall-xxxx.livekit.cloud`
   - the **API Key** (starts with `API...`)
   - the **API Secret**
4. Put them in `server/.env` as `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` (replacing the placeholders).
   - Free "Build" tier = 5,000 participant-minutes/month (~2,500 minutes of 1:1 calls). Plenty for personal use. Upgradeable or self-hostable later.
5. **Quick sanity test after adding keys**: re-run a `POST /calls`, copy the returned `token` and your `wss://` URL, go to https://meet.livekit.io → "Custom" tab → paste both → if it joins a room, token minting is correct.

### 2. Firebase project (the ringing/push) — free
1. https://console.firebase.google.com → Add project → name `clear-call`.
2. Add an **Android app**: package name `dev.shivarya.clearcall`. Download the `google-services.json` it gives you — save it for P1 (goes into `mobile/app/`). *(The app doesn't exist yet, so just keep the file.)*
3. Project Settings → **Service accounts** → **Generate new private key** → downloads a JSON file. This is the FCM v1 credential the PHP backend uses to send ring pushes.
   - **Treat this like a password.** On the server it goes somewhere outside the web root, chmod 600. For local testing you can point `FCM_SERVICE_ACCOUNT_PATH` in `.env` at wherever you save it.
4. In `server/.env` set `FCM_PROJECT_ID` (the Firebase project ID, visible in project settings) and `FCM_SERVICE_ACCOUNT_PATH` (full path to that JSON).
   - Until you do this, calls still fully work in testing — the backend just reports `pushSkipped:true` instead of ringing a phone.

### 3. Google Sign-In OAuth (only needed for real, non-dev login)
1. https://console.cloud.google.com → the same project Firebase created → APIs & Services → Credentials.
2. Create an **OAuth client ID — Web application**. Copy its client ID → `server/.env` `GOOGLE_CLIENT_ID`.
3. Create an **OAuth client ID — Android**: package `dev.shivarya.clearcall` + your debug keystore SHA-1.
   - Get the SHA-1 with: `keytool -list -v -keystore "$HOME/.android/debug.keystore" -alias androiddebugkey -storepass android -keypass android` (SHA-1 `7A:03:EE:4F:...` — same debug keystore your other apps use).
   - Your `diet-plan` project already did this exact dance; the `diet-release` skill documents the SHA-1 gotchas if you hit them.
4. For **local testing you can skip all of §3** — `ALLOW_DEV_LOGIN=true` lets you log in as two test users with no Google at all.

### 4. When we build the app (P1), tests only YOU can run
I can build and install the APK, but the actual call experience needs two devices and your ears:
- Two-device call: your **Pixel 10** + the **Android emulator** (or a second phone). Both call directions, answer on lock screen, two-way audio.
- **The whole point — noise A/B**: during a call, play traffic/music noise near the mic, then toggle the in-app "suppression bypass" switch. The background noise should audibly drop while your voice stays intact. On the emulator, its mic is piped from your PC's mic (Extended controls → Microphone), so you can feed it a noise recording.
- Ring under Doze (phone screen off a while), and with Bluetooth buds.

---

## What's NEXT (not started)

**P1 — the Android app + first real call** (est. a few days of build time):
- Kotlin/Compose app scaffolded from your `clear-mic-router` conventions.
- Google Sign-In, contacts screen with your shareable QR/code, outgoing call, incoming full-screen ringing call (Telecom self-managed + CallStyle notification), join LiveKit room, plain two-way audio.
- Exit criterion: Pixel 10 ↔ emulator can call each other and talk.

**P2 — the noise removal** (the reason for all this):
- Plug DeepFilterNet3 into LiveKit's mic-processing hook; debug overlay with a live bypass A/B toggle and CPU/latency readout.

**P3 — polish**: in-call device picker, missed/busy handling, call history UI, release keystore.

**P4 (later) — "only my voice"**: personalized suppression where you enroll ~30–60s of your voice on-device (no GPU needed by users) so even nearby human voices are removed. Designed-in but not built.

---

## Where things are
- Plan: `~/.claude/plans/hashed-hugging-umbrella.md`
- Project guide: `clear-call/CLAUDE.md`
- Backend: `clear-call/server/` (run `php -S localhost:8010` from that folder; XAMPP MySQL must be running — it's a Windows service, already auto-start)
- The old routing app (still useful for WhatsApp on its own): `clear-mic-router/` — I also fixed a real bug in its mic-test tool today (it was recording through the earbud mic instead of the phone mic; now fixed + clearer UI). That build is installed on your Pixel.

---

*Backend Phase 0 complete and verified 2026-07-05. Resume by doing the console signups above, then continue with P1 (the app).*
