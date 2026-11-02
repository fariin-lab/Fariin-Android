# Permissions in the release build, and why (draft)

Taken from the MERGED release manifest on 2 November 2026
(app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml),
so it includes lines that libraries add. Re-check it before each upload:
`findstr uses-permission` on that file.

## Asked on screen (runtime permissions)

| Permission | Asked when | Used for | If the user says no |
|---|---|---|---|
| POST_NOTIFICATIONS (Android 13+) | After sign-in, on the Notifications explainer page, when the user taps Continue | Message notifications and the incoming call ring | No banners and no ring when the app is closed; chats still work |
| RECORD_AUDIO | First hold of the mic button, or the first call | Voice messages; the user's voice in a call | No voice notes, calls cannot start |
| CAMERA | First video call | The user's video in a call | Video calls go voice only |

Photos are picked with the system Photo Picker, which needs no storage or media permission.
There is no location, contacts, phone state, SMS or storage permission.

## Granted at install (no prompt)

| Permission | From | Used for |
|---|---|---|
| INTERNET | app | Everything online |
| ACCESS_NETWORK_STATE | app libraries (Firebase, LiveKit) | Reconnecting when the network comes back, resending queued messages |
| WAKE_LOCK | Firebase Messaging, WorkManager | Handling a push while the phone sleeps |
| com.google.android.c2dm.permission.RECEIVE | Firebase Messaging | Receiving pushes |
| MODIFY_AUDIO_SETTINGS | app (calls) | Speaker / earpiece switching during a call |
| BLUETOOTH (Android 11 and below only, maxSdkVersion 30) | LiveKit audio switching | Routing call audio to a Bluetooth headset on old Android |
| MANAGE_OWN_CALLS | app (calls) | Self-managed calling (the system call framework); kept for the planned Telecom integration |
| FOREGROUND_SERVICE, FOREGROUND_SERVICE_PHONE_CALL, FOREGROUND_SERVICE_MICROPHONE, FOREGROUND_SERVICE_CAMERA | app (call/CallService) | Keeping a call alive when the user leaves the app or locks the phone |
| USE_FULL_SCREEN_INTENT | app (call/IncomingCallWatcher) | Showing an incoming call over the lock screen |
| USE_BIOMETRIC, USE_FINGERPRINT | biometric prompt library (Credential Manager / App Lock) | Fingerprint or face check for App Lock (security/AppLock.kt) and passkeys; the phone does the check, Fariin never gets the biometric data |
| DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION | AndroidX core (generated) | Internal, not a user permission |
| READ_GSERVICES | Google Play services | Internal Google Play services check |

Removed in release (app/src/release/AndroidManifest.xml): FOREGROUND_SERVICE_MEDIA_PROJECTION and
LiveKit's ScreenCaptureService. Android has no screen sharing yet, so the build does not carry a
foreground service type it never uses.

## Play Console declarations

### Full-screen intent (Policy > App content > Full-screen intent)
Pick: "Calling: the app makes and receives calls".
Text to paste:
> Fariin is a messenger with voice and video calls. When someone calls, the app shows the incoming
> call screen over the lock screen with Accept and Decline, the same way the phone app does. The
> full-screen intent is used only for incoming calls, never for messages or other notifications.

### Foreground service types (Policy > App content > Foreground service permissions)
| Type | Task | Text |
|---|---|---|
| phoneCall | "Make and receive calls" | During a voice or video call, a foreground service with an ongoing call notification keeps the call running when the user switches apps or locks the screen. It starts when a call connects and stops when the call ends. |
| microphone | "Voice or video call" | Sends the user's voice during a call they started or accepted. Only while the call is on. |
| camera | "Video call" | Sends the user's camera during a video call. Only while the call is on and the camera is switched on. |

Play asks for a video link for these: record a short screen video of starting a call, pressing Home,
and the ongoing call notification with Hang Up. Make it on a test build with two Android-only test
accounts.

## Coming in work not yet committed (seen in the shared tree on 2 November 2026)
Other workers are adding these to app/src/main/AndroidManifest.xml. Re-run the merged-manifest
check once they land and move each line into the tables above.

| Permission | For | Play note |
|---|---|---|
| READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED, READ_EXTERNAL_STORAGE (max 32) | The recent photos grid in the attach sheet | **Risk.** Play's Photo and Video Permissions policy lets only apps whose core purpose needs broad gallery access hold READ_MEDIA_IMAGES / READ_MEDIA_VIDEO; others must use the system Photo Picker. A messenger's attach grid is often refused. Either declare it (Console > App content > Photo and video permissions, text below) and accept the review risk, or keep the Photo Picker only and drop these lines from the release manifest. My advice: drop them for the first release. |
| WRITE_EXTERNAL_STORAGE (max 28) | Saving to the gallery on Android 9 | Fine, old phones only. |
| WAKE_LOCK (app) | Proximity sensor screen-off at the ear during a voice call | Already granted through Firebase. |
| USE_BIOMETRIC (app) | App Lock | Already in the list above. |
| Telecom ConnectionService (BIND_TELECOM_CONNECTION_SERVICE) | Headset, car and Bluetooth call buttons | No Console declaration; MANAGE_OWN_CALLS covers it. |

Declaration text if the grid stays:
> Fariin is a messenger. Sending photos and videos to friends is a core feature used many times a
> day, from a grid of recent photos inside the chat's attach sheet, so the user can pick several
> quickly while staying in the conversation. Photos are read only when the user opens that sheet.

## Gaps seen while listing (app TODO, not permissions to add blindly)
- Android 12+ Bluetooth headsets: BLUETOOTH_CONNECT is not declared, so LiveKit cannot route call
  audio to a headset on new phones. Adding it means a new runtime prompt and a line here.
- Android 14+: Play limits USE_FULL_SCREEN_INTENT to calling and alarm apps. Fariin qualifies, but the
  app should still check `NotificationManager.canUseFullScreenIntent()` and fall back to a heads-up
  notification when the user has switched it off.
