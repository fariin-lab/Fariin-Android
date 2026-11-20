package com.fariin.app.media

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A chat video's prepare (the Transformer export) on a real phone or emulator, for the older
 * Android versions (Oppo 8.1, 2026-11-20: the export threw NoClassDefFoundError there).
 * Needs a clip pushed first: adb push vtest20.mp4 /data/local/tmp/ (ffmpeg testsrc2, 1280x720, 20 s).
 */
@RunWith(AndroidJUnit4::class)
class VideoPrepOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun chatVideoPrepares() = runBlocking {
        val f = File("/data/local/tmp/vtest20.mp4")
        assumeTrue("push vtest20.mp4 first", f.canRead())
        val copy = File(ctx.cacheDir, "vtest20.mp4")
        f.copyTo(copy, overwrite = true)
        val out = File(ctx.cacheDir, "vp-out").apply { mkdirs() }
        assertNotNull(VideoPrep.fromUri(ctx, Uri.fromFile(copy), out, "t1"))
    }
}
