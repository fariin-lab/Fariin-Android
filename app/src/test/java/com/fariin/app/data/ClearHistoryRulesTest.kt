package com.fariin.app.data

import com.fariin.app.data.ClearHistoryRules.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/** Clear Chat History takes every chat in the list, followed channels too, as iOS. */
class ClearHistoryRulesTest {
    private val me = "me"

    @Test fun personAndGroupGetMyMark() {
        assertEquals(Step.CLEAR_MARK, ClearHistoryRules.step(me, Conversation(id = "d1", users = listOf(me, "you"))))
        assertEquals(Step.CLEAR_MARK, ClearHistoryRules.step(me, Conversation(id = "g1", users = listOf(me, "a", "b"), convType = "group")))
    }

    @Test fun channelIAmInGetsMyMark() {
        val mine = Conversation(id = "c1", users = listOf(me), isChannel = true, createdBy = me)
        assertEquals(Step.CLEAR_MARK, ClearHistoryRules.step(me, mine))
    }

    @Test fun followedChannelIsLeftAlone() {
        val followed = Conversation(id = "c2", users = listOf("owner"), isChannel = true, followerRead = 3)
        assertEquals(Step.SKIP, ClearHistoryRules.step(me, followed))
    }

    @Test fun fariinChatClearsItsOwnHistory() {
        assertEquals(Step.CLEAR_OFFICIAL, ClearHistoryRules.step(me, Conversation(id = OfficialChannel.CID)))
    }
}
