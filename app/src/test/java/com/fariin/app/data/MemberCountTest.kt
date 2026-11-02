package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MemberCountTest {
    @Test fun singularAndPlural() {
        assertEquals("1 member", GroupRules.memberCount(1))
        assertEquals("2 members", GroupRules.memberCount(2))
        assertEquals("0 members", GroupRules.memberCount(0))
    }
}
