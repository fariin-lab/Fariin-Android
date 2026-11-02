# Fariin Android: progress (read this first when resuming)

Owner's order 2026-11-02 night: pure native Android (Kotlin + Jetpack Compose), same app as iOS
(C:\Users\abdik\Documents\Kulan-iOS), same backend (Firebase project kulan-2ef85, functions in
C:\Users\abdik\Documents\Kulan). Work all night, test everything, no prompts to him.

## Rules
- No brand names of other messengers in code or commits ("the reference app").
- Never type passwords or sign in on the emulator (he signs in tomorrow with review1/review2, never kream:
  one phone per account would sign out his iPhone).
- Commit small, with the Co-Authored-By + Claude-Session lines. Local git in this folder.
- Encryption MUST match iOS byte for byte (Kulan-iOS/Kulan/Services/Crypto.swift), or iPhone and
  Android users cannot read each other.

## Setup (done)
- SDK at %LOCALAPPDATA%\Android\Sdk, cmdline-tools/latest, image android-36 google_apis x86_64 (the playstore image blocks adb with an "Allow USB debugging" dialog).
- Emulator AVD: fariin_pixel (Pixel 8). WHPX acceleration works.
- JAVA_HOME = C:\Program Files\Android\Android Studio\jbr
- Firebase Android app: com.fariin.app, id 1:522284693527:android:53f4bb9ef900197a458452,
  app/google-services.json in place.
- Build: `./gradlew assembleDebug` (Gradle 8.13 wrapper, AGP 8.11.1, Kotlin 2.2.0, Compose BOM 2025.06.01).
- First build (hello screen) OK 2026-11-02 05:00.

## Plan (in order)
1. [x] Hello app runs on the emulator (screenshot 05:11)
2. [x] Crypto port (lazysodium) with unit tests from the Swift code's formats
3. [x] Data models (Conversation done; Message, UserProfile still TODO) from Models.swift + Firestore paths
4. [x] Sign-in screen (email + password, same flows as iOS FirstRunViews) and session
5. [x] Chat list (live Firestore), look matching iOS (previews wait for crypto; not yet seen with a real account)
6. [ ] 1:1 thread: read + send encrypted text
7. [ ] Groups, photos, voice, push (FCM), calls, stories, channels

## Log
- 2026-11-02 05:00 project created, first debug APK built.
- 2026-11-02 05:11 app installed and opened on fariin_pixel; adb works (emulator-5554).
- 2026-11-02 05:25 crypto port committed (3ed410e): enc1/encg1/EncMeta/rk/safety number in app/.../crypto, 29 unit tests green incl. PyNaCl-built vectors; formats in crypto/WIRE_FORMAT.md. Not yet run on the emulator (SodiumAndroid load unchecked).
- 2026-11-02 05:15 data layer: Conversation (iOS field names, isCleared group rule, pinned-first sort, request filter), ConversationsRepository (users array-contains me, updatedAt desc, 300, read only), AuthRepository (email sign-in, reset via requestPasswordReset me-central1). 5 unit tests pass.
- 2026-11-02 05:20 sign-in pages: Welcome > Log in to Fariin > Log in with email, iOS wording; Apple/Google/passkey/sign-up are TODO. Theme from Theme.swift, follows system dark mode. Coil fetcher for fariin-photo:// via Storage getBytes.
- 2026-11-02 05:25 main shell: tabs Stories, Calls, Chats (default, unread badge), Settings (Log Out). Chat rows: grey silhouette avatar, name, preview ("Message" until crypto is wired), time, ticks, unread badge, Pinned/Chats sections. Checked on the emulator in light and dark with debug demo data (`am start -n com.fariin.app/.MainActivity --ez demo true`). Nobody signed in.
