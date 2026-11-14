package com.fariin.app.media

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The story export on a real encoder (iOS story-hd-1114). Needs test clips pushed first:
 * adb push sq1440.mp4 / hd720.mp4 /data/local/tmp/ (made with ffmpeg testsrc2).
 */
@RunWith(AndroidJUnit4::class)
class StoryVideoCutOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun size(f: File): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            // Media3 may store a portrait frame landscape with a 90 degree tag: compare it upright.
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            return if (rot == 90 || rot == 270) h to w else w to h
        } finally { r.release() }
    }

    private fun source(name: String): Uri {
        val f = File("/data/local/tmp/$name")
        assumeTrue("push $name first", f.canRead())
        val copy = File(ctx.cacheDir, name)
        f.copyTo(copy, overwrite = true)
        return Uri.fromFile(copy)
    }

    private fun cut(name: String, spec: StoryVideoCut.Edit): Pair<Int, Int> = runBlocking {
        val out = File(ctx.cacheDir, "out-$name")
        assertTrue(StoryVideoCut.edit(ctx, source(name), out, spec))
        size(out)
    }

    @Test fun squareFullHdOnCardStays1080Wide() {
        val w = com.fariin.app.data.StoryCameraRules.storyCanvasWidth(1440)
        assertEquals(1080 to 1920, cut("sq1440.mp4", StoryVideoCut.Edit(null, true, null, w, w * 16 / 9, null)))
    }

    @Test fun squareFullHdTrimKeeps1080() {
        assertEquals(1080 to 1080, cut("sq1440.mp4", StoryVideoCut.Edit(0L..1500L, false, null, 0, 0, null)))
    }

    @Test fun hd720IsNotScaledUp() {
        assertEquals(720 to 1280, cut("hd720.mp4", StoryVideoCut.Edit(0L..1500L, false, null, 0, 0, null)))
    }
}
