package com.fariin.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadConvRulesTest {
    private val base = mapOf<String, Any?>("users" to listOf("me", "o"), "lastMessage" to "x", "updatedAt" to 1,
        "unreadCount" to mapOf("me" to 0, "o" to 2), "typing" to mapOf("o" to 0), "pinned" to listOf("a"))

    @Test fun summaryOnly() {
        assertTrue(ThreadConvRules.onlySummaryChanged(base, base + mapOf("lastMessage" to "y", "updatedAt" to 2,
            "unreadCount" to mapOf("me" to 0, "o" to 3), "typing" to mapOf("o" to 0, "me" to 5)), "me"))
    }

    @Test fun marksSkippedInOneToOneOnly() {
        val read = base + ("lastRead" to mapOf("o" to 5))
        assertTrue(ThreadConvRules.onlySummaryChanged(base, read, "me"))
        assertFalse(ThreadConvRules.onlySummaryChanged(base, read, "me", group = true))
    }

    @Test fun realChangesGoThrough() {
        assertFalse(ThreadConvRules.onlySummaryChanged(base, base + ("pinned" to listOf("b")), "me"))
        assertFalse(ThreadConvRules.onlySummaryChanged(base, base + ("unreadCount" to mapOf("me" to 1, "o" to 2)), "me"))
        assertFalse(ThreadConvRules.onlySummaryChanged(base, base + ("typing" to mapOf("o" to 9)), "me"))
        assertFalse(ThreadConvRules.onlySummaryChanged(base + ("lastMessage" to ""), base, "me"))
        assertFalse(ThreadConvRules.onlySummaryChanged(emptyMap(), base, "me"))
    }
}
