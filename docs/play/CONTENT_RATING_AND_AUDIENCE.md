# Content rating, target audience and other App content answers (draft)

Drafted 2 November 2026. Play Console > Policy > App content. The owner fills these in himself.

## Target audience and content
- Target age groups: 16-17 and 18 and over. Do NOT tick 13-15 or anything younger.
  The privacy policy says Fariin is not for anyone under 16, and iOS is rated 16+.
- Could the app unintentionally appeal to children? No (plain messenger, no cartoon characters, no
  games, no child-themed store graphics).
- Because no under-13 group is picked, the Families Policy does not apply and no teacher-approved
  questions come up.

## Content rating questionnaire (IARC)
- Email for the certificate: the owner's support address.
- Category: "Social or Communication" (all other app types / communication).

| Question | Answer | Note |
|---|---|---|
| Violence, blood, sexual content, crude humour, drugs, alcohol, tobacco, gambling made by the app | No | Fariin ships no content of its own. |
| Does the app let users interact or exchange content with each other? | Yes | Chats, calls, groups, stories, channels. |
| Can users share their location with other users? | No today on Android. Yes once location messages ship (iOS has them). | Answer for the build being rated. |
| Does the app allow users to buy digital goods? | No | |
| Does the app contain unrestricted internet access (a web browser)? | No | Links open in the phone's browser. |
| Is the app a news app? | No | Channels are user-run, not news from the developer. |
| Does the app share user-generated content that is moderated? | Users can block and report from any chat and profile; reports reach the team (reports collection). | |

Expected result: a rating near "Teen / PEGI 12 / USK 12" with the descriptor "Users Interact".
The age gate that matters is the target audience above (16+), not the rating.

## Other App content declarations
| Item | Answer |
|---|---|
| Privacy policy | https://fariin.com/privacy |
| Ads | No, the app has no ads |
| App access | "All or some functionality is restricted": give Play a NEW Android-only review account (never review1/review2 while Apple's review is open, and never the owner's own account: one phone per account). Write the account details only into Console, not into git. |
| Advertising ID | No |
| Government app | No |
| Financial features | None |
| Health | No |
| News app | No |
| Data safety | see DATA_SAFETY.md |
| Account deletion | In-app path + https://fariin.com/help web link. BLOCKER: on Android, Settings > Delete Account still says "Coming soon" (ui/settings/SettingsScreen.kt). Play requires the in-app path to work. |
| Full-screen intent (Android 14+) | Declare "Calling": incoming voice and video calls show over the lock screen. See PERMISSIONS.md. |
| Foreground service types | phoneCall, microphone, camera for an ongoing call. Play asks for a short video of the feature: record an outgoing call on a test build. See PERMISSIONS.md. |
