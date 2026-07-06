# ClearCall — Work Handoff (updated 2026-07-06)

Catch-up doc: **what's built**, **what's verified**, **what's left for YOU**, **what's next**. Full architecture in `CLAUDE.md`; full roadmap in `~/.claude/plans/hashed-hugging-umbrella.md` (local, not in this repo).

---

## TL;DR

Clear Mic Router hit its ceiling — Android forbids any app from cleaning WhatsApp's/the dialer's audio without root. The real fix is **ClearCall**: a new calling app where both people install it, so we own the whole audio pipeline and can insert real deep-learning noise removal (DeepFilterNet3) before the call leaves the phone.

**Status: the backend is live in production, and the Android app placed a real verified call over live LiveKit Cloud infrastructure.** DeepFilterNet noise removal itself (the whole point) isn't wired in yet — that's next.

---

## What's built and VERIFIED working

**Backend** — deployed and live at `https://shivarya.dev/clear_call/`. Verified: health check, auth-guarded routing, dev-login correctly disabled in production, secrets non-web-accessible, HTTPS enforced. Full endpoint set (auth, contacts, call signaling, LiveKit token minting, call history) was curl-tested locally first, then the exact same code deployed.

**Android app** (`mobile/`) — built from scratch and verified end-to-end on the emulator:
- Dev login → session persists → contacts load → your shareable QR code renders
- Placed a real outgoing call: minted a genuine LiveKit token, connected to your live LiveKit Cloud project, **microphone confirmed actively capturing**
- Clean hangup → call canceled server-side → UI resets

Not yet tested: incoming call / two-way audio between two signed-in devices (needs a second device — your Pixel is locked, so that side needs you).

---

## WHAT YOU NEED TO DO MANUALLY

### Done already ✅
- LiveKit Cloud project — real credentials are live in production `.env`.
- Firebase project (`clear-call-8fc12`) — `google-services.json` is in the app; the FCM service-account key is deployed to the server (kept outside the webroot, not just `.htaccess`-denied).

### Still needed: Google Sign-In OAuth
1. https://console.cloud.google.com → the Firebase-created project → APIs & Services → Credentials.
2. **OAuth consent screen**: External, add `email`/`profile` scopes, leave in Testing, add your Gmail as a test user.
3. **Create Credentials → OAuth 2.0 Client ID → Web application.** No redirect URI is actually needed for our flow (Android gets an ID token directly from Google Play Services and POSTs it to our API — there's no callback route in this codebase at all). If the console form insists on one anyway, any placeholder under `https://shivarya.dev/clear_call/` is fine since it's never hit. Copy the **Client ID**.
4. **Create Credentials → OAuth 2.0 Client ID → Android.** Package `dev.shivarya.clearcall` + your debug keystore SHA-1 (`keytool -list -v -keystore "$HOME/.android/debug.keystore" -alias androiddebugkey -storepass android -keypass android`).
5. Put the Web client ID into production `.env` as `GOOGLE_CLIENT_ID`, and into the app's `build.gradle.kts` as `GOOGLE_WEB_CLIENT_ID` (currently blank, which is why the app shows "Login as Dev 1/2" instead of "Sign in with Google").

Until this is done, real people can't sign in — only the dev-login backdoor works, and that's now correctly disabled in production (it only works against your local dev server).

### Tests only YOU can run
- **Incoming call + two-way audio**: sign into Dev User 1 on your Pixel (Dev User 2 is already signed in on the emulator) and call between them.
- **The whole point — noise A/B** (once P2 lands): background noise near the mic, toggle the suppression bypass mid-call, confirm the noise floor drops while your voice stays intact.
- Ring behavior under Doze, with Bluetooth buds connected.

---

## What's NEXT

**P2 — the noise removal** (the reason for all this): plug DeepFilterNet3 into LiveKit's mic-processing hook; debug overlay with a live bypass A/B toggle and CPU/latency readout.

**P3 — polish**: in-call device picker, missed/busy handling, call history UI, release keystore.

**P4 (later) — "only my voice"**: personalized suppression where you enroll ~30–60s of your voice on-device (no GPU needed) so even nearby human voices are removed. Designed-in but not built.

**P5 (later) — iPhone**: separate native Swift app (LiveKit Swift + CallKit + PushKit), so a call to/from an iPhone works too — uses Apple's own Voice Isolation instead of DeepFilterNet on that side.

---

*Last updated 2026-07-06 after the backend went live and the Android app's first real call was verified.*
