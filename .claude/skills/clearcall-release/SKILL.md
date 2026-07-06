---
name: clearcall-release
description: Build the signed release APK/AAB for the ClearCall Android app, verify the signature, and report the release signing SHA-1 (needed for the release Google OAuth Android client). Use when shipping a ClearCall build.
---

Build and sign the ClearCall Android release. Native Kotlin/Compose, `applicationId dev.shivarya.clearcall`, minSdk 31.

## Signing setup (one-time, already done on this machine)

Release signing reads a **gitignored** `mobile/keystore.properties` pointing at `mobile/app/clearcall-release.keystore`. Neither is in git (`*.keystore` + `mobile/keystore.properties` are ignored). If `keystore.properties` is absent, the release build **falls back to the debug keystore** so it still assembles — but that's not a distributable identity.

`keystore.properties` shape (values are the real keystore password — keep local only):
```
storeFile=clearcall-release.keystore
storePassword=<password>
keyAlias=clearcall
keyPassword=<password>
```

⚠️ **Back up `clearcall-release.keystore` + its password somewhere safe.** It is the app's permanent signing identity — lose it and you can never ship an update to the same app on the Play Store.

## Build

```powershell
# APK (sideload/testing)
cd "c:\Users\Ash\Documents\Projects\apps\clear-call\mobile" ; .\gradlew.bat :app:assembleRelease
# -> app\build\outputs\apk\release\app-release.apk

# AAB (Play Store upload)
cd "c:\Users\Ash\Documents\Projects\apps\clear-call\mobile" ; .\gradlew.bat :app:bundleRelease
# -> app\build\outputs\bundle\release\app-release.aab
```
Native Kotlin, so the RN/CMake 260-char long-path junction trick is NOT needed here. Release builds talk to the production API (`https://shivarya.dev/clear_call`) over HTTPS and require real Google Sign-In (no dev login).

## Report the signing SHA-1 (needed for the release Google OAuth Android client)

```powershell
$keytool = "C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot\bin\keytool.exe"
& $keytool -list -v -keystore "c:\Users\Ash\Documents\Projects\apps\clear-call\mobile\app\clearcall-release.keystore" -alias clearcall
```
**Current release SHA-1** (regenerate the keystore only if lost; this is the identity to register):
```
01:49:A3:D5:30:30:2E:96:62:A5:BC:A3:44:FE:53:27:01:D0:FB:CF
```
Register this SHA-1 (+ package `dev.shivarya.clearcall`) as a **new Android OAuth client** in Google Cloud Console for the release build to sign in — the debug-keystore SHA-1 (`7A:03:EE:...`) only covers debug builds.

## Verify the APK is signed with the release key

```powershell
# apksigner (Android SDK build-tools). If missing, the gradle build's validateSigningRelease
# task passing already proves the release keystore was used.
$bt = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools","$env:ANDROID_HOME\build-tools" -Directory -EA SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
& "$($bt.FullName)\apksigner.bat" verify --print-certs "c:\Users\Ash\Documents\Projects\apps\clear-call\mobile\app\build\outputs\apk\release\app-release.apk"
```

## Install a release build on a connected device

```powershell
adb install -r "c:\Users\Ash\Documents\Projects\apps\clear-call\mobile\app\build\outputs\apk\release\app-release.apk"
```

## Before shipping to Play

- Bump `versionCode` (and `versionName`) in `mobile/app/build.gradle.kts`.
- Play Store store-listing assets (512×512 icon, feature graphic, screenshots) — generate via the root `play-store-assets` skill when actually submitting; the launcher adaptive icon is already branded.
- Confirm the release Google OAuth Android client (SHA-1 above) exists, and the host `.env` has the real `GOOGLE_CLIENT_ID` + `FCM_*`.
