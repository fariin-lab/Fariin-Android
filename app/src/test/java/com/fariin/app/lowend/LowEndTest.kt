package com.fariin.app.lowend

import com.fariin.app.data.ConnectionStatus
import com.fariin.app.data.ConnectionStatus.State
import com.fariin.app.media.AutoDownload
import com.fariin.app.media.BitmapDecode
import com.fariin.app.media.LowEnd
import com.fariin.app.media.MediaRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cheap phones and slow networks: the pure rules behind decoding, caching, downloads and the header. */
class LowEndTest {
    @Test fun sampleKeepsShortSideAtTarget() {
        assertEquals(2, MediaRules.sampleFor(1280, 960, 400))
        assertEquals(1, MediaRules.sampleFor(1280, 960, 600))
        assertEquals(2, MediaRules.sampleFor(4000, 3000, 1000))
        assertEquals(4, MediaRules.sampleFor(4000, 3000, 600))
        assertEquals(1, MediaRules.sampleFor(0, 0, 100))
    }

    @Test fun sampleCapsTheLongSide() {
        // A 12 MP photo in the viewer of a low-RAM phone: never 4000 px wide in memory.
        assertEquals(4, MediaRules.sampleFor(4000, 3000, 1440, 1440))
        assertEquals(1, MediaRules.sampleFor(1280, 960, 1440, 1440))
        assertEquals(2, MediaRules.sampleFor(3000, 200, 100, 2048))
    }

    @Test fun legacySampleSize() {
        assertEquals(1, BitmapDecode.sampleSizeFor(1280, 960, 1280))
        assertEquals(2, BitmapDecode.sampleSizeFor(4032, 3024, 1280))
        assertEquals(8, BitmapDecode.sampleSizeFor(4032, 3024, 300))
        assertEquals(1, BitmapDecode.sampleSizeFor(4032, 3024, 0))
    }

    @Test fun exifOrientation() {
        assertEquals(90 to false, BitmapDecode.orientation(6))   // ROTATE_90
        assertEquals(270 to false, BitmapDecode.orientation(8))  // ROTATE_270
        assertEquals(0 to true, BitmapDecode.orientation(2))     // FLIP_HORIZONTAL
        assertEquals(0 to false, BitmapDecode.orientation(1))
        assertEquals(0 to false, BitmapDecode.orientation(0))
    }

    @Test fun lowRamCacheAndViewer() {
        assertEquals(0.15, LowEnd.memoryCachePercent(true), 0.0)
        assertEquals(0.25, LowEnd.memoryCachePercent(false), 0.0)
        assertEquals(1440, LowEnd.viewerSide(1708, true))
        assertEquals(1708, LowEnd.viewerSide(1708, false))
        assertEquals(2048, LowEnd.viewerSide(4800, false))
        assertEquals(480, LowEnd.viewerSide(100, true))
    }

    @Test fun autoDownloadOnCellular() {
        assertFalse(AutoDownload.allowed("wifi", onWifi = false))
        assertTrue(AutoDownload.allowed("wifi", onWifi = true))
        assertTrue(AutoDownload.allowed("always", onWifi = false))
        assertFalse(AutoDownload.allowed("never", onWifi = true))
    }

    @Test fun connectionHeader() {
        assertNull(ConnectionStatus.label(State.ONLINE))
        assertEquals("Waiting for network", ConnectionStatus.label(State.WAITING_FOR_NETWORK))
        assertEquals("Connecting", ConnectionStatus.label(State.CONNECTING))
        // No route wins over everything, at once.
        assertEquals(State.WAITING_FOR_NETWORK, ConnectionStatus.next(State.ONLINE, hasRoute = false, serverAnswered = true))
        // The route comes back: connecting until the server answers.
        assertEquals(State.CONNECTING, ConnectionStatus.next(State.WAITING_FOR_NETWORK, true, null))
        assertEquals(State.ONLINE, ConnectionStatus.next(State.CONNECTING, true, true))
        // A route event on a healthy app says nothing new (no flash of "Connecting").
        assertEquals(State.ONLINE, ConnectionStatus.next(State.ONLINE, true, null))
        assertEquals(State.CONNECTING, ConnectionStatus.next(State.ONLINE, true, false))
    }
}
