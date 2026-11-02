# Google Play Data safety form (draft answers)

Drafted 2 November 2026. Sources: the privacy policy (https://fariin.com/privacy, updated
2 November 2026), the iOS App Privacy answers (Fariin-AppStore/APP_STORE_ANSWERS.md), and a grep of
this Android app's code on the same day. If the app starts collecting something new, change the
policy, the iOS answers AND this form together.

Play's words: "collected" = sent off the phone to us or our service providers. "Shared" = given to
a third party that is NOT acting for us. Service providers (Firebase, Cloudflare, LiveKit, Resend)
act for us, so nothing below is "shared".

## Section 1: Data collection and security

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes (Firebase, LiveKit and the call relays are all TLS or DTLS; 1:1 content is also end to end encrypted) |
| Do you provide a way for users to request that their data is deleted? | Yes: in the app (Settings > Account > Delete account, 30 day wait, "Delete It Now" skips it) and on the web (https://fariin.com/help, delete your account page) |

## Section 2: Data types

Every type below: Collected = Yes, Shared = No, Processed ephemerally = No.
"Required" means the user cannot use the app without it; "Optional" means only when they use that
feature.

### Location
| Type | Collected | Required / optional | Purposes | Why |
|---|---|---|---|---|
| Approximate location | Yes | Required | App functionality, Fraud prevention, security and compliance | The server works out a city from the connection's IP when the app opens (`recordDeviceLocation`, device/DeviceRegistry.kt) and stores only the place name on the user's own device record, shown in Settings > Devices. No location permission. |
| Precise location | No | | | The Android app has no location permission and no location sharing yet. When location messages or story place stickers come to Android, change this to Yes, Optional, App functionality. |

### Personal info
| Type | Collected | Required / optional | Purposes |
|---|---|---|---|
| Name | Yes | Required | App functionality, Account management |
| Email address | Yes | Required | App functionality, Account management, Developer communications (sign-in alerts, password change emails), Fraud prevention, security and compliance |
| User IDs | Yes | Required | App functionality, Account management, Fraud prevention, security and compliance |
| Address, Phone number, Race and ethnicity, Political or religious beliefs, Sexual orientation, Other info | No | | |

Note: username, bio and links count as User IDs / Other user-generated content.

### Financial info, Health and fitness, Calendar, Contacts, Web browsing
All No. Fariin never reads the address book. There are no purchases in the app.

### Messages
| Type | Collected | Required / optional | Purposes | Why |
|---|---|---|---|---|
| Other in-app messages | Yes | Required | App functionality | Group and channel messages are readable on our side (policy: "Group chats", "Channels"). 1:1 messages are stored only as end to end ciphertext, plus who sent them, to whom, when and the kind of message. |
| Emails, SMS or MMS | No | | | |

### Photos and videos
| Type | Collected | Required / optional | Purposes |
|---|---|---|---|
| Photos | Yes | Optional | App functionality (chat photos, profile photo, stories; group and story photos are readable on our side) |
| Videos | Yes | Optional | App functionality |

### Audio
| Type | Collected | Required / optional | Purposes |
|---|---|---|---|
| Voice or sound recordings | Yes | Optional | App functionality (voice messages; group call audio passes through LiveKit) |
| Music files, Other audio files | No | | |

### Files and docs
| Type | Collected | Required / optional | Purposes |
|---|---|---|---|
| Files and docs | Yes once file sending lands (media/FilePrep.kt is in progress in the shared tree on 2 November). Answer Yes, Optional, App functionality: group files are readable on our side. | | |

### App activity
| Type | Collected | Required / optional | Purposes | Why |
|---|---|---|---|---|
| App interactions | Yes | Required | App functionality | Online status and last seen (users/{uid}/presence/state), read and delivered receipts, story views, channel follows, call records (who, when, how long) |
| Other user-generated content | Yes | Optional | App functionality | Stories, reactions, bio, profile links, poll votes |
| In-app search history | No | | | Username search is sent to the server to answer it and is not stored. (If GIF search comes to Android, its text goes to Giphy: then declare Yes, Optional, App functionality.) |
| Installed apps, Other actions | No | | | |

### App info and performance
| Type | Collected | Required / optional | Purposes | Why |
|---|---|---|---|---|
| Crash logs | Yes | Required | Analytics, App functionality | Firebase Crashlytics in release builds (CrashReports.kt): stack trace, app version, phone model, Android version, a salted hash of the user id. Never names, email or message text. Kept up to 90 days. |
| Diagnostics | Yes | Required | Analytics, App functionality | Crashlytics custom keys (build, model, install). The iOS technical log upload is not on Android yet, but the policy covers it, so declare it now. |
| Other app performance data | No | | | |

### Device or other IDs
| Type | Collected | Required / optional | Purposes | Why |
|---|---|---|---|---|
| Device or other IDs | Yes | Required | App functionality, Fraud prevention, security and compliance | FCM push token (users/{uid}/push/tokens), the install's ANDROID_ID as the device record id (one phone per account, "Logged in on another phone"), the Crashlytics install id. No advertising id. |

## Section 3: Security practices shown on the listing

- Data is encrypted in transit: Yes
- You can request that data be deleted: Yes
- Committed to the Play Families Policy: No (not a children's app, 16+)
- Independent security review: No (leave unticked)

## Checks before pressing Save in Console
- The release build's merged manifest has no location, contacts, SMS or phone state permission
  (see PERMISSIONS.md). Gallery read permissions are being added for the attach grid; reading the
  gallery on the phone is not "collection", only the photos sent are. If a later build adds
  location or contacts, this form must change first.
- Advertising ID: the app declares no AD_ID permission. In Console > App content > Advertising ID,
  answer "No, my app does not use an advertising ID". Firebase Analytics is not in the app; keep it
  that way or this answer changes.
