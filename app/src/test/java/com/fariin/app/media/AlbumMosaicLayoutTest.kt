package com.fariin.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumMosaicLayoutTest {
    private fun ok(r: AlbumMosaicLayout.Result, n: Int, width: Float) {
        assertEquals(n, r.tiles.size)
        assertEquals(width, r.width, 1.5f)
        r.tiles.forEach { assertTrue("tile inside", it.x + it.w <= r.width + 1.5f && it.w > 20 && it.h > 20) }
    }

    @Test fun twoTallScreenshotsSitSideBySideAndKeepShape() {
        val r = AlbumMosaicLayout.plan(listOf(0.46f, 0.46f), 300f)
        ok(r, 2, 300f)
        assertEquals(r.tiles[0].y, r.tiles[1].y, 0.5f)          // side by side
        assertTrue(r.tiles[0].h > r.tiles[0].w * 1.3f)         // tall, not cropped square
        assertTrue(r.height <= 450.5f)                          // capped at 1.5 widths
    }

    @Test fun twoWideStack() {
        val r = AlbumMosaicLayout.plan(listOf(1.8f, 1.8f), 300f)
        ok(r, 2, 300f)
        assertTrue(r.tiles[1].y > r.tiles[0].y)
    }

    @Test fun everyCountFits() {
        for (n in 1..10) for (a in listOf(0.46f, 1f, 1.78f)) ok(AlbumMosaicLayout.plan(List(n) { a }, 300f), n, 300f)
        ok(AlbumMosaicLayout.plan(listOf(0.5f, 1.7f, 1f, 3f, 0.6f), 300f), 5, 300f)
    }
}
