# Android release checklist (draft)

Drafted 2 November 2026. Steps marked OWNER are ones only the owner can do (accounts, money, keys,
Console buttons). Nothing here has been done on Google Play or in the Firebase console.

## 1. Keys and fingerprints

Three signing keys matter. Each one's SHA-1 and SHA-256 must be added where the table says.

| Key | Where it lives | SHA-1 | SHA-256 |
|---|---|---|---|
| Debug key (this PC) | %USERPROFILE%\.android\debug.keystore | 88:0D:27:E4:4F:FA:DF:0A:F1:A2:B3:6D:D4:C8:FB:C8:44:C5:98:27 | E9:C1:09:05:DC:47:E9:91:C8:B7:8E:C5:18:93:1F:A2:E3:37:79:28:19:58:83:74:F6:FF:F7:5E:4B:D0:B3:23 |
| TEST upload key (emulator release tests only, never upload with it) | %USERPROFILE%\.fariin\upload-test.jks, passwords in keystore.properties (git-ignored) | 40:40:FC:6B:68:46:3F:1A:2A:10:D1:4F:C0:A2:FB:72:CD:68:EF:16 | 8B:03:57:3F:54:8C:89:54:1C:36:5E:30:35:E3:A2:19:E1:46:B3:58:1A:42:79:D1:E5:3F:8A:54:ED:70:58:33 |
| Real upload key | OWNER makes it (below) | after it exists | after it exists |
| Play app signing key | Google holds it | Console > Test and release > App integrity | same page |

What users' phones see is the **Play app signing key**, not the upload key. Sign-in with Google,
passkeys and app links all check that one.

### OWNER: make the real upload key (once, keep two offline copies)
```
"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore %USERPROFILE%\.fariin\upload.jks -storetype PKCS12 -alias upload -keyalg RSA -keysize 4096 -validity 10000
```
Then change the four lines of `keystore.properties` (project root, never committed) to point at
it. `./gradlew bundleRelease` then signs with it. Losing the upload key is recoverable (Play support
can reset it); losing the passwords is the same as losing it.

## 2. Firebase console (project kulan-2ef85), OWNER
- Project settings > Your apps > Android com.fariin.app > Add fingerprint: the SHA-1 and SHA-256 of
  the Play app signing key AND the upload key (and the debug key for local testing). Without them
  Sign in with Google returns no credential.
- Download the new google-services.json after adding them and replace app/google-services.json
  (it carries the OAuth client ids made for those fingerprints).
- Authentication > Sign-in method > Apple: a Services ID and key for the web flow Android uses.
- Crashlytics: the first release build that should report crashes is built with
  `./gradlew bundleRelease -PuploadMapping=true` so the R8 mapping file goes up and stack traces are
  readable. Without the flag nothing is uploaded (the default, so test builds never upload).

## 3. Website (fariin.com), needs his word to deploy
- `https://fariin.com/.well-known/assetlinks.json` (and www), JSON, no redirect, with
  `delegate_permission/common.handle_all_urls` and `delegate_permission/common.get_login_creds`
  for package com.fariin.app and the SHA-256 of the Play app signing key (plus the debug key while
  testing). The draft file is in PROGRESS.md, "Server/website changes needed for sign-in".
  Passkeys on Android do not work until this file is live.

## 4. Server (functions in Documents\Kulan\functions), needs a deploy
- passkeys.js `ORIGINS`: add `android:apk-key-hash:<base64url SHA-256 of the Play app signing key>`
  (the debug one is already written in PROGRESS.md).
- Push: data-only messages for tokens in `androidFcmTokens` so closed phones get readable,
  decrypted notifications (PROGRESS.md, "Server changes needed").
- Calls: a data-only high-priority `kind: 'call'` push on every ring and `kind: 'callEnd'` on
  cancel (PROGRESS.md, "Server changes needed for calls"). Without it a closed Android phone does
  not ring.

## 5. App blockers before the first Play upload
- Settings > Delete Account says "Coming soon": Play requires a working in-app deletion path.
- versionCode: 1 today. Every upload needs a higher number; never reuse one.
- Full-screen intent: on Android 14+ check `NotificationManager.canUseFullScreenIntent()` and, when
  false, show a normal heads-up notification (and a Settings link) instead of assuming the
  lock-screen ring works.
- Gallery permissions (READ_MEDIA_IMAGES / READ_MEDIA_VIDEO, being added for the attach grid):
  Play's photo and video policy may refuse them for a messenger. See PERMISSIONS.md; decide before
  the first upload.
- Keep rules: any new library that uses reflection or JNI needs a line in app/proguard-rules.pro.
  Run `connectedReleaseAndroidTest -PtestRelease` after adding one.
- Run the release smoke test again on the final build (section 7).

## 6. Play Console, OWNER only (a Google Play developer account costs a one-time USD 25)
1. Create the developer account (organization account if Fariin becomes a company; a personal
   account must run a closed test with at least 12 testers for 14 days before production).
2. Create app: name Fariin, default language English (US), App, Free.
3. App content: privacy policy, ads (No), app access (Android-only review account), content rating
   (CONTENT_RATING_AND_AUDIENCE.md), target audience 16+, data safety (DATA_SAFETY.md), advertising
   ID (No), government (No), financial (None), health (No), full-screen intent and foreground
   service declarations (PERMISSIONS.md).
4. Store listing: STORE_LISTING.md, 512 icon, 1024 x 500 feature graphic, phone screenshots.
5. Test and release > Internal testing > create release > upload `app-release.aab`
   (app/build/outputs/bundle/release/). Accept Play App Signing (Google makes the app signing key).
6. Copy the app signing key's SHA-1 / SHA-256 from App integrity into Firebase and assetlinks.json
   (sections 2 and 3), and its base64url SHA-256 into passkeys.js (section 4).
7. Closed test, then production when ready.

## 7. Release builds and smoke test (how it was done on 2 November 2026)

| Command | Gives |
|---|---|
| `./gradlew bundleRelease` | app/build/outputs/bundle/release/app-release.aab (the Play upload, 35.2 MB) |
| `./gradlew assembleRelease` | per-CPU APKs: app-arm64-v8a-release.apk 25.3 MB, app-armeabi-v7a-release.apk 20.2 MB, app-x86_64-release.apk 29.1 MB |
| `./gradlew assembleRelease bundleRelease` | one universal app-release.apk (61.3 MB) + the AAB (AGP cannot shrink resources for split APKs and an AAB in one run) |
| `./gradlew connectedReleaseAndroidTest -PtestRelease` (set ANDROID_SERIAL first) | the on-device tests (libsodium through JNA, WebRTC loopback, photo prep) against the R8-shrunk release build |

For comparison the debug APK (every CPU, no shrinking) is 168 MB.

```
adb -s emulator-5558 uninstall com.fariin.app   (a debug build has a different signature)
adb -s emulator-5558 install -r app/build/outputs/apk/release/app-x86_64-release.apk
adb -s emulator-5558 shell am start -n com.fariin.app/.MainActivity
adb -s emulator-5558 logcat -d | findstr /i "FATAL ClassNotFound NoSuchMethod UnsatisfiedLink jna"
```
Third AVD `fariin_rel` (android-36 google_apis x86_64, pixel_8), port 5558. Release builds use the
real backend, so never sign in on them; walk the signed-out pages only.
