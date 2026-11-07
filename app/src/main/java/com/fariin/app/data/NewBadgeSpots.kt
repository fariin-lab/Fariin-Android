package com.fariin.app.data

/**
 * Every spot that can show the admin panel's green "New" pill (iOS NewBadgeSpots; the server
 * catalog is functions-admin newBadgeSpots.js). The ids are shared with iOS and the panel: never
 * rename one. The first 12 are the old Settings row keys. Tag a spot with a constant from here,
 * never a typed string.
 */
object NewBadgeSpots {
    // Settings
    const val SUBSCRIPTIONS = "subscriptions"
    const val ACCOUNT = "account"
    const val DEVICES = "devices"
    const val NOTIFICATIONS = "notifications"
    const val APPEARANCE = "appearance"
    const val CHATS = "chats"
    const val STORIES = "stories"
    const val PRIVACY = "privacy"
    const val STORAGE = "storage"
    const val INVITE = "invite"
    const val SAFETY = "safety"
    const val HELP = "help"
    const val MY_PROFILE = "myProfile"

    // Settings > Account
    const val ACCOUNT_EMAIL = "account.email"
    const val ACCOUNT_PASSWORD = "account.password"
    const val ACCOUNT_USERNAME = "account.username"
    const val ACCOUNT_PASSKEYS = "account.passkeys"
    const val ACCOUNT_TWO_STEP = "account.twoStep"
    const val ACCOUNT_SECURITY_NOTIFICATIONS = "account.securityNotifications"
    const val ACCOUNT_ACCOUNT_DATA = "account.accountData"
    const val ACCOUNT_LOG_OUT = "account.logOut"

    // Settings > Notifications
    const val NOTIFICATIONS_SHOW = "notifications.show"
    const val NOTIFICATIONS_PREVIEW = "notifications.preview"
    const val NOTIFICATIONS_SOUND = "notifications.sound"
    const val NOTIFICATIONS_IN_APP_SOUNDS = "notifications.inAppSounds"
    const val NOTIFICATIONS_IN_APP_VIBRATE = "notifications.inAppVibrate"
    const val NOTIFICATIONS_IN_APP_PREVIEW = "notifications.inAppPreview"
    const val NOTIFICATIONS_SENT_SOUND = "notifications.sentSound"
    const val NOTIFICATIONS_RESET_ALL = "notifications.resetAll"

    // Settings > Appearance
    const val APPEARANCE_CHAT_WALLPAPER = "appearance.chatWallpaper"
    const val APPEARANCE_CHAT_COLOR = "appearance.chatColor"
    const val APPEARANCE_BUBBLE_STYLES = "appearance.bubbleStyles"
    const val APPEARANCE_APP_ICON = "appearance.appIcon"
    const val APPEARANCE_QUICK_REACTION = "appearance.quickReaction"
    const val APPEARANCE_NIGHT_MODE = "appearance.nightMode"

    // Settings > Chats
    const val CHATS_CHAT_KEY = "chats.chatKey"
    const val CHATS_SAVE_TO_PHOTOS = "chats.saveToPhotos"
    const val CHATS_LINK_PREVIEWS = "chats.linkPreviews"
    const val CHATS_AUTO_ARCHIVE = "chats.autoArchive"
    const val CHATS_CLEAR_HISTORY = "chats.clearHistory"

    // Settings > Stories
    const val STORIES_VIEW_RECEIPTS = "stories.viewReceipts"

    // Settings > Privacy & Security
    const val PRIVACY_LAST_SEEN = "privacy.lastSeen"
    const val PRIVACY_PHOTO = "privacy.photo"
    const val PRIVACY_BIO = "privacy.bio"
    const val PRIVACY_GROUPS = "privacy.groups"
    const val PRIVACY_MESSAGES = "privacy.messages"
    const val PRIVACY_CALLS = "privacy.calls"
    const val PRIVACY_BLOCKED = "privacy.blocked"
    const val PRIVACY_ALWAYS_RELAY = "privacy.alwaysRelay"
    const val PRIVACY_CALLS_IN_RECENTS = "privacy.callsInRecents"
    const val PRIVACY_DISAPPEARING = "privacy.disappearing"
    const val PRIVACY_APP_LOCK = "privacy.appLock"
    const val PRIVACY_SAFETY_TIPS = "privacy.safetyTips"
    const val PRIVACY_PROFILE_LAYOUT = "privacy.profileLayout"

    // Settings > Storage and Data
    const val STORAGE_MANAGE = "storage.manage"
    const val STORAGE_AUTO_DOWNLOAD_PHOTOS = "storage.autoDownload.photos"
    const val STORAGE_AUTO_DOWNLOAD_VIDEOS = "storage.autoDownload.videos"
    const val STORAGE_AUTO_DOWNLOAD_AUDIO = "storage.autoDownload.audio"
    const val STORAGE_AUTO_DOWNLOAD_DOCUMENTS = "storage.autoDownload.documents"
    const val STORAGE_SENT_MEDIA_QUALITY = "storage.sentMediaQuality"
    const val STORAGE_USE_LESS_DATA = "storage.useLessData"

