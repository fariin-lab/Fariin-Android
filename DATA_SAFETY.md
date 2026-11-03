# Google Play: Data safety and permission declarations (prepared from the code, 2026-11-03)

Copy these into Play Console > App content. If anything here does not match what you know about
the server, tell me before submitting: the form must be true.

## Data safety

**Does the app collect or share user data?** Yes (collects). **Shared with third parties?** No.
**Encrypted in transit?** Yes. **Can users request deletion?** Yes: in the app (Settings >
Account > Delete Account) and at fariin.com (the account deletion link Play asks for).

Messages, photos, videos, voice notes, files, locations and contact cards sent in chats are
end-to-end encrypted: the server stores them sealed and cannot read them. Play treats end-to-end
encrypted data that the developer cannot read as **not collected**, so they are not listed below.

| Data type (Play's names) | Collected | Why (Play's purposes) | Optional? |
|---|---|---|---|
| Personal info: Email address | Yes | Account management | Required (sign-in) |
| Personal info: Name | Yes | App functionality, Account management | Required (display name) |
| Personal info: User IDs | Yes | App functionality, Account management | Required (username) |
| Photos and videos: Photos (profile photo, stories) | Yes | App functionality | Optional |
| App activity: Other user-generated content (stories, channel posts that are not end-to-end encrypted, group names) | Yes | App functionality | Optional |
| App info and performance: Crash logs, Diagnostics | Yes | Analytics (Crashlytics) | Required |
| Device or other IDs (push token, device record) | Yes | App functionality (notifications, devices list) | Required |

Not collected: location (shared only inside end-to-end encrypted messages), contacts, financial
info, health, web history, installed apps, audio (calls and voice notes are end-to-end
encrypted), advertising ID (no ads, no analytics SDK).

## Permission declarations Play will ask for
- **Full-screen intent** (USE_FULL_SCREEN_INTENT): "Calling app: shows the incoming call screen."
- **Foreground service types**:
  - phoneCall: voice and video calls in progress.
  - microphone / camera: the call's audio and video while the app is in the background.
  - mediaProjection: screen sharing during a call, started by the user, with Android's own
    consent dialog.
  Play asks for a short video of each; record one call with screen share on the Pixel.
- **Location** (fine and coarse): "Share my location in a chat, only when the user taps Location."
- **Camera / Microphone**: calls, voice notes, photos, stories.

## Other App content answers
- Ads: No. Target audience: 13+ (same as the App Store age rating; confirm). News app: No.
- Content rating questionnaire: a messaging app with user-generated content and user-to-user
  communication; report and block exist (same answers as the App Store).
