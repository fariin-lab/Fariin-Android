package com.fariin.app.ui.thread

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeReplyMathTest {
    @Test fun oneToOneThenAQuarterPastTheThreshold() {
        assertEquals(30f, SwipeReplyMath.eased(30f, 55f))
        assertEquals(55f, SwipeReplyMath.eased(55f, 55f))
        assertEquals(65f, SwipeReplyMath.eased(95f, 55f))
        assertEquals(305f, SwipeReplyMath.eased(1055f, 55f))   // no cap
    }

    @Test fun arrowFadesAndGrowsFromSixTenths() {
        assertEquals(0.6f, SwipeReplyMath.arrowScale(SwipeReplyMath.progress(0f, 55f)))
        assertEquals(1f, SwipeReplyMath.arrowScale(SwipeReplyMath.progress(80f, 55f)))
        assertEquals(0.5f, SwipeReplyMath.progress(27.5f, 55f))
    }
}
