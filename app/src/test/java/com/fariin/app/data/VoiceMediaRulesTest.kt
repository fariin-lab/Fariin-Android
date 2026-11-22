package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceMediaRulesTest {
    @Test fun someoneElsesNoteShowsTheChatName() {
        assertEquals(VoiceMediaRules.Meta("Amina", "Voice message"), VoiceMediaRules.meta("Amina", mine = false, appLocked = false))
    }

    @Test fun myOwnNoteSaysYouAndTheChat() {
        assertEquals("You · Family", VoiceMediaRules.meta("Family", mine = true, appLocked = false).title)
        assertEquals("You", VoiceMediaRules.meta("", mine = true, appLocked = false).title)
    }

    @Test fun unknownChatFallsBackToVoiceMessage() {
        assertEquals("Voice message", VoiceMediaRules.meta("  ", mine = false, appLocked = false).title)
    }

    @Test fun appLockHidesTheName() {
        val m = VoiceMediaRules.meta("Amina", mine = false, appLocked = true)
        assertEquals(VoiceMediaRules.Meta("Voice message", ""), m)
        assertEquals("Voice message", VoiceMediaRules.meta("Family", mine = true, appLocked = true).title)
    }
}