    // Settings > Help & About
    const val HELP_HELP_CENTER = "help.helpCenter"
    const val HELP_PRIVACY_POLICY = "help.privacyPolicy"
    const val HELP_TERMS = "help.terms"

    // Channel Settings
    const val CHANNEL_SETTINGS_REACTIONS = "channelSettings.reactions"
    const val CHANNEL_SETTINGS_REPLIES = "channelSettings.replies"
    const val CHANNEL_SETTINGS_FOLLOWERS = "channelSettings.followers"
    const val CHANNEL_SETTINGS_SCHEDULED_POSTS = "channelSettings.scheduledPosts"

    // Group / Channel Settings
    const val GROUP_SETTINGS_DISAPPEARING = "groupSettings.disappearing"
    const val GROUP_SETTINGS_CHAT_HISTORY = "groupSettings.chatHistory"
    const val GROUP_SETTINGS_RECENT_ACTIONS = "groupSettings.recentActions"
    const val GROUP_SETTINGS_TYPE = "groupSettings.type"
    const val GROUP_SETTINGS_INVITE_LINK = "groupSettings.inviteLink"
    const val GROUP_SETTINGS_REQUESTS = "groupSettings.requests"
    const val GROUP_SETTINGS_MEMBERS = "groupSettings.members"
    const val GROUP_SETTINGS_ADMINS = "groupSettings.admins"
    const val GROUP_SETTINGS_BANNED = "groupSettings.banned"
    const val GROUP_SETTINGS_PERMISSIONS = "groupSettings.permissions"
    const val GROUP_SETTINGS_MEDIA = "groupSettings.media"
    const val GROUP_SETTINGS_PROTECT_CONTENT = "groupSettings.protectContent"

    // Group / Channel info page
    const val GROUP_INFO_SOUNDS = "groupInfo.sounds"
    const val GROUP_INFO_STORAGE = "groupInfo.storage"
    const val GROUP_INFO_CHAT_BUBBLE = "groupInfo.chatBubble"
    const val GROUP_INFO_STATISTICS = "groupInfo.statistics"
    const val GROUP_INFO_FOLLOWERS = "groupInfo.followers"
    const val GROUP_INFO_SETTINGS = "groupInfo.settings"

    // Profile page (a person's info page)
    const val PROFILE_DISAPPEARING = "profile.disappearing"
    const val PROFILE_RESTRICTED = "profile.restricted"
    const val PROFILE_SOUNDS = "profile.sounds"
    const val PROFILE_CHAT_BUBBLE = "profile.chatBubble"
    const val PROFILE_VERIFY_ENCRYPTION = "profile.verifyEncryption"

    // Chat > attach menu
    const val ATTACH_GIF = "attach.gif"
    const val ATTACH_FILES = "attach.files"
    const val ATTACH_LOCATION = "attach.location"
    const val ATTACH_POLL = "attach.poll"

    // Story editor > tools
    const val STORY_EDITOR_TEXT = "storyEditor.text"
    const val STORY_EDITOR_TRIM = "storyEditor.trim"
    const val STORY_EDITOR_CROP = "storyEditor.crop"
    const val STORY_EDITOR_STICKERS = "storyEditor.stickers"
    const val STORY_EDITOR_DRAW = "storyEditor.draw"

    // Story editor > Stickers
    const val STORY_STICKERS_LOCATION = "storyStickers.location"
    const val STORY_STICKERS_LINK = "storyStickers.link"
    const val STORY_STICKERS_TIME = "storyStickers.time"

    // Chat > message long-press menu
    const val CHAT_MENU_REPLY = "chatMenu.reply"
    const val CHAT_MENU_FORWARD = "chatMenu.forward"
    const val CHAT_MENU_QUOTE = "chatMenu.quote"
    const val CHAT_MENU_REPLIES = "chatMenu.replies"
    const val CHAT_MENU_COPY = "chatMenu.copy"
    const val CHAT_MENU_BUBBLE_STYLE = "chatMenu.bubbleStyle"
    const val CHAT_MENU_EDIT = "chatMenu.edit"
    const val CHAT_MENU_EDIT_HISTORY = "chatMenu.editHistory"
    const val CHAT_MENU_SAVE_IMAGE = "chatMenu.saveImage"
    const val CHAT_MENU_INFO = "chatMenu.info"
    const val CHAT_MENU_PIN = "chatMenu.pin"
    const val CHAT_MENU_REPORT = "chatMenu.report"

