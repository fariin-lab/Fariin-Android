package com.fariin.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The photo send preparation on a real Android decoder: size, quality, previews. */
@RunWith(AndroidJUnit4::class)
class PhotoPrepOnDeviceTest {
    private fun jpeg(w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h step 8) for (x in 0 until w step 8) b.setPixel(x, y, Color.rgb(x % 255, y % 255, 128))
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    @Test fun bigPhotoIsScaledTo1280AndFits() {
        val p = PhotoPrep.fromBytes(jpeg(4000, 3000))!!
        assertEquals(1280, p.width); assertEquals(960, p.height)
        assertTrue(p.jpeg.size <= MediaRules.PhotoQuality.STANDARD.maxBytes)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(p.jpeg, 0, p.jpeg.size, o)
        assertEquals(1280, o.outWidth)
        assertEquals(28, p.blurhash!!.length)
        val thumb = Base64.decode(p.thumbB64, Base64.DEFAULT)
        val t = BitmapFactory.decodeByteArray(thumb, 0, thumb.size)
        assertNotNull(t); assertEquals(40, t.width); assertEquals(30, t.height)
    }

    @Test fun smallPhotoIsNotEnlarged() {
        val p = PhotoPrep.fromBytes(jpeg(300, 200))!!
        assertEquals(300, p.width); assertEquals(200, p.height)
    }
}
