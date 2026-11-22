package com.fariin.app.data

import com.fariin.app.data.OpenGate.Doc
import com.fariin.app.data.OpenGate.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenGateTest {
    @Test fun publicChannelINeitherJoinNorFollowIsAVisit() {
        assertEquals(Result.VISITOR, OpenGate.decide(Doc.DENIED_PUBLIC, postGone = false))
    }

    @Test fun aVisitWhosePostIsGoneSaysSo() {
        assertEquals(Result.POST_GONE, OpenGate.decide(Doc.DENIED_PUBLIC, postGone = true))
    }

    @Test fun refusedWithNoPublicPageStaysRefused() {
        assertEquals(Result.REFUSED, OpenGate.decide(Doc.DENIED, postGone = false))
        assertEquals(Result.REFUSED, OpenGate.decide(Doc.NOT_MINE, postGone = false))
    }

    @Test fun myChatsOpen() {
        assertEquals(Result.OPEN, OpenGate.decide(Doc.KNOWN, postGone = false))
        assertEquals(Result.OPEN, OpenGate.decide(Doc.OPENABLE, postGone = false))
        assertEquals(Result.POST_GONE, OpenGate.decide(Doc.KNOWN, postGone = true))
    }

    @Test fun missingOrArchivedChannelIsGone() {
        assertEquals(Result.CHAT_GONE, OpenGate.decide(Doc.MISSING, postGone = false))
        assertEquals(Result.CHAT_GONE, OpenGate.decide(Doc.ARCHIVED, postGone = true))
    }

    @Test fun aFailedReadDecidesNothing() {
        assertEquals(Result.OPEN, OpenGate.decide(Doc.FAILED, postGone = false))
    }

    @Test fun aVisitHasNoNotice() {
        assertEquals("", OpenGate.notice(Result.VISITOR))
    }
}
