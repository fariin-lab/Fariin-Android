package com.fariin.app.system

import com.fariin.app.data.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of system/: which chats become shortcuts, the App Lock rule for the
 *  notification buttons, and how shared items are sorted. */
class SystemIntegrationTest {
    private val me = "me"

    private fun dm(other: String, name: String, at: Double, vararg extra: Pair<String, Any>) = Conversation(
        id = listOf(me, other).sorted().joinToString("_"), users = listOf(me, other),
        names = mapOf(other to name, me to "Me"), updatedAtMillis = at,
    ).let { c ->
        var x = c
        for ((k, v) in extra) x = when (k) {
            "pinned" -> x.copy(pinnedBy = mapOf(me to true))
            "archived" -> x.copy(archivedBy = mapOf(me to true))
            "request" -> x.copy(startedBy = v as String, accepted = false)
            else -> x
        }
        x
    }

    @Test fun shortcutsPinnedFirstThenNewest() {
        val list = listOf(dm("a", "Amina", 100.0), dm("b", "Bobby", 300.0), dm("c", "Carol", 200.0, "pinned" to true))
        val chosen = ChatShortcuts.choose(list, me)
        assertEquals(listOf("Carol", "Bobby", "Amina"), chosen.map { it.title })
        assertEquals("b", chosen[1].personUid)
        assertFalse(chosen[1].isGroup)
    }

    @Test fun shortcutsLeaveOutArchivedRequestsChannelsAndCap() {
        val list = listOf(
            dm("a", "Amina", 100.0, "archived" to true),
            dm("b", "Bobby", 300.0, "request" to "b"),          // their request, not answered
            dm("d", "Dahir", 250.0, "request" to me),           // my request, waiting
            Conversation(id = "chan1", users = emptyList(), isChannel = true, updatedAtMillis = 400.0),
        ) + (1..12).map { dm("p$it", "Person $it", it.toDouble()) }
        val chosen = ChatShortcuts.choose(list, me)
        assertEquals(ChatShortcuts.MAX_PUBLISHED, chosen.size)
        assertTrue(chosen.none { it.title in setOf("Amina", "Bobby", "Dahir") })
        assertTrue(chosen.none { it.cid == "chan1" })
        assertEquals("Person 12", chosen.first().title)
        // The launcher's top three are the three newest.
        assertEquals(listOf("Person 12", "Person 11", "Person 10"), chosen.take(3).map { it.title })
    }

    @Test fun shortcutSignatureFollowsNamesAndOrder() {
        val a = ChatShortcuts.choose(listOf(dm("a", "Amina", 1.0), dm("b", "Bobby", 2.0)), me)
        val b = ChatShortcuts.choose(listOf(dm("a", "Amina", 3.0), dm("b", "Bobby", 2.0)), me)
        val c = ChatShortcuts.choose(listOf(dm("a", "Amina Y", 1.0), dm("b", "Bobby", 2.0)), me)
        assertTrue(ChatShortcuts.signature(a) != ChatShortcuts.signature(b))
        assertTrue(ChatShortcuts.signature(a) != ChatShortcuts.signature(c))
        assertEquals(ChatShortcuts.signature(a), ChatShortcuts.signature(a.toList()))
    }

    @Test fun notificationButtonsUnderAppLock() {
        // Off: Reply and Mark as read, no unlock.
        assertEquals(NotifActions.Allowed(true, false, true), NotifActions.allowed(false, 34, true))
        // On, Android 12+: Reply asks for the phone's unlock.
        assertEquals(NotifActions.Allowed(true, true, true), NotifActions.allowed(true, 31, true))
        // On, Android 11 and older: no way to ask, so no Reply; Mark as read stays.
        assertEquals(NotifActions.Allowed(false, false, true), NotifActions.allowed(true, 30, true))
        // Not a chat notification: no buttons.
        assertEquals(NotifActions.Allowed(false, false, false), NotifActions.allowed(false, 34, false))
    }

    @Test fun shareKinds() {
        assertEquals(ShareIntake.Kind.MEDIA, ShareIntake.kindOf("image/jpeg"))
        assertEquals(ShareIntake.Kind.MEDIA, ShareIntake.kindOf("VIDEO/mp4"))
        assertEquals(ShareIntake.Kind.FILE, ShareIntake.kindOf("image/svg+xml"))
        assertEquals(ShareIntake.Kind.FILE, ShareIntake.kindOf("application/pdf"))
        assertEquals(ShareIntake.Kind.FILE, ShareIntake.kindOf(null))
    }

    @Test fun shareText() {
        assertNull(ShareIntake.textOf(null, "  "))
        assertEquals("https://fariin.com", ShareIntake.textOf(null, "https://fariin.com"))
        assertEquals("Fariin\nhttps://fariin.com", ShareIntake.textOf("Fariin", "https://fariin.com"))
        assertEquals("Read Fariin https://fariin.com", ShareIntake.textOf("Fariin", "Read Fariin https://fariin.com"))
        assertEquals("Subject only", ShareIntake.textOf("Subject only", null))
    }

    @Test fun shareFileNamesAndSummary() {
        assertEquals("pdf", ShareIntake.extensionFor("application/octet-stream", "Report.PDF"))
        assertEquals("jpg", ShareIntake.extensionFor("image/jpeg", null))
        assertEquals("mp4", ShareIntake.extensionFor("video/mp4", "weird.name.with.toolongext"))
        assertEquals("bin", ShareIntake.extensionFor(null, null))
        assertEquals("1 photo or video", ShareIntake.summary(1, 0, true))
        assertEquals("3 photos and videos, 1 file", ShareIntake.summary(3, 1, false))
        assertEquals("Text", ShareIntake.summary(0, 0, true))
        // Plain English, no em dashes in what the user reads.
        for (s in listOf(ShareIntake.summary(2, 2, true))) assertFalse(s.contains('—'))
    }
}
