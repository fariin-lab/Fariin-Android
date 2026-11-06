package com.fariin.app.data

import com.fariin.app.data.MemberRestrict.R
import com.fariin.app.ui.chats.MuteFor
import com.fariin.app.ui.thread.AdminDelete
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberRestrictTest {
    private val now = 1_000_000_000_000.0
    private fun group(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = mapOf(
        "type" to "group", "createdBy" to "o", "users" to listOf("o", "a", "m", "n"), "admins" to listOf("o", "a"),
        "adminRights" to mapOf("a" to listOf("deleteMessages")),
        "membersCanAdd" to true, "membersCanEditInfo" to false,
    ) + extra

    private fun restricted(uid: String, flags: List<String>, until: Double, extra: Map<String, Any?> = emptyMap()) =
        group(mapOf("restrictedFlags" to mapOf(uid to flags), "restrictedUntil" to mapOf(uid to until)) + extra)

    @Test fun muteRunsUntilItsEnd() {
        val d = restricted("m", MemberRestrict.MUTE_ALL, now + 1000)
        assertTrue(MemberRestrict.isMuted(d, "m", now))
        assertFalse(MemberRestrict.isMuted(d, "m", now + 1000))
        assertFalse(MemberRestrict.isMuted(d, "n", now))
    }

    @Test fun staffAreNeverRestricted() {
        val d = restricted("a", MemberRestrict.MUTE_ALL, now + 1000)
        assertFalse(MemberRestrict.isMuted(d, "a", now))
        assertFalse(MemberRestrict.hasRestriction(d, "a", now))
    }

    @Test fun oneToOneHasNoMutedBar() {
        val d = restricted("m", MemberRestrict.MUTE_ALL, now + 1000, mapOf("type" to "direct"))
        assertFalse(MemberRestrict.isMuted(d, "m", now))
    }

    @Test fun oldMediaFlagCoversTheSplitKinds() {
        val d = restricted("m", listOf("sendMedia"), now + 1000)
        assertTrue(MemberRestrict.isRestricted(d, "m", R.SEND_PHOTOS, now))
        assertTrue(MemberRestrict.isRestricted(d, "m", R.SEND_LINKS, now))
        assertFalse(MemberRestrict.isRestricted(d, "m", R.SEND_VOICE, now))
        assertFalse(MemberRestrict.isMuted(d, "m", now))
    }

    @Test fun muteEndIsFiftyYearsForUntilLifted() {
        assertEquals(now + 3_600_000, MemberRestrict.muteEnd(3600.0, now), 0.1)
        assertEquals(now + MemberRestrict.FOREVER_SECONDS * 1000, MemberRestrict.muteEnd(0.0, now), 0.1)
    }

    @Test fun sheetKeepsATimedEnd() {
        val timed = restricted("m", listOf("sendVoice"), now + 3_600_000)
        assertEquals(now + 3_600_000, MemberRestrict.sheetEnd(timed, "m", now), 0.1)
        assertEquals(MemberRestrict.muteEnd(0.0, now), MemberRestrict.sheetEnd(group(), "m", now), 0.1)
        val forever = restricted("m", listOf("sendVoice"), MemberRestrict.muteEnd(0.0, now))
        assertEquals(MemberRestrict.muteEnd(0.0, now), MemberRestrict.sheetEnd(forever, "m", now), 0.1)
    }

    @Test fun groupLocksSwitches() {
        val d = group(mapOf("onlyAdminsSend" to true))
        assertFalse(MemberRestrict.groupLets(d, R.SEND_TEXT))
        assertTrue(MemberRestrict.groupLets(d, R.ADD_MEMBERS))
        assertFalse(MemberRestrict.groupLets(d, R.CHANGE_INFO))
        assertFalse(MemberRestrict.groupLets(d, R.PIN_MESSAGES))
    }

    @Test fun seedStartsFromWhatTheyMayDoNow() {
        val d = restricted("m", listOf("sendVoice"), now + 1000)
        val s = MemberRestrict.seed(d, listOf("m"), now)
        assertTrue(R.SEND_TEXT in s)
        assertFalse(R.SEND_VOICE in s)
        assertFalse(R.CHANGE_INFO in s)   // the group has it off
    }

    @Test fun messagesOffTakesTheMediaAndMediaOnBringsItBack() {
        val d = group()
        val all = MemberRestrict.seed(d, listOf("m"), now)
        val off = MemberRestrict.setAllowed(d, all, R.SEND_TEXT, false)
        assertTrue(MemberRestrict.MEDIA.none { it in off })
        val back = MemberRestrict.setAllowed(d, off, R.SEND_FILES, true)
        assertTrue(R.SEND_TEXT in back && R.SEND_FILES in back)
    }

    @Test fun nothingOffWritesNothing() {
        val d = group()
        assertEquals(emptyList<String>(), MemberRestrict.flagsFor(d, "m", MemberRestrict.seed(d, listOf("m"), now), now))
    }

    @Test fun messagesOffWritesTheWholeMuteSet() {
        val d = group()
        val f = MemberRestrict.flagsFor(d, "m", MemberRestrict.setAllowed(d, MemberRestrict.seed(d, listOf("m"), now), R.SEND_TEXT, false), now)
        assertTrue(f.containsAll(MemberRestrict.MUTE_ALL))
        assertTrue(f.containsAll(listOf("sendPhotos", "sendFiles", "sendLinks")))
        assertEquals(R.entries.map { it.slug }.filter { it in f }, f)   // model order
    }

    @Test fun allThreeFinerMediaOffAddsTheOldFlag() {
        val d = group()
        var a = MemberRestrict.seed(d, listOf("m"), now)
        for (r in listOf(R.SEND_PHOTOS, R.SEND_FILES, R.SEND_LINKS)) a = MemberRestrict.setAllowed(d, a, r, false)
        val f = MemberRestrict.flagsFor(d, "m", a, now)
        assertEquals(listOf("sendMedia", "sendPhotos", "sendFiles", "sendLinks"), f)
    }

    @Test fun lockedSwitchKeepsTheirOwnValue() {
        val d = restricted("m", listOf("changeInfo"), now + 1000)
        val f = MemberRestrict.flagsFor(d, "m", MemberRestrict.seed(d, listOf("m"), now), now)
        assertEquals(listOf("changeInfo"), f)
    }

    // ---- The admin delete sheet's gate ---------------------------------------------------------

    private fun msg(id: String, author: String, type: String? = null) = Message(id = id, authorId = author, text = "hi", type = type, createdAtMillis = now)

    @Test fun sheetOpensForAnAdminOnSomeoneElsesMessage() {
        val d = group()
        assertTrue(AdminDelete.opens(d, "a", listOf(msg("1", "m"))))
        assertFalse(AdminDelete.opens(d, "a", listOf(msg("1", "a"))))            // my own: the plain question
        assertFalse(AdminDelete.opens(d, "m", listOf(msg("1", "n"))))            // not an admin
        assertFalse(AdminDelete.opens(d, "a", listOf(msg("1", "o"))))            // the owner's: not for an admin
        assertTrue(AdminDelete.opens(d, "o", listOf(msg("1", "a"))))
        assertFalse(AdminDelete.opens(d + ("type" to "direct"), "a", listOf(msg("1", "m"))))
        val noRight = d + ("adminRights" to mapOf("a" to listOf("banUsers")))
        assertFalse(AdminDelete.opens(noRight, "a", listOf(msg("1", "m"))))
    }

    @Test fun restrictNeedsTheRemoveRightAndAPlainMember() {
        val d = group()
        assertFalse(AdminDelete.canRestrict(d, "a", listOf("m")))   // a holds only Delete messages
        assertTrue(AdminDelete.canRestrict(d, "o", listOf("m")))
        assertFalse(AdminDelete.canRestrict(d, "o", listOf("a")))
    }

    @Test fun loadedByAuthorSkipsNoticesAndMine() {
        val d = group()
        val loaded = listOf(msg("1", "m"), msg("2", "m", "system"), msg("3", "a"), msg("4", "m"))
        assertEquals(listOf("1", "4"), AdminDelete.loadedByAuthor(d, "a", loaded, listOf("m"))["m"]?.map { it.id })
    }

    @Test fun doneLineAsIos() {
        assertEquals("Reported as spam. Mo restricted", AdminDelete.doneLine(true, emptyList(), listOf("m"), false) { "Mo" })
        assertEquals("Some of that couldn't be done", AdminDelete.doneLine(true, listOf("m"), emptyList(), true) { "Mo" })
        assertEquals("Delete 1 Message", AdminDelete.title(1))
        assertEquals("Delete 3 Messages", AdminDelete.title(3))
    }

    // ---- The chat list's timed mute ------------------------------------------------------------

    @Test fun chatListMuteChoicesAsIos() {
        assertEquals(listOf("Mute for 1 hour", "Mute for 8 hours", "Mute for 1 week", "Mute Always"), MuteFor.CHOICES.map { it.first })
        assertEquals(GroupInfoCircles.FOREVER, MuteFor.until(null, 5), 0.1)
        assertEquals(5 + 8 * 3_600_000.0, MuteFor.until(8.0, 5), 0.1)
        assertEquals("Muted for 1 hour", MuteFor.doneWord("Mute for 1 hour"))
        assertEquals("Muted", MuteFor.doneWord("Mute Always"))
    }

    @Test fun followedChannelStoresAlwaysAsNoEnd() {
        assertEquals(true to 0.0, MuteFor.channelEntry(GroupInfoCircles.FOREVER, now))
        assertEquals(true to now + 3_600_000, MuteFor.channelEntry(now + 3_600_000, now))
        assertEquals(false to 0.0, MuteFor.channelEntry(0.0, now))
    }
}
