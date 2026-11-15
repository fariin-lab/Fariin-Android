# Fariin Android: map for agents (read this, then go straight to the files named)

Native Android (Kotlin + Jetpack Compose). Same app as iOS (`C:\Users\abdik\Documents\Kulan-iOS`),
same backend: Firebase project `kulan-2ef85`, server code in `C:\Users\abdik\Documents\Kulan`
(functions/, functions-username/, functions-admin/ ... one codebase per folder, see its firebase.json).
Work branch: `chat-speed` (everything ships from it). Specs from iOS arrive in
`C:\Users\abdik\Documents\fariin-plans\to-android\` (done ones move to `done\`).

## Hard rules
- Icons: NEVER Material icons. Use `app/src/main/res/drawable/ic_sf_*` (our own iOS-style outline
  drawings) or draw a new `ic_sf_<name>.xml` (stroke 1.5-1.8, round caps, 24x24, comment "Our own drawing").
- Never write Telegram / WhatsApp / Signal / Viber / TikTok in code comments or commit messages: "the reference app".
- No em-dashes in comments or commit messages. Plain short English.
- Never install on the owner's Pixel (serial 0B251FDD4000WB) and never `installDebug` unless the
  brief says so. Never push to `chat-speed` unless the brief says so: commit on your own branch.
- Never deploy server code unless the brief says so; then deploy BY NAME
  (`firebase deploy --only functions:<codebase>:<name>`), never a whole codebase, never `--force`.
- No big-photo profile header on Android, ever. Do only what the brief asks; no refactors.
- Behaviour and data formats must match iOS exactly (shared Firestore contract); the look is ours.

## Build (Windows, Git Bash)
```
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew -q :app:compileDebugKotlin > ../build.log 2>&1; grep -E "^e:" ../build.log   # -q hides failures: always grep
./gradlew -q :app:testDebugUnitTest --tests "com.fariin.app.data.PollRulesTest"
./gradlew -q :app:assemblePixelfast     # the build the owner's Pixel runs
```
A new worktree needs `local.properties` (`sdk.dir=C:/Users/abdik/AppData/Local/Android/Sdk`) and
`keystore.properties` copied from `C:\Users\abdik\AppData\Local\Temp\awt-poll1114\`.
adb: `/c/Users/abdik/AppData/Local/Android/Sdk/platform-tools/adb`, with `export MSYS_NO_PATHCONV=1`
and Windows-style `C:/...` paths for files. Emulators: `emulator -list-avds` (fariin_pixel2 etc.).
Unit tests live in `app/src/test/java/com/fariin/app/data/*Test.kt` (pure rules objects); device
tests in `app/src/androidTest/`.

## Where things are (package com.fariin.app)
Pattern: pure logic in `data/*Rules.kt` (unit tested), Firestore in `data/*Repository/Store/Service`,
screens in `ui/<area>/`.

| Area | Files |
|---|---|
| Chat screen (bubbles, menus, composer, forward/quote sheets) | `ui/thread/ThreadScreen.kt` (big), `ThreadViewModel.kt` (actions wiring), `MessageActions.kt`, `ForwardSheet.kt`, `MediaBubbles.kt`, `PinSelect.kt`, `SwipeActions.kt` |
| Messages: send, listen, pending bubbles, chat summary (`lastMessage`) | `data/ThreadRepository.kt` (big), `data/Message.kt`, `data/MessageRules.kt` |
| Media send / forward / upload / seal | `media/MediaSender.kt`, `media/Forwarder.kt`, `media/ChatMedia.kt` (download+decrypt cache), `media/PhotoPrep.kt`, `media/VideoPrep.kt`, `media/MediaRules.kt` |
| Attach sheet (photo grid), video send / trim editor | `ui/thread/AttachSheet.kt`, `ui/thread/VideoTrimEditor.kt` |
| Media viewer (photo/video full screen, speed, share, full screen) | `ui/thread/MediaViewer.kt`, `ui/thread/MediaViewerVideo.kt` |
| Polls (composer, card, results, data) | `ui/thread/PollBubble.kt`, `data/PollRules.kt`, `data/PollVotes.kt` |
| Quotes of channel posts | `data/QuoteRules.kt`, `data/QuoteStore.kt`, `data/PostQuotes.kt`, `ui/channels/PostQuotesUi.kt` |
| Chat list (rows, previews, typing line) | `ui/chats/ChatListScreen.kt`, `ui/chats/ChatRowPreview.kt`, `data/ChatList.kt` (RowPreview, previewLine), `data/Conversation.kt` (doc parse, typing map) |
| Channels | `ui/channels/ChannelThread.kt` (post menu), `ChannelPage.kt`, `ChannelRoute.kt` (routes), `channel/*` |
| Stories | `ui/stories/StoriesScreen.kt` (tab), `StoryViewer.kt`, `StoryComposer.kt`, `StoryCamera.kt`, `StoryLibrary.kt` (our own photo sheet), `data/StoryMedia.kt`, `media/StoryVideoCut.kt` |
| Profile, group/channel info, username page | `ui/people/GroupAndProfile.kt`, `ui/people/PeopleScreens.kt`, `data/People.kt` (PeopleApi callables) |
| Settings pages | `ui/settings/*` |
| Official Fariin chat | `ui/official/OfficialChatScreen.kt`, `OfficialInfoPage.kt`, `data/OfficialChannel.kt` |
| Calls | `call/*`, `ui/call/*`, `ui/calls/*` |
| Tab shell, bottom bar inset | `ui/shell/MainShell.kt` (`bottomBarInset()`) |
| Shared UI (glass menus, alerts, floating buttons, motion) | `ui/components/*` (`GlassDropdown`, `GlassAlert`, `Modifier.floating`, `Motion.kt` PageMotion, `Snack`) |
| Crypto (sealing text/files) | `crypto/FariinCrypto.kt`, `crypto/Wire.kt` (EncMeta) |
| Push notifications | `push/*` |
| Remote switches / limits from the server | `data/AppFeatures.kt`, `data/AppConfig.kt` |
| App start | `FariinApp.kt`, `MainActivity.kt` |

Find anything else with `grep -rn "<words the user sees>" app/src/main/java` (UI strings are inline
in Kotlin, not in strings.xml), then read only the lines around it (files are large: read slices).
