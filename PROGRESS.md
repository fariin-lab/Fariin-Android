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
2. [ ] Crypto port (lazysodium) with unit tests from the Swift code's formats
3. [ ] Data models (Conversation, Message, UserProfile) from Models.swift + Firestore paths
4. [ ] Sign-in screen (email + password, same flows as iOS FirstRunViews) and session
5. [ ] Chat list (live Firestore), look matching iOS
6. [ ] 1:1 thread: read + send encrypted text
7. [ ] Groups, photos, voice, push (FCM), calls, stories, channels

## Log
- 2026-11-02 05:00 project created, first debug APK built.
- 2026-11-02 05:11 app installed and opened on fariin_pixel; adb works (emulator-5554).
