package com.fariin.app.ui.chats

import com.fariin.app.data.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListDeletePlanTest {
    private val me = "me"
    private val dm = Conversation(id = "d", users = listOf(me, "amina"), names = mapOf("amina" to "Amina"))
    private val group = Conversation(id = "g", users = listOf(me, "x"), convType = "group", title = "Family")
    private val formerGroup = Conversation(id = "f", users = listOf("x"), convType = "group", title = "Old")
    private val followed = Conversation(id = "c", users = listOf("owner"), convType = "group", title = "VE3", isChannel = true, followerRead = 0)
    private val myChannel = Conversation(id = "m", users = listOf(me), convType = "group", title = "Mine", isChannel = true)

    @Test fun kinds() {
        assertEquals(DeleteKind.HIDE, ChatListActions.deleteKind(me, dm))
        assertEquals(DeleteKind.LEAVE_GROUP, ChatListActions.deleteKind(me, group))
        assertEquals(DeleteKind.HIDE, ChatListActions.deleteKind(me, formerGroup))
        assertEquals(DeleteKind.UNFOLLOW, ChatListActions.deleteKind(me, followed))
        assertEquals(DeleteKind.LEAVE_CHANNEL, ChatListActions.deleteKind(me, myChannel))
    }

    @Test fun singleWords() {
        assertEquals(DeletePlan("Leave Channel?", "Are you sure you want to leave VE3?", "Leave Channel"),
            ChatListActions.deletePlan(me, listOf(followed)))
        assertEquals("Leave Channel", ChatListActions.deletePlan(me, listOf(myChannel)).button)
        assertEquals("Delete Group", ChatListActions.deletePlan(me, listOf(group)).button)
        assertEquals("Delete for Me", ChatListActions.deletePlan(me, listOf(dm)).button)
        assertEquals("Delete Chat", ChatListActions.deletePlan(me, listOf(formerGroup)).button)
    }

    @Test fun mixedSaysWhatIsLeft() {
        val p = ChatListActions.deletePlan(me, listOf(dm, group, followed, myChannel))
        assertEquals("Delete 4 chats?", p.title)
        assertEquals("Delete", p.button)
        assertEquals("You will leave the group and the 2 channels you selected. The other chat is removed from your list.", p.body)
        val chats = ChatListActions.deletePlan(me, listOf(dm, formerGroup))
        assertTrue(chats.body.startsWith("The chats are removed"))
    }

    @Test fun everyPlanHasAnAction() {
        for (pick in listOf(listOf(dm), listOf(group), listOf(followed), listOf(myChannel), listOf(followed, group), listOf(dm, followed)))
            assertTrue(ChatListActions.deletePlan(me, pick).button.isNotEmpty())
    }
}
