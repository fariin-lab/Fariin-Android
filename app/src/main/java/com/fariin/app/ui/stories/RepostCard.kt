package com.fariin.app.ui.stories

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint

/**
 * iOS RepostCard: the original story as a card on a 1080 x 1920 canvas (80% wide, at most 74% tall,
 * centred, corners 4% of its width, a soft drop shadow), a top scrim, and the author's chip (a 64 px
 * face with a white ring, the name in 36 px semibold) at its top-left. Android bakes iOS's editor
 * backdrop in too: the original's own top and bottom colours, darkened to 62%, as a gradient.
 */
object RepostCard {
    const val W = 1080
    const val H = 1920

    fun render(image: Bitmap, name: String, avatar: Bitmap?): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val (top, bottom) = edgeColours(image)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, 0f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP) })

        val a = if (image.height > 0) image.width.toFloat() / image.height else 9f / 16
        var w = W * 0.80f; var h = w / a
        if (h > H * 0.74f) { h = H * 0.74f; w = h * a }
        val card = RectF((W - w) / 2, (H - h) / 2, (W + w) / 2, (H + h) / 2)
        val r = card.width() * 0.04f
        // Shadow: offset 12, blur 40, black 35%.
        c.drawRoundRect(RectF(card.left, card.top + 12, card.right, card.bottom + 12), r, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(89, 0, 0, 0); maskFilter = BlurMaskFilter(40f, BlurMaskFilter.Blur.NORMAL) })
        c.save()
        c.clipPath(Path().apply { addRoundRect(card, r, r, Path.Direction.CW) })
        c.drawBitmap(image, null, card, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        c.drawRect(card.left, card.top, card.right, card.top + 180, Paint().apply {
            shader = LinearGradient(0f, card.top, 0f, card.top + 180, Color.argb(89, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        c.restore()
        drawChip(c, card, name, avatar)
        return out
    }

    private fun drawChip(c: Canvas, card: RectF, name: String, avatar: Bitmap?) {
        val d = 64f; val inset = 26f
        val face = RectF(card.left + inset, card.top + inset, card.left + inset + d, card.top + inset + d)
        c.save()
        c.clipPath(Path().apply { addOval(face, Path.Direction.CW) })
        if (avatar != null) {
            val s = maxOf(d / avatar.width.coerceAtLeast(1), d / avatar.height.coerceAtLeast(1))
            val aw = avatar.width * s; val ah = avatar.height * s
            c.drawBitmap(avatar, null, RectF(face.centerX() - aw / 2, face.centerY() - ah / 2, face.centerX() + aw / 2, face.centerY() + ah / 2),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            c.drawRect(face, Paint().apply { color = Color.rgb(115, 115, 115) })
            val letter = name.trim().take(1).uppercase()
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            c.drawText(letter, face.centerX() - p.measureText(letter) / 2, face.centerY() - (p.descent() + p.ascent()) / 2, p)
        }
        c.restore()
        c.drawOval(RectF(face.left + 1.5f, face.top + 1.5f, face.right - 1.5f, face.bottom - 1.5f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.argb(230, 255, 255, 255) })
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 36f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(6f, 0f, 1f, Color.argb(115, 0, 0, 0))
        }
        val maxW = card.right - face.right - inset - 18
        val text = TextUtils.ellipsize(name.ifEmpty { "Story" }, tp, maxW, TextUtils.TruncateAt.END).toString()
        c.drawText(text, face.right + 18, face.centerY() - (tp.descent() + tp.ascent()) / 2, tp)
    }

    /** iOS edgeColours: the picture averaged to 1 x 2 px, each half darkened to 62%. */
    fun edgeColours(image: Bitmap): Pair<Int, Int> {
        val tiny = Bitmap.createScaledBitmap(image, 1, 2, true)
        fun dark(px: Int) = Color.rgb((Color.red(px) * 0.62f).toInt(), (Color.green(px) * 0.62f).toInt(), (Color.blue(px) * 0.62f).toInt())
        return dark(tiny.getPixel(0, 0)) to dark(tiny.getPixel(0, 1))
    }
}
