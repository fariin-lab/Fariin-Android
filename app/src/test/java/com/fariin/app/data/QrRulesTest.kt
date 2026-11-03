package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrRulesTest {
    @Test fun linksAsIos() {
        assertEquals("https://fariin.com/u/alice", QrRules.userLink("alice"))
        assertEquals("https://fariin.com", QrRules.inviteLink(""))
        assertEquals("https://fariin.com/u/bob_1", QrRules.inviteLink("bob_1"))
    }

    @Test fun readsUserCodesOnly() {
        assertEquals("alice", QrRules.handleFrom("https://fariin.com/u/alice"))
        assertEquals("alice", QrRules.handleFrom("https://www.fariin.com/u/alice"))
        assertEquals("alice", QrRules.handleFrom("kulan://u/alice"))
        assertNull(QrRules.handleFrom("https://evil.com/u/alice"))
        assertNull(QrRules.handleFrom("https://fariin.com/g/abc"))
        assertNull(QrRules.handleFrom("https://fariin.com/u/a%2Fb"))
        assertNull(QrRules.handleFrom("https://fariin.com/u/__x__"))
        assertNull(QrRules.handleFrom("hello"))
    }

    @Test fun matrixHasFindersAndClearCentre() {
        val m = QrRules.matrix(QrRules.userLink("alice"))
        val n = m.size
        assertTrue(n >= 21)
        // A finder square's corner is dark in all three places.
        for ((x, y) in QrRules.eyes(n)) assertTrue(m[y][x])
        val r = QrRules.clearRange(n, 0.22f)
        assertEquals(n % 2, (r.last - r.first + 1) % 2)
        assertEquals(r.first, n - 1 - r.last)
    }
}
