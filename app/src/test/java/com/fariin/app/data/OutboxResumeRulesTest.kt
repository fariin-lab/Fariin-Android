package com.fariin.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxResumeRulesTest {
    @Test fun theChatOnScreenEndsALostSend() = assertTrue(OutboxResumeRules.mayFailHere(onScreen = true))

    @Test fun aQuietCopyLeavesTheEntryForTheChat() = assertFalse(OutboxResumeRules.mayFailHere(onScreen = false))
}
