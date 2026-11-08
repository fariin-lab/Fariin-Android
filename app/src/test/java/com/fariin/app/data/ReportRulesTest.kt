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
}
