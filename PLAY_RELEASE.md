# Fariin Android: first Google Play release checklist

Prepared 2026-11-03. Steps marked **(you)** are the owner's buttons; the rest is done or I do it.

## Already done
- Release build: shrunk and optimised (R8), start-up profile included, signed with the upload key
  (`keystore.properties`, never committed).
- App Check sends Play Integrity tokens (nothing enforced yet, same as iOS).
- Crash reports (Crashlytics), Google sign-in with the debug key.

## 1. Firebase (kulanchat account, project kulan-2ef85) (you, or tell me to do it in Chrome)
1. Project settings > Your apps > Android `com.fariin.app` > **Add fingerprint**, add both:
   - Upload key SHA-1: `40:40:FC:6B:68:46:3F:1A:2A:10:D1:4F:C0:A2:FB:72:CD:68:EF:16`
   - Upload key SHA-256: `8B:03:57:3F:54:8C:89:54:1C:36:5E:30:35:E3:A2:19:E1:46:B3:58:1A:42:79:D1:E5:3F:8A:54:ED:70:58:33`
2. After step 2.3 below, add the **App signing key** SHA-1 and SHA-256 that Play Console shows
   (Google re-signs the app; without these, Google sign-in fails in the Play version).
3. App Check > Apps > Android > register **Play Integrity** (and enable the App Check API when asked).
   Do NOT press "Enforce" yet: enforcement is a separate decision for iOS and Android together.

## 2. Google Play Console (you)
1. Create app: name **Fariin**, default language, App, Free.
2. App content: privacy policy URL (fariin.com/privacy), Data safety form (I will prepare the
   answers from the code: what is collected, end-to-end encryption, account deletion link),
   content rating questionnaire, target audience, news app = no, ads = no.
3. Testing > **Internal testing** > create release > upload the `.aab` I build
   (`./gradlew bundleRelease`) > add testers (your Gmail addresses) > Save > Review > Roll out.
4. Copy the App signing key fingerprints into Firebase (step 1.2).

## 3. Before any wider release (needs the owner's word)
- Server: data-only pushes to Android tokens, so calls ring and message notifications show names
  and text with the app closed (waits for the Apple review to finish).
- Two-phone tests: calls iPhone<->Android (voice, video, screen share), group calls, reply swipe.
- versionCode/versionName (now 1 / 1.0) go up with every upload.
