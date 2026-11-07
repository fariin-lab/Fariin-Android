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

    @Test fun sizesReadLikeIos() {
        assertEquals("1 KB", MediaDownloads.sizeLabel(200))
        assertEquals("64 KB", MediaDownloads.sizeLabel(65_536))
        assertEquals("0.6 MB", MediaDownloads.sizeLabel(640L * 1024))
        assertEquals("1.2 MB", MediaDownloads.sizeLabel(1_258_291))
        assertEquals("15 MB", MediaDownloads.sizeLabel(15L * 1_048_576))
    }

    @Test fun thePillSaysTheSizeThenBytesOfTheSize() {
        assertEquals(null, MediaDownloads.pillLabel(null, null))
        assertEquals("15 MB", MediaDownloads.pillLabel(null, 15L * 1_048_576))
        assertEquals("1.2 MB / 15 MB", MediaDownloads.pillLabel(1_258_291, 15L * 1_048_576))
    }
}
