package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportRulesTest {
    @Test fun categoriesMatchTheContractInOrder() {
        assertEquals(listOf("nudity", "child_safety", "hate", "scam", "violence", "illegal_goods", "bullying",
            "impersonation", "ip", "self_harm", "spam", "other"), ReportReason.entries.map { it.key })
    }

    @Test fun words() {
        assertEquals("Why are you reporting this account?", ReportRules.header(ReportKind.ACCOUNT))
        assertEquals("Reports are private. Amina won't know it was you.", ReportRules.privacy("Amina"))
        assertEquals("Reports are private. They won't know it was you.", ReportRules.privacy(" "))
        assertEquals("Report this reply?", ReportRules.confirmTitle(ReportKind.REPLY))
        assertEquals("Leave this group", ReportRules.leaveLabel(ReportTarget(ReportKind.GROUP)))
    }

    @Test fun blockOnlyWhereAPersonIs() {
        assertTrue(ReportRules.canBlock(ReportTarget(ReportKind.STORY, personUid = "u2"), "me"))
        assertFalse(ReportRules.canBlock(ReportTarget(ReportKind.GROUP, personUid = "u2"), "me"))
        assertFalse(ReportRules.canBlock(ReportTarget(ReportKind.CHANNEL), "me"))
        assertFalse(ReportRules.canBlock(ReportTarget(ReportKind.ACCOUNT, personUid = "me"), "me"))
        assertFalse(ReportRules.canBlock(ReportTarget(ReportKind.REPLY, personUid = null), "me"))
    }

    @Test fun noteOnlyWithSomethingElse() {
        assertEquals("", ReportRules.note(ReportReason.SPAM, "hello"))
        assertEquals("hi", ReportRules.note(ReportReason.OTHER, "  hi "))
        assertEquals(500, ReportRules.note(ReportReason.OTHER, "x".repeat(700)).length)
    }

    @Test fun requestCarriesTheIdsEachKindNeeds() {
        assertEquals(mapOf("kind" to "user", "category" to "spam", "block" to true, "id" to "u2", "cid" to "c1"),
            ReportRules.request(ReportTarget(ReportKind.ACCOUNT, personUid = "u2", cid = "c1"), ReportReason.SPAM, "", true))
        assertEquals(mapOf("kind" to "story", "category" to "other", "block" to false, "note" to "why", "id" to "s1", "uid" to "u2"),
            ReportRules.request(ReportTarget(ReportKind.STORY, personUid = "u2", storyId = "s1"), ReportReason.OTHER, "why", false))
        assertEquals(mapOf("kind" to "group", "category" to "hate", "block" to false, "cid" to "g1"),
            ReportRules.request(ReportTarget(ReportKind.GROUP, cid = "g1"), ReportReason.HATE, "", false))
        assertEquals(mapOf("kind" to "post", "category" to "scam", "block" to false, "cid" to "c1", "mid" to "m1"),
            ReportRules.request(ReportTarget(ReportKind.POST, cid = "c1", messageId = "m1"), ReportReason.SCAM, "", false))
        assertEquals(mapOf("kind" to "reply", "category" to "bullying", "block" to false, "cid" to "c1", "mid" to "m1", "rid" to "r1", "aid" to "a1"),
            ReportRules.request(ReportTarget(ReportKind.REPLY, cid = "c1", messageId = "m1", replyId = "r1", answerId = "a1"), ReportReason.BULLYING, "", false))
    }

    @Test fun refusals() {
        assertEquals("You've sent a lot of reports today. Try again tomorrow.", ReportRules.errorText("RESOURCE_EXHAUSTED"))
        assertEquals(ReportSender.FAILED, ReportRules.errorText(null))
    }

    @Test fun subReasons() {
        assertTrue(ReportSubs.of(ReportReason.SPAM).isEmpty())
        assertTrue(ReportSubs.of(ReportReason.OTHER).isEmpty())
        assertEquals(listOf("nudity", "sexual_messages", "intimate_images", "involves_minor"), ReportSubs.of(ReportReason.NUDITY).map { it.id })
        val minor = ReportSubs.of(ReportReason.NUDITY).last()
        assertEquals("child_safety", ReportRules.category(ReportReason.NUDITY, minor))
        val req = ReportRules.request(ReportTarget(ReportKind.STORY, storyId = "s1"), ReportReason.NUDITY, "", false, minor)
        assertEquals("child_safety", req["category"]); assertEquals("involves_minor", req["sub"])
        assertEquals("Nudity or sexual content › It involves someone under 18", ReportRules.reasonLine(ReportReason.NUDITY, minor))
        assertEquals("Child safety › It involves someone under 18", ReportSubs.label("child_safety", "involves_minor"))
        assertEquals("Spam", ReportSubs.label("spam", null))
    }

    @Test fun safetyBoxes() {
        val threat = ReportSubs.of(ReportReason.VIOLENCE).first { it.id == "threat" }
        assertTrue(ReportRules.safetyBox(ReportReason.VIOLENCE, threat) != null)
        assertEquals(null, ReportRules.safetyBox(ReportReason.VIOLENCE, ReportSubs.of(ReportReason.VIOLENCE).first { it.id == "graphic" }))
        assertTrue(ReportRules.safetyBox(ReportReason.SELF_HARM, null) != null)
        assertTrue(ReportRules.safetyBox(ReportReason.NUDITY, ReportSubs.of(ReportReason.NUDITY).last()) != null)
        assertEquals(null, ReportRules.safetyBox(ReportReason.SPAM, null))
    }

    @Test fun messageWordsOnlyForAGroupMessageWithTheSwitchOn() {
        val msg = ReportTarget(ReportKind.MESSAGE, cid = "g1", messageId = "m1", messageText = "hi")
        assertEquals("hi", ReportRules.request(msg, ReportReason.SPAM, "", false, null, shareText = true)["messageText"])
        assertFalse(ReportRules.request(msg, ReportReason.SPAM, "", false, null, shareText = false).containsKey("messageText"))
        val user = ReportTarget(ReportKind.ACCOUNT, personUid = "u2", messageText = "hi")
        assertFalse(ReportRules.request(user, ReportReason.SPAM, "", false, null, shareText = true).containsKey("messageText"))
    }

    @Test fun myReports() {
        assertEquals(MyReports.Status.RECEIVED, MyReports.status("open"))
        assertEquals(MyReports.Status.ACTION_TAKEN, MyReports.status("removed"))
        assertEquals(MyReports.Status.NO_VIOLATION, MyReports.status("no_violation"))
        assertEquals(MyReports.Status.ACTION_TAKEN, MyReports.status("action_taken"))
        assertEquals(MyReports.Status.RECEIVED, MyReports.status("received"))
        assertFalse(MyReports.more(mapOf("more" to false), 30))
        assertTrue(MyReports.more(emptyMap<String, Any>(), 30))
        assertEquals(1_700_000_000_000L, MyReports.millis(1_700_000_000_000L))
        assertEquals(1_700_000_000_000L, MyReports.millis(1_700_000_000L))
        assertEquals(1_700_000_000_000L, MyReports.millis(mapOf("_seconds" to 1_700_000_000L)))
        val list = MyReports.parse(mapOf("items" to listOf(mapOf("id" to "r1", "kind" to "user", "category" to "spam",
            "createdAt" to 1_700_000_000_000L, "status" to "open", "targetName" to "Amina"))))
        assertEquals("Account · Amina", MyReports.what(list.single()))
    }
}