    /** The whole catalog; [NewBadges.parse] drops any id not in it. */
    val ALL: Set<String> = setOf(
        SUBSCRIPTIONS, ACCOUNT, DEVICES, NOTIFICATIONS, APPEARANCE, CHATS,
        STORIES, PRIVACY, STORAGE, INVITE, SAFETY, HELP,
        MY_PROFILE, ACCOUNT_EMAIL, ACCOUNT_PASSWORD, ACCOUNT_USERNAME, ACCOUNT_PASSKEYS, ACCOUNT_TWO_STEP,
        ACCOUNT_SECURITY_NOTIFICATIONS, ACCOUNT_ACCOUNT_DATA, ACCOUNT_LOG_OUT, NOTIFICATIONS_SHOW, NOTIFICATIONS_PREVIEW, NOTIFICATIONS_SOUND,
        NOTIFICATIONS_IN_APP_SOUNDS, NOTIFICATIONS_IN_APP_VIBRATE, NOTIFICATIONS_IN_APP_PREVIEW, NOTIFICATIONS_SENT_SOUND, NOTIFICATIONS_RESET_ALL, APPEARANCE_CHAT_WALLPAPER,
        APPEARANCE_CHAT_COLOR, APPEARANCE_BUBBLE_STYLES, APPEARANCE_APP_ICON, APPEARANCE_QUICK_REACTION, APPEARANCE_NIGHT_MODE, CHATS_CHAT_KEY,
        CHATS_SAVE_TO_PHOTOS, CHATS_LINK_PREVIEWS, CHATS_AUTO_ARCHIVE, CHATS_CLEAR_HISTORY, STORIES_VIEW_RECEIPTS, PRIVACY_LAST_SEEN,
        PRIVACY_PHOTO, PRIVACY_BIO, PRIVACY_GROUPS, PRIVACY_MESSAGES, PRIVACY_CALLS, PRIVACY_BLOCKED,
        PRIVACY_ALWAYS_RELAY, PRIVACY_CALLS_IN_RECENTS, PRIVACY_DISAPPEARING, PRIVACY_APP_LOCK, PRIVACY_SAFETY_TIPS, PRIVACY_PROFILE_LAYOUT,
        STORAGE_MANAGE, STORAGE_AUTO_DOWNLOAD_PHOTOS, STORAGE_AUTO_DOWNLOAD_VIDEOS, STORAGE_AUTO_DOWNLOAD_AUDIO, STORAGE_AUTO_DOWNLOAD_DOCUMENTS, STORAGE_SENT_MEDIA_QUALITY,
        STORAGE_USE_LESS_DATA, HELP_HELP_CENTER, HELP_PRIVACY_POLICY, HELP_TERMS, CHANNEL_SETTINGS_REACTIONS, CHANNEL_SETTINGS_REPLIES,
        CHANNEL_SETTINGS_FOLLOWERS, CHANNEL_SETTINGS_SCHEDULED_POSTS, GROUP_SETTINGS_DISAPPEARING, GROUP_SETTINGS_CHAT_HISTORY, GROUP_SETTINGS_RECENT_ACTIONS, GROUP_SETTINGS_TYPE,
        GROUP_SETTINGS_INVITE_LINK, GROUP_SETTINGS_REQUESTS, GROUP_SETTINGS_MEMBERS, GROUP_SETTINGS_ADMINS, GROUP_SETTINGS_BANNED, GROUP_SETTINGS_PERMISSIONS,
        GROUP_SETTINGS_MEDIA, GROUP_SETTINGS_PROTECT_CONTENT, GROUP_INFO_SOUNDS, GROUP_INFO_STORAGE, GROUP_INFO_CHAT_BUBBLE, GROUP_INFO_STATISTICS,
        GROUP_INFO_FOLLOWERS, GROUP_INFO_SETTINGS, PROFILE_DISAPPEARING, PROFILE_RESTRICTED, PROFILE_SOUNDS, PROFILE_CHAT_BUBBLE,
        PROFILE_VERIFY_ENCRYPTION, ATTACH_GIF, ATTACH_FILES, ATTACH_LOCATION, ATTACH_POLL, STORY_EDITOR_TEXT,
        STORY_EDITOR_TRIM, STORY_EDITOR_CROP, STORY_EDITOR_STICKERS, STORY_EDITOR_DRAW, STORY_STICKERS_LOCATION, STORY_STICKERS_LINK,
        STORY_STICKERS_TIME, CHAT_MENU_REPLY, CHAT_MENU_FORWARD, CHAT_MENU_QUOTE, CHAT_MENU_REPLIES, CHAT_MENU_COPY,
        CHAT_MENU_BUBBLE_STYLE, CHAT_MENU_EDIT, CHAT_MENU_EDIT_HISTORY, CHAT_MENU_SAVE_IMAGE, CHAT_MENU_INFO, CHAT_MENU_PIN,
        CHAT_MENU_REPORT,
    )
}
