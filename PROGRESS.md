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
3. [x] Data models (Conversation, Message done; UserProfile still TODO) from Models.swift + Firestore paths
4. [x] Sign-in screen (email + password, same flows as iOS FirstRunViews) and session
5. [x] Chat list (live Firestore), look matching iOS (previews wait for crypto; not yet seen with a real account)
6. [x] 1:1 thread: read + send encrypted text (and group text, sealed or plain); not yet tried with a real account
7. [ ] Groups, photos, voice, push (FCM), calls, stories, channels

## Log
- 2026-11-02 05:00 project created, first debug APK built.
- 2026-11-02 05:11 app installed and opened on fariin_pixel; adb works (emulator-5554).
- 2026-11-02 05:25 crypto port committed (3ed410e): enc1/encg1/EncMeta/rk/safety number in app/.../crypto, 29 unit tests green incl. PyNaCl-built vectors; formats in crypto/WIRE_FORMAT.md. Not yet run on the emulator (SodiumAndroid load unchecked).
- 2026-11-02 05:15 data layer: Conversation (iOS field names, isCleared group rule, pinned-first sort, request filter), ConversationsRepository (users array-contains me, updatedAt desc, 300, read only), AuthRepository (email sign-in, reset via requestPasswordReset me-central1). 5 unit tests pass.
- 2026-11-02 05:20 sign-in pages: Welcome > Log in to Fariin > Log in with email, iOS wording; Apple/Google/passkey/sign-up are TODO. Theme from Theme.swift, follows system dark mode. Coil fetcher for fariin-photo:// via Storage getBytes.
- 2026-11-02 05:25 main shell: tabs Stories, Calls, Chats (default, unread badge), Settings (Log Out). Chat rows: grey silhouette avatar, name, preview ("Message" until crypto is wired), time, ticks, unread badge, Pinned/Chats sections. Checked on the emulator in light and dark with debug demo data (`am start -n com.fariin.app/.MainActivity --ez demo true`). Nobody signed in.
- 2026-11-02 05:40 Settings, Calls, Stories tabs (read only). Settings: header (photo 120, name, @username, Edit profile / Share profile), then the iOS rows in order: Account, Devices | Notifications, Appearance, Chats, Stories, Privacy & Security, Storage and Data | Invite Friends, Safety, Help & About. Real: Account page (Sign-in, Security, Your Account Data, Log out with the iOS confirm), Appearance > Night Mode (System/Light/Dark, saved on device, theme and bar icons follow it), Help & About (fariin.com/help, /privacy, /terms as on iOS), Safety (fariin.com/safety). Others open "Coming soon on Android". Profile read live from users/{uid}.
- 2026-11-02 05:40 Calls: per-chat `messages where type == "call"` as iOS CallsRepository (re-read only when the chat's updatedAt moves), All/Missed pill, Search calls, rows folded "name (2)", red when they called and I missed, arrow + video icon, duration, time label as iOS, empty states "No Calls Yet" / "No Missed Calls". TODO: groupCalls, group per-member outcome, calling, (i) page, Edit.
- 2026-11-02 05:40 Stories: the 4 iOS listeners (recipientUids token sha256("fariin-audience-v1:"+uid), legacy bare uid, authorUid == me, users/{me}/storyContexts watermarks); My Story / Add Story row, Recent Stories, Viewed Stories (folds), green to blue ring unseen, grey seen. Opening/posting a story is TODO. Demo data in ui/debug/DemoSettings.kt. 7 unit tests (CallsStoriesTest). Screenshots s_*.png checked light and dark.
- 2026-11-02 05:32 SodiumAndroid proven on the emulator: androidTest SodiumOnDeviceTest (RFC 7748 key, enc1 and encg1 round trips, Keystore store survives a new instance, two FariinCrypto accounts talk). `./gradlew connectedDebugAndroidTest`, 5/5 green on fariin_pixel.
- 2026-11-02 05:45 Crypto wired: data/CryptoHub (one FariinCrypto). After sign-in: ensureReady then publishPublicKey (iOS RootView order; a key is made only when none is stored; an unreadable store is never "no key"). Publish skips an account with no users/{uid} doc (never makes a key-only profile). Chat list previews open for real (enc1, encg1, plain groups, conversation rk), partner keys fetched once then the list redraws. Crypto prefs excluded from backup and device transfer (res/xml/data_extraction_rules.xml, backup_rules.xml).
- 2026-11-02 05:45 Chat page: data/Message.kt, ThreadLogic.kt (ticks, receipts, runs, day labels, iOS order keys), ThreadRepository.kt (newest 60 live, older pages, conv receipts, send, mark read), ui/thread/. Sends = iOS writes: messages/{clientId} {text, authorId, createdAt, clientTs, clientId}; conv {lastMessage, lastSender, updatedAt, unreadCount.<other> +1, accepted when answering a request, lastPlainTag in plain groups}. Open = unreadCount.<me> 0 (+ markedUnread off) and lastRead.<me> stamp. Message requests (first message to a stranger, waiting for reply) are refused in the composer with a note. Demo: `--ez demo true --es thread d1` (1:1) or `d3` (group); shots thread_*.png checked light and dark. 10 unit tests (ThreadLogicTest); 51 unit tests total green.
- TODO chat page: send queue that survives a kill, delete/edit/reactions/reply-by-swipe, typing, presence, media bubbles, delivered.<me> writes on receipt, mark read only when scrolled to the bottom (iOS isAtBottom), readReceipts privacy switch (always on for now), unread divider, jump arrow, knock counter, device registry before publishing a key.
