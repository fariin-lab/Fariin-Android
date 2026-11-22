package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForwardRulesTest {
    private val me = "me"
    private val now = 1_000_000_000.0
    private fun doc(vararg extra: Pair<String, Any?>): Map<String, Any?> =
        mapOf("type" to "group", "users" to listOf(me, "owner"), "admins" to listOf("owner"), "createdBy" to "owner") + extra
    private fun conv(d: Map<String, Any?>, id: String = "g1") = Conversation.fromFirestore(id, d + ("title" to "Team"))
    private fun msg(type: String) = Message(id = type, authorId = "x", text = "", createdAtMillis = 0.0, type = type)

    @Test fun plainGroupTakesIt() {
        val d = doc()
        assertNull(ForwardRules.refusal(d, conv(d), me, listOf(msg("image")), nowMs = now))
    }

    @Test fun endedRowRefusesWithoutDoc() {
        val e = doc("endedAt" to com.google.firebase.Timestamp(1, 0))
        assertEquals("You can't send messages in Team.", ForwardRules.refusal(null, conv(e), me, emptyList(), nowMs = now))
    }

    @Test fun unknownDocRefusesNothing() {
        val d = doc("onlyAdminsSend" to true)
        assertNull(ForwardRules.refusal(null, conv(d), me, listOf(msg("image")), nowMs = now))
    }

    @Test fun adminsOnlyAndEnded() {
        val a = doc("onlyAdminsSend" to true)
        assertEquals("You can't send messages in Team.", ForwardRules.refusal(a, conv(a), me, emptyList(), nowMs = now))
        val e = doc("mod" to mapOf("state" to "ended"))
        assertEquals("You can't send messages in Team.", ForwardRules.refusal(e, conv(e), me, emptyList(), nowMs = now))
        // The owner is never held by announcement mode.
        assertNull(ForwardRules.refusal(a, conv(a), "owner", emptyList(), nowMs = now))
    }

    @Test fun slowModeOneAtATime() {
        val d = doc("slowMode" to 60)
        assertEquals("Slow mode is on in Team. Forward one message at a time.",
            ForwardRules.refusal(d, conv(d), me, listOf(msg("text"), msg("text")), nowMs = now))
        assertNull(ForwardRules.refusal(d, conv(d), me, listOf(msg("text")), nowMs = now))
        val mine = d + ("lastSender" to me) + ("updatedAt" to com.google.firebase.Timestamp(((now - 20_000) / 1000).toLong(), 0))
        assertEquals("Slow mode is on in Team. You can send your next message in 40 seconds.",
            ForwardRules.refusal(mine, conv(mine), me, listOf(msg("text")), nowMs = now))
    }

    @Test fun kindOff() {
        val d = doc("memberSend" to mapOf("photos" to false, "location" to false))
        assertEquals("Photos are off in Team.", ForwardRules.refusal(d, conv(d), me, listOf(msg("album")), nowMs = now))
        assertEquals("Location is off in Team.", ForwardRules.refusal(d, conv(d), me, listOf(msg("location")), nowMs = now))
        assertNull(ForwardRules.refusal(d, conv(d), me, listOf(msg("album")), share = true, nowMs = now))
    }

    @Test fun pollOff() {
        val d = doc("memberSend" to mapOf("polls" to false))
        val poll = Message(id = "p", authorId = "x", createdAtMillis = 0.0, type = "text",
            text = PollRules.markerText("abc", "Lunch?", listOf("Yes", "No"), false))
        assertEquals("Polls are off in Team.", ForwardRules.refusal(d, conv(d), me, listOf(poll), nowMs = now))
        assertNull(ForwardRules.refusal(d, conv(d), me, listOf(msg("text")), nowMs = now))
    }

    @Test fun locationOff() {
        val d = doc("memberSend" to mapOf("location" to false))
        val place = Message(id = "l", authorId = "x", createdAtMillis = 0.0, type = "text",
            text = CardRules.locationText(2.04, 45.34, "Market"))
        assertEquals("Location is off in Team.", ForwardRules.refusal(d, conv(d), me, listOf(place), nowMs = now))
        assertNull(ForwardRules.refusal(doc(), conv(doc()), me, listOf(place), nowMs = now))
    }

    @Test fun linkOff() {
        val d = doc("memberSend" to mapOf("links" to false))
        val link = Message(id = "k", authorId = "x", createdAtMillis = 0.0, type = "text", text = "see https://fariin.com/help")
        assertEquals("Links are off in Team.", ForwardRules.refusal(d, conv(d), me, listOf(link), nowMs = now))
        val plain = Message(id = "t", authorId = "x", createdAtMillis = 0.0, type = "text", text = "see you soon")
        assertNull(ForwardRules.refusal(d, conv(d), me, listOf(plain), nowMs = now))
        // A photo whose caption has a link is still a photo, as iOS.
        val photo = Message(id = "i", authorId = "x", createdAtMillis = 0.0, type = "image", text = "https://fariin.com")
        assertNull(ForwardRules.refusal(d, conv(d), me, listOf(photo), nowMs = now))
    }

    @Test fun noteInSlowGroup() {
        val d = doc("slowMode" to 30)
        assertEquals("Slow mode is on in Team. Send without a comment, or send the comment later.",
            ForwardRules.noteRefusal(listOf(conv(d) to d), me, "hi"))
        assertNull(ForwardRules.noteRefusal(listOf(conv(d) to d), me, "  "))
    }
}
