package com.fariin.app.data

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRulesTest {
    private val me = "me"
    private val now = 10_000_000.0
    private fun msg(author: String = me, ageMs: Double = 60_000.0, text: String = "hi", type: String? = null) =
        Message(id = "m", authorId = author, text = text, type = type, createdAtMillis = now - ageMs)

    @Test fun editWindowIsFifteenMinutes() {
        assertTrue(MessageRules.canEdit(msg(ageMs = 14 * 60_000.0), me, now))
        assertFalse(MessageRules.canEdit(msg(ageMs = 15 * 60_000.0), me, now))
        assertFalse(MessageRules.canEdit(msg(author = "them"), me, now))
        assertFalse(MessageRules.canEdit(msg().copy(forwarded = true), me, now))
        assertFalse(MessageRules.canEdit(msg(type = "audio"), me, now))
        assertFalse(MessageRules.canEdit(msg(text = ""), me, now))
        assertFalse(MessageRules.canEdit(msg().copy(sendState = Message.SendState.SENDING), me, now))
        assertFalse(MessageRules.canEdit(msg().copy(deleted = true), me, now))
    }

    @Test fun deleteForEveryoneHasNoTimeLimitLikeIos() {
        assertTrue(MessageRules.canDeleteForEveryone(msg(ageMs = 30 * 24 * 3600_000.0), me))
        assertFalse(MessageRules.canDeleteForEveryone(msg(author = "them"), me))
        assertFalse(MessageRules.canDeleteForEveryone(msg().copy(deleted = true), me))
        assertFalse(MessageRules.canDeleteForEveryone(msg(type = "call"), me))
        assertTrue(MessageRules.canDeleteForEveryone(msg(author = "them"), me, adminMayDelete = true))
        assertFalse(MessageRules.canDeleteForEveryone(msg(author = "own"), me, adminMayDelete = true, authorIsOwner = true))
        assertTrue(MessageRules.canDeleteForEveryone(msg(author = "own"), me, true, authorIsOwner = true, iAmOwner = true))
    }

    @Test fun deleteHereGroupVersusDirect() {
        assertTrue(MessageRules.canDeleteHere(msg(author = "them"), me, isGroup = false))
        assertFalse(MessageRules.canDeleteHere(msg(author = "them"), me, isGroup = true))
        assertTrue(MessageRules.canDeleteHere(msg(), me, isGroup = true))
    }

    @Test fun reactionChipsAggregateLikeIos() {
        val chips = MessageRules.reactionChips(mapOf("a" to "👍", "b" to "👍", me to "❤️", "c" to "👍", "d" to "👍"), me)
        assertEquals(listOf("👍", "❤️"), chips.map { it.emoji })
        assertEquals(4, chips[0].count)
        assertTrue(chips[0].faces.isEmpty())
        assertTrue(chips[1].mine)
        assertEquals(listOf(me), chips[1].faces)
        val three = MessageRules.reactionChips(mapOf("z" to "😂", "a" to "😂", "m" to "😂"), me)
        assertEquals(listOf("a", "m", "z"), three[0].faces)
        assertNull(MessageRules.nextReaction("👍", "👍"))
        assertEquals("😮", MessageRules.nextReaction("👍", "😮"))
    }

    @Test fun typingValues() {
        assertEquals("text-1700000000", MessageRules.typingValue(true, 1_700_000_000_123L))
        assertEquals(false, MessageRules.typingValue(false, 0L))
        assertTrue(MessageRules.isTyping(true)); assertTrue(MessageRules.isTyping("text-5"))
        assertFalse(MessageRules.isTyping("audio-5")); assertTrue(MessageRules.isRecording("audio-5"))
    }

    @Test fun deliveredOnlyForTheirNewerOneToOne() {
        val c = Conversation(id = "a_me", users = listOf("a", me), lastSender = "a", updatedAtMillis = 100.0, delivered = mapOf(me to 50.0))
        assertEquals(mapOf("a_me" to 100.0), MessageRules.deliveredWrites(listOf(c), me, emptyMap()))
        assertTrue(MessageRules.deliveredWrites(listOf(c.copy(lastSender = me)), me, emptyMap()).isEmpty())
        assertTrue(MessageRules.deliveredWrites(listOf(c.copy(delivered = mapOf(me to 100.0))), me, emptyMap()).isEmpty())
        assertTrue(MessageRules.deliveredWrites(listOf(c), me, mapOf("a_me" to 100.0)).isEmpty())
        assertTrue(MessageRules.deliveredWrites(listOf(c.copy(convType = "group")), me, emptyMap()).isEmpty())
        assertTrue(MessageRules.deliveredWrites(listOf(c.copy(blockedBy = mapOf(me to true))), me, emptyMap()).isEmpty())
    }

    @Test fun unreadDividerAboveOldestUnread() {
        val ms = listOf("t1" to "a", "m1" to me, "t2" to "a", "t3" to "a").mapIndexed { i, (id, au) ->
            Message(id = id, authorId = au, text = "x", createdAtMillis = i.toDouble()) }
        assertEquals("t2", MessageRules.unreadDividerId(ms, me, 2))
        assertNull(MessageRules.unreadDividerId(ms, me, 0))
    }

    @Test fun usernameRules() {
        assertEquals("ab_c1", Usernames.sanitize("ab-_c1 !"))
        assertNull(Usernames.shapeProblem("abc"))
        assertEquals("Usernames can't start or end with _.", Usernames.shapeProblem("_abcde"))
        assertEquals("Usernames can't contain two _ in a row.", Usernames.shapeProblem("ab__cd"))
        assertEquals("Usernames need at least one letter.", Usernames.shapeProblem("12_345"))
        assertNull(Usernames.shapeProblem("amina_y"))
        assertTrue(Usernames.isValid("v")); assertFalse(Usernames.isValid("abcd"))
        assertEquals("amina", Usernames.lookupKey(" @Amina "))
        assertNull(Usernames.lookupKey("a/b")); assertNull(Usernames.lookupKey("__x__"))
        val pool = Usernames.suggestionPool("amina", Random(7))
        assertTrue(pool.size in 1..4)
        assertTrue(pool.all { it.startsWith("amina") && Usernames.shapeProblem(it) == null })
        assertEquals("https://fariin.com/u/amina", Usernames.profileLink("amina"))
    }

    @Test fun requestsAndKeys() {
        val c = Conversation(id = "a_me", users = listOf("a", me), startedBy = "a", accepted = false, lastSender = "a")
        assertEquals(Requests.Stance.INCOMING, Requests.stance(c, me))
        assertEquals(Requests.Stance.AWAITING_REPLY, Requests.stance(c.copy(startedBy = me, lastSender = me), me))
        assertEquals(Requests.Stance.FIRST_MESSAGE, Requests.stance(c.copy(startedBy = me, lastSender = ""), me))
        assertEquals(Requests.Stance.OPEN, Requests.stance(c.copy(accepted = true), me))
        assertTrue(Requests.firstMessageTooLong("x".repeat(151)))
        assertTrue(ChatKey.isValid("1234")); assertFalse(ChatKey.isValid("123")); assertFalse(ChatKey.isValid("12a4"))
        assertEquals("123456", ChatKey.fromPaste("key: 12-34-56-78"))
        assertTrue(Person.allows(emptyMap(), "groups", false))
        assertFalse(Person.allows(mapOf("groups" to "contacts"), "groups", false))
        assertFalse(Person.allows(mapOf("groups" to "nobody"), "groups", true))
    }

    @Test fun ownerLeavingHandsOver() {
        val data = mapOf<String, Any?>("users" to listOf(me, "a", "b"), "admins" to listOf(me, "b"), "createdBy" to me,
            "names" to mapOf("b" to "Bilan"))
        val plan = GroupRules.leavePlan(me, "Abdi", data)
        assertEquals("b", plan.heir)
        assertEquals(listOf("Bilan is now the owner", "Abdi left"), plan.notices)
        assertEquals("b", plan.update["createdBy"])
        val plain = GroupRules.leavePlan("a", "Ali", data)
        assertNull(plain.heir)
        assertEquals(listOf("Ali left"), plain.notices)
        assertEquals("You own this group. If you leave, Bilan becomes the owner.", GroupRules.ownerLeaveLine("Bilan"))
        assertEquals("A, B and 2 others", GroupRules.addedWho(listOf("A", "B", "C", "D")))
    }

    @Test fun messageParsesReactions() {
        val m = Message.fromFirestore("x", mapOf("authorId" to "a", "text" to "", "reactions" to mapOf("a" to "enc1:zz", "b" to "enc1:yy")),
            "grp") { _, uid -> if (uid == "a") "👍" else "" }
        assertEquals(mapOf("a" to "👍"), m.reactions)
    }
}
