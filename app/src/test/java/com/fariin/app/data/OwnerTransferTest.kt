package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerTransferTest {
    @Test fun channelWordsAreWholeWords() {
        assertEquals("Leave Channel?", ChannelWords.apply("Leave Group?"))
        assertEquals("Approve New Followers", ChannelWords.apply("Approve New Members"))
        assertEquals("Follow Requests", ChannelWords.apply("Join Requests"))
        assertEquals("remember the groupies", ChannelWords.apply("remember the groupies"))
        assertEquals("Join Requests", ChannelWords.forChat(false, "Join Requests"))
    }

    @Test fun whoMayTransfer() {
        val users = listOf("a", "b")
        assertTrue(OwnerTransfer.canTransfer("a", "a", "b", users))
        assertFalse(OwnerTransfer.canTransfer("a", "a", "a", users))      // not to myself
        assertFalse(OwnerTransfer.canTransfer("b", "a", "a", users))      // only the owner
        assertFalse(OwnerTransfer.canTransfer("a", "a", "c", users))      // only a member
        assertTrue(OwnerTransfer.offersChooseAnother("a", "a", users))
        assertFalse(OwnerTransfer.offersChooseAnother("a", "a", listOf("a")))
        assertFalse(OwnerTransfer.offersChooseAnother("b", "a", users))
    }

    @Test fun refusalReasonAndWords() {
        assertEquals("no-password", OwnerTransfer.reasonOf(mapOf("reason" to "no-password")))
        assertEquals("", OwnerTransfer.reasonOf(null))
        assertEquals("", OwnerTransfer.reasonOf("text"))
        assertTrue(OwnerTransfer.Refusal("no-password", "x").needsPassword)
        assertEquals("Enter your password to finish this. Bo will have full control of this channel, including over you.",
            OwnerTransfer.passwordMessage(true, "Bo", leaving = false))
        assertEquals("Enter your password to finish this. Bo will become the owner, then you leave the group.",
            OwnerTransfer.passwordMessage(false, "Bo", leaving = true))
        assertTrue(OwnerTransfer.noPasswordMessage(false, false).endsWith("Add one in Settings, then try again after 5 hours."))
        assertEquals("Bo is now the owner", OwnerTransfer.doneToast("Bo"))
    }

    @Test fun joinRequestsShownAndKept() {
        val a = JoinRequests.from("a", mapOf("name" to "Ann"))
        val b = JoinRequests.from("b", mapOf("name" to "", "photo" to ""))
        assertEquals("New member", b.name)
        assertNull(b.photo)
        assertEquals(listOf(b), JoinRequests.shown(listOf(a, b), setOf("a")))
        // Asked again after an answer: the old answer is dropped once the server dropped the request.
        assertEquals(setOf("a"), JoinRequests.keepAnswered(listOf(a), setOf("a", "z")))
        assertEquals("None", JoinRequests.rowValue(0))
        assertEquals("3", JoinRequests.rowValue(3))
        assertEquals("Follow Requests", JoinRequests.title(true))
        assertEquals("People who ask to join “Trip” with a group link show up here.", JoinRequests.emptyFooter(false, "Trip"))
    }

    @Test fun reactionsRules() {
        assertEquals("off" to emptyList<String>(), ChannelReactions.normalize("some", emptyList()))
        assertEquals("all" to emptyList<String>(), ChannelReactions.normalize("weird", listOf("👍")))
        assertEquals(12, ChannelReactions.normalize("some", ChannelReactions.palette).second.size)
        assertEquals(listOf("👍", "🔥"), ChannelReactions.normalize("some", listOf("👍", "👍", "🔥")).second)
        assertEquals(ChannelReactions.palette.take(6), ChannelReactions.startList(emptyList(), emptyList()))
        assertEquals(listOf("🔥"), ChannelReactions.startList(emptyList(), listOf("🔥")))
        // Replace, swap, add, full.
        assertEquals(listOf("😂", "🔥"), ChannelReactions.place(listOf("👍", "🔥"), 0, "😂"))
        assertEquals(listOf("🔥", "👍"), ChannelReactions.place(listOf("👍", "🔥"), 0, "🔥"))
        assertEquals(listOf("👍", "😂"), ChannelReactions.place(listOf("👍"), null, "😂"))
        assertNull(ChannelReactions.place(listOf("👍"), null, "👍"))
        assertEquals(ChannelReactions.palette.take(6) + "🌭", ChannelReactions.place(ChannelReactions.palette.take(6), null, "🌭"))
        assertNull(ChannelReactions.place(ChannelReactions.palette.take(12), null, "🌭"))
        assertEquals(listOf("👍"), ChannelReactions.remove(listOf("👍"), 0))   // the last one stays
        assertEquals(listOf("🔥"), ChannelReactions.remove(listOf("👍", "🔥"), 0))
        assertEquals("Off", ChannelReactions.value("off"))
        assertEquals("Any", ChannelReactions.value("all"))
        assertEquals("Pick at least one, or reactions will be off.", ChannelReactions.pickFooter(emptyList()))
    }

    @Test fun addFollowersToasts() {
        val r = ChannelAddFollowers.parse(mapOf("added" to listOf("a"), "refused" to listOf("b", "c"), "linkOnly" to listOf("c"), "capped" to emptyList<String>()))
        assertEquals(listOf("1 follower added", "1 person couldn't be added"), ChannelAddFollowers.toasts(r, 3))
        assertEquals(setOf("c"), ChannelAddFollowers.viaLink(r))
        val none = ChannelAddFollowers.parse(mapOf("added" to emptyList<String>()))
        assertEquals(listOf("Already following"), ChannelAddFollowers.toasts(none, 1))
        val full = ChannelAddFollowers.parse(mapOf("capped" to listOf("x")))
        assertEquals(listOf(ChannelAddFollowers.ADDS_FULL), ChannelAddFollowers.toasts(full, 1))
        assertEquals("Ann and Bo only let certain people add them to channels. Send the channel's link in your chat instead.",
            ChannelAddFollowers.inviteInstead(listOf("Ann", "Bo")))
        assertEquals("Follow “News” on Fariin: https://fariin.com/g/x", ChannelAddFollowers.shareText("News", "https://fariin.com/g/x"))
    }

    @Test fun noEmDashes() {
        val all = listOf(OwnerTransfer.OFFLINE, OwnerTransfer.CREATE_FIRST, OwnerTransfer.noPasswordMessage(true, true),
            OwnerTransfer.chooseFooter(true), ChannelAddFollowers.ADDS_FULL, ChannelReactions.FOOTER, JoinRequests.empty(false))
        all.forEach { assertFalse(it, it.contains('—')) }
    }
}
