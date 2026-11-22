package com.fariin.app.data

/**
 * What the lock screen, the media notification and a headset see for a playing voice note
 * (iOS VoiceNotePlayer.updateNowPlaying). Pure, so it is unit tested.
 *
 * iOS: the title is the chat's name (noteTitle, the same words as the floating bar), "You · <chat>"
 * for my own note, and the line under it says "Voice message". No artwork for a voice note.
 * With App Lock on, nothing outside the app may name the chat: only "Voice message" shows.
 */
object VoiceMediaRules {
    const val VOICE = "Voice message"

    data class Meta(val title: String, val subtitle: String)

    fun meta(chatName: String, mine: Boolean, appLocked: Boolean): Meta {
        if (appLocked) return Meta(VOICE, "")
        val name = chatName.trim()
        val title = when {
            mine -> if (name.isEmpty()) "You" else "You · $name"
            else -> name.ifEmpty { VOICE }
        }
        return Meta(title, VOICE)
    }
}
