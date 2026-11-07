package com.fariin.app.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure rules behind the photo and video bubbles matching iOS. */
class MediaParityTest {
    @Test fun aQuarterTurnSwapsTheSides() {
        assertEquals(LocalPreview.Shape(3000, 4000), LocalPreview.turned(4000, 3000, 90))
        assertEquals(LocalPreview.Shape(3000, 4000), LocalPreview.turned(4000, 3000, 270))
        assertEquals(LocalPreview.Shape(3000, 4000), LocalPreview.turned(4000, 3000, -90))
    }

    @Test fun noTurnOrAHalfTurnKeepsTheSides() {
        assertEquals(LocalPreview.Shape(4000, 3000), LocalPreview.turned(4000, 3000, 0))
        assertEquals(LocalPreview.Shape(4000, 3000), LocalPreview.turned(4000, 3000, 180))
    }
}
