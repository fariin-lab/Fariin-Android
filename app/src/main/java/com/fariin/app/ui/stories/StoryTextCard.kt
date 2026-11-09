package com.fariin.app.ui.stories

import com.fariin.app.ui.components.GlassAlert
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.StoryMedia
import com.fariin.app.data.TextStoryRules

/** The four faces (iOS TextStoryStyles.uiFont): bold, black, serif semibold, monospaced semibold. */
internal fun textStoryTypeface(fontIndex: Int): Typeface {
    val (family, weight) = when (TextStoryRules.face(fontIndex)) {
        1 -> Typeface.SANS_SERIF to 900
        2 -> Typeface.SERIF to 600
        3 -> Typeface.MONOSPACE to 600
        else -> Typeface.DEFAULT to 700
    }
    return if (Build.VERSION.SDK_INT >= 28) Typeface.create(family, weight, false) else Typeface.create(family, Typeface.BOLD)
}

private fun layoutOf(text: String, widthPx: Int, tf: Typeface, sizePx: Float, color: Int = 0): StaticLayout {
    val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = sizePx; this.color = color }
    return StaticLayout.Builder.obtain(text, 0, text.length, p, widthPx.coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(true).build()
}

/** iOS fittedSize, measured with the same typeface the words are drawn in. */
internal fun fittedTextStorySize(text: String, boxWidthPx: Float, fontIndex: Int): Float {
    val tf = textStoryTypeface(fontIndex)
    return TextStoryRules.fittedSize(text, boxWidthPx) { s -> layoutOf(text, boxWidthPx.toInt(), tf, s).height.toFloat() }
}

/**
 * iOS renderTextStory: the words on their colour, 1080 x 2700 drawn at 1.5x, URLs shortened, the
 * same fit as the card at the picture's own width. Posted as a plain photo story. Off the main thread.
 *
 * With a [link] (the page's card, fetched for the first address in the words) the card sits on top
 * and the words under it, centred as one group, and where the card landed goes with the picture as
 * its tap area, so a viewer on either phone can open it.
 */
internal fun renderTextStory(text: String, styleIndex: Int, fontIndex: Int,
                             link: com.fariin.app.data.LinkPreviews.Draft? = null,
                             linkStyle: TextStoryRules.LinkStyle = TextStoryRules.LinkStyle.COMPACT): StoryMedia.Prepared? {
    val t = TextStoryRules.shortenedUrls(text).trim()
    if (t.isEmpty()) return null
    val k = TextStoryRules.RENDER_SCALE
    val w = (TextStoryRules.RENDER_W * k).toInt()
    val h = (TextStoryRules.RENDER_H * k).toInt()
    val pad = TextStoryRules.RENDER_PAD * k
    val box = w - pad * 2
    val style = TextStoryRules.style(styleIndex)
    val size = fittedTextStorySize(t, box, fontIndex)
    val ink = if (style.inkIsWhite) android.graphics.Color.WHITE else android.graphics.Color.BLACK
    val layout = layoutOf(t, box.toInt(), textStoryTypeface(fontIndex), size, ink)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.drawColor(style.bg.toInt())
    if (link == null) {
        c.translate(pad, (h - layout.height) / 2f)
        layout.draw(c)
        return StoryMedia.fromRendered(bmp, TextStoryRules.RENDER_QUALITY)
    }
    // In the picture's own points (1080 x 2700), as iOS lays it out; drawn at k.
    val cardW = TextStoryRules.RENDER_W * TextStoryRules.LINK_CARD_WIDTH
    val cardH = storyLinkCardHeight(link, linkStyle, cardW)
    // The tall card already shows the link in its capsule: words that are only the link are not
    // drawn a second time (iOS isBareLink).
    val hideWords = linkStyle == TextStoryRules.LinkStyle.POSTER && TextStoryRules.isBareLink(text)
    val (wordsH, centreY) = TextStoryRules.placeLink(if (hideWords) 0f else layout.height / k * 1.06f, cardH,
        TextStoryRules.RENDER_W, TextStoryRules.RENDER_H)
    val cardTop = centreY * h - cardH * k / 2
    val cardLeft = (w - cardW * k) / 2
    drawStoryLinkCard(c, link, linkStyle, cardLeft, cardTop, cardW * k, cardH * k)
    if (!hideWords) {
        // The words centred in what the layout gave them, under the card and the gap.
        val wordsTop = cardTop + cardH * k + TextStoryRules.RENDER_W * TextStoryRules.LINK_GAP * k
        c.save()
        c.clipRect(0f, wordsTop, w.toFloat(), wordsTop + wordsH * k)
        c.translate(pad, wordsTop + (wordsH * k - layout.height) / 2f)
        layout.draw(c)
        c.restore()
    }
    // The tap area is the whole card, the tall card's capsule included.
    val tap = TextStoryRules.linkTap(link.url, centreY, cardH)
    return StoryMedia.fromRendered(bmp, TextStoryRules.RENDER_QUALITY, listOf(tap))
}

/**
 * iOS StoryTextLinkPreview, compact style: a 0.97 white card, 18 corners at a 300 wide card (12 when
 * the page gave back only its host), a square of the page's picture on the left, then the title (15
 * semibold, two lines), the description (12.5, two lines) and the host (12.5, grey), 2 apart, 12 in
 * from the sides, centred top to bottom. Black on it whatever the story's colour. Our channel, group
 * and person links add a Join or Chat pill on the right (StoryLinkCardRules).
 *
 * LARGE puts the picture across the top (120 at the reference) with the same words under it;
 * POSTER is the tall card ([drawPosterCard]).
 */
private fun drawStoryLinkCard(c: android.graphics.Canvas, d: com.fariin.app.data.LinkPreviews.Draft,
                              style: TextStoryRules.LinkStyle,
                              left: Float, top: Float, cw: Float, ch: Float) {
    if (style == TextStoryRules.LinkStyle.POSTER) { drawPosterCard(c, d, left, top, cw); return }
    val large = style == TextStoryRules.LinkStyle.LARGE
    val u = cw / TextStoryRules.LINK_REF_W
    val bare = d.title.isEmpty() && d.desc.isEmpty()
    val pic = if (bare) null else d.jpeg?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
    val radius = (if (bare) 12f else 18f) * u
    val shape = android.graphics.Path().apply {
        addRoundRect(android.graphics.RectF(left, top, left + cw, top + ch), radius, radius, android.graphics.Path.Direction.CW)
    }
    c.save()
    c.clipPath(shape)
    c.drawColor(android.graphics.Color.rgb(247, 247, 247))
    var textLeft = left
    // The words' block: the whole card on COMPACT, the part under the banner on LARGE.
    var blockTop = top
    var blockH = ch
    if (pic != null) {
        if (large) {
            val bannerH = TextStoryRules.LINK_BANNER_H * u
            drawFilled(c, pic, android.graphics.RectF(left, top, left + cw, top + bannerH))
            blockTop = top + bannerH
            blockH = ch - bannerH
        } else {
            // The square, filled: the picture's middle, cropped to the card's height on each side.
            drawFilled(c, pic, android.graphics.RectF(left, top, left + ch, top + ch))
            textLeft = left + ch
        }
        pic.recycle()
    }
    fun paint(sizePt: Float, bold: Boolean, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePt * u; this.color = color
        typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, if (bold) 600 else 400, false)
            else if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
    // Join on a channel's or group's link, Chat on a person's (owner 2026-11-06): a blue pill on the
    // right, 13 semibold white, 14 by 7 inside, 12 from the edge, in the middle of the card's height.
    // It is drawn into the picture, so the posted story shows it too.
    val pill = com.fariin.app.data.StoryLinkCardRules.pill(d.url, d.desc)?.label
    val pillPaint = paint(13f, true, android.graphics.Color.WHITE)
    val pillW = pill?.let { pillPaint.measureText(it) + 28f * u } ?: 0f
    val pillSpace = if (pill != null) pillW + 12f * u else 0f
    val textW = (left + cw - textLeft - 24f * u - pillSpace).toInt().coerceAtLeast(1)
    fun line(s: String, p: TextPaint, lines: Int): StaticLayout = StaticLayout.Builder.obtain(s, 0, s.length, p, textW)
        .setMaxLines(lines).setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false).build()
    val lines = buildList {
        if (d.title.isNotEmpty()) add(line(d.title, paint(15f, true, android.graphics.Color.BLACK), 2))
        if (d.desc.isNotEmpty()) add(line(d.desc, paint(12.5f, false, android.graphics.Color.BLACK), 2))
        add(line(d.host, paint(12.5f, false, android.graphics.Color.argb(153, 60, 60, 67)), 1))
    }
    // The words CENTRED in the card's fixed height (owner 2026-11-06: they sat at the top, beside a
    // picture centred on the whole card). The card's size does not change.
    val wordsH = lines.sumOf { it.height.toDouble() }.toFloat() + 2f * u * (lines.size - 1)
    var y = blockTop + maxOf(7f * u, (blockH - wordsH) / 2f)
    lines.forEach { l -> c.save(); c.translate(textLeft + 12f * u, y); l.draw(c); c.restore(); y += l.height + 2f * u }
    if (pill != null) {
        val fm = pillPaint.fontMetrics
        val pillH = (fm.descent - fm.ascent) + 14f * u
        val pr = android.graphics.RectF(left + cw - 12f * u - pillW, blockTop + (blockH - pillH) / 2f,
            left + cw - 12f * u, blockTop + (blockH + pillH) / 2f)
        c.drawRoundRect(pr, pillH / 2f, pillH / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(0, 122, 255) })
        c.drawText(pill, pr.left + 14f * u, pr.centerY() - (fm.ascent + fm.descent) / 2f, pillPaint)
    }
    c.restore()
}

/** The card's height for [style], from the same numbers the drawing uses (iOS StoryTextLinkPreview.height). */
internal fun storyLinkCardHeight(d: com.fariin.app.data.LinkPreviews.Draft, style: TextStoryRules.LinkStyle, cardW: Float): Float {
    val bare = d.title.isEmpty() && d.desc.isEmpty()
    val (pw, ph) = d.picSize
    return TextStoryRules.linkCardHeight(cardW, style, d.jpeg != null && !bare, pw, ph, d.isVideo, d.title.isNotEmpty())
}

/** The style a fresh link starts in: the tall card for a video or a portrait picture. */
internal fun preferredStoryLinkStyle(d: com.fariin.app.data.LinkPreviews.Draft): TextStoryRules.LinkStyle {
    val own = com.fariin.app.data.StoryLinkCardRules.opensInApp(d.url) || com.fariin.app.data.LinkPreviews.isOwnLink(d.url)
    val (pw, ph) = d.picSize
    return TextStoryRules.preferredLinkStyle(own, d.isVideo, pw, ph)
}

/** [pic] filling [dst], its middle kept (aspect fill). */
private fun drawFilled(c: android.graphics.Canvas, pic: Bitmap, dst: android.graphics.RectF) {
    val want = dst.width() / dst.height()
    var sw = pic.width.toFloat()
    var sh = sw / want
    if (sh > pic.height) { sh = pic.height.toFloat(); sw = sh * want }
    val sx = ((pic.width - sw) / 2).toInt()
    val sy = ((pic.height - sh) / 2).toInt()
    c.drawBitmap(pic, android.graphics.Rect(sx, sy, sx + sw.toInt(), sy + sh.toInt()), dst,
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
}

/**
 * iOS StoryTextLinkPreview poster: THE TALL CARD FOR REELS AND SHORT VIDEOS. A white card with 22
 * corners at the 300 reference; the picture across it at its own portrait shape (4:5 to 2:3), a
 * dark round play button in the middle on a video; then the title (17 semibold, two lines) and the
 * full host (14, grey) with the site's icon on the right (a link glyph without one), 14 in from the
 * sides. Under the card, 10 apart, the link itself in a 30 tall light capsule.
 */
private fun drawPosterCard(c: android.graphics.Canvas, d: com.fariin.app.data.LinkPreviews.Draft,
                           left: Float, top: Float, cw: Float) {
    val u = cw / TextStoryRules.LINK_REF_W
    val (pw, ph) = d.picSize
    val picH = TextStoryRules.posterPictureHeight(cw, pw, ph, d.isVideo)
    val hasTitle = d.title.isNotEmpty()
    val textH = (if (hasTitle) TextStoryRules.POSTER_TEXT_H else TextStoryRules.POSTER_HOST_ONLY_H) * u
    val cardBottom = top + picH + textH
    val anti = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    c.save()
    c.clipPath(android.graphics.Path().apply {
        addRoundRect(android.graphics.RectF(left, top, left + cw, cardBottom), 22f * u, 22f * u, android.graphics.Path.Direction.CW)
    })
    c.drawColor(android.graphics.Color.WHITE)
    if (picH > 0f) {
        val area = android.graphics.RectF(left, top, left + cw, top + picH)
        val pic = d.jpeg?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (pic != null) { drawFilled(c, pic, area); pic.recycle() }
        else c.drawRect(area, Paint().apply { color = android.graphics.Color.rgb(235, 235, 235) })
        if (d.isVideo) {
            val cx = area.centerX(); val cy = area.centerY()
            c.drawCircle(cx, cy, 29f * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(115, 0, 0, 0) })
            val t = 22f * u
            val ox = 2f * u
            c.drawPath(android.graphics.Path().apply {
                moveTo(cx - t * 0.38f + ox, cy - t / 2)
                lineTo(cx - t * 0.38f + ox, cy + t / 2)
                lineTo(cx + t * 0.55f + ox, cy)
                close()
            }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        }
    }
    fun paint(sizePt: Float, weight: Int, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePt * u; this.color = color
        typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, weight, false)
            else if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
    val side = 14f * u
    val iconS = 20f * u
    val innerW = cw - side * 2
    val title = if (hasTitle) StaticLayout.Builder.obtain(d.title, 0, d.title.length, paint(17f, 600, android.graphics.Color.BLACK),
        innerW.toInt().coerceAtLeast(1)).setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END)
        .setIncludePad(false).build() else null
    // Title, 6, the 20 tall host row: centred in the text block.
    val contentH = (title?.height?.toFloat()?.plus(6f * u) ?: 0f) + iconS
    var y = top + picH + (textH - contentH) / 2f
    title?.let { c.save(); c.translate(left + side, y); it.draw(c); c.restore(); y += it.height + 6f * u }
    // The host row: the FULL host on the left, the site's icon on the right.
    val grey = android.graphics.Color.rgb(115, 115, 115)
    val hostPaint = paint(14f, 400, grey)
    val host = runCatching { java.net.URI(d.url).host }.getOrNull() ?: d.host
    val hostText = android.text.TextUtils.ellipsize(host, hostPaint, innerW - iconS - 8f * u, android.text.TextUtils.TruncateAt.END).toString()
    val fm = hostPaint.fontMetrics
    c.drawText(hostText, left + side, y + iconS / 2f - (fm.ascent + fm.descent) / 2f, hostPaint)
    val iconRect = android.graphics.RectF(left + cw - side - iconS, y, left + cw - side, y + iconS)
    val icon = d.icon?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
    if (icon != null) {
        c.save()
        c.clipPath(android.graphics.Path().apply { addRoundRect(iconRect, 5f * u, 5f * u, android.graphics.Path.Direction.CW) })
        drawFilled(c, icon, iconRect)
        c.restore()
        icon.recycle()
    } else drawLinkGlyph(c, iconRect, grey, u)
    c.restore()

    // The link in its capsule under the card.
    val capH = TextStoryRules.POSTER_CAPSULE_H * u
    val capTop = cardBottom + TextStoryRules.POSTER_GAP * u
    val capPaint = paint(14f, 500, android.graphics.Color.argb(204, 0, 0, 0))
    val pad = 12f * u
    val label = android.text.TextUtils.ellipsize(TextStoryRules.shortened(d.url), capPaint, cw - pad * 2,
        android.text.TextUtils.TruncateAt.END).toString()
    val capW = capPaint.measureText(label) + pad * 2
    val cap = android.graphics.RectF(left + (cw - capW) / 2f, capTop, left + (cw + capW) / 2f, capTop + capH)
    c.drawRoundRect(cap, capH / 2f, capH / 2f, Paint(anti).apply { color = android.graphics.Color.argb(153, 255, 255, 255) })
    val cfm = capPaint.fontMetrics
    c.drawText(label, cap.left + pad, cap.centerY() - (cfm.ascent + cfm.descent) / 2f, capPaint)
}

/** Two linked rounded links, turned 45 degrees: the stand-in when a site has no icon. */
private fun drawLinkGlyph(c: android.graphics.Canvas, r: android.graphics.RectF, color: Int, u: Float) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f * u; this.color = color }
    val cx = r.centerX(); val cy = r.centerY()
    val lw = 9f * u; val lh = 5f * u
    c.save()
    c.rotate(-45f, cx, cy)
    c.drawRoundRect(android.graphics.RectF(cx - lw + 1.5f * u, cy - lh / 2, cx + 1.5f * u, cy + lh / 2), lh / 2, lh / 2, p)
    c.drawRoundRect(android.graphics.RectF(cx - 1.5f * u, cy - lh / 2, cx + lw - 1.5f * u, cy + lh / 2), lh / 2, lh / 2, p)
    c.restore()
}

/**
 * The TEXT half of the story camera (iOS StoryTextCard): a coloured card, the X top-left, the words
 * in the middle, "Aa" and the colour ring bottom-left, and a tick bottom-right while the keyboard is
 * up. A tap on the card raises or drops the keyboard; arriving never raises it.
 *
 * [bottomReserve]: what the page's bottom bar covers while the keyboard is down.
 */
@Composable
internal fun StoryTextCard(
    text: String,
    onText: (String) -> Unit,
    styleIndex: Int,
    onNextStyle: () -> Unit,
    fontIndex: Int,
    onNextFont: () -> Unit,
    onTyping: (Boolean) -> Unit,
    bottomReserve: Dp,
    active: Boolean,
    onClose: () -> Unit,
    linkStyle: TextStoryRules.LinkStyle,
    onLinkStyle: (TextStoryRules.LinkStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }
    var cardWidth by remember { mutableIntStateOf(0) }
    val style = TextStoryRules.style(styleIndex)
    val ink = if (style.inkIsWhite) Color.White else Color.Black
    val bg by animateColorAsState(Color(style.bg), tween(300, easing = FastOutSlowInEasing), label = "textStoryBg")

    val imePx = WindowInsets.ime.getBottom(density)
    val ime = with(density) { imePx.toDp() }
    val keyboardUp = imePx > 0

    // Fitted against a box that never changes (the card less 28 dp a side), so opening the
    // keyboard cannot resize the words under his hand.
    val sidePx = with(density) { TextStoryRules.CARD_SIDE_DP.dp.toPx() }
    val boxPx = (cardWidth - sidePx * 2).coerceAtLeast(1f)
    val face = remember(fontIndex) { FontFamily(textStoryTypeface(fontIndex)) }
    val sizePx = remember(text, boxPx, fontIndex) { fittedTextStorySize(text, boxPx, fontIndex) }
    val fontSize = with(density) { sizePx.toSp() }
    val textStyle = TextStyle(color = ink, fontSize = fontSize, fontFamily = face, textAlign = TextAlign.Center)

    // iOS StoryTextComposer: the link in the words shows its card live, above them, the same card
    // the posted picture gets (drawn by the same code), so what he sees is what is posted. Fetched
    // 400 ms after typing stops; the fetch is cached, so the post step finds it ready.
    val linkUrl = remember(text) { TextStoryRules.firstLink(text) }
    var linkDraft by remember { mutableStateOf<com.fariin.app.data.LinkPreviews.Draft?>(null) }
    LaunchedEffect(linkUrl) {
        if (linkUrl == null) { linkDraft = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(400)
        val d = com.fariin.app.data.LinkPreviews.storyDraftWithin(linkUrl, 8_000)
        // A fresh link starts in its preferred style: the tall card for a video or a portrait picture.
        if (d != null && linkDraft?.url != d.url) onLinkStyle(preferredStoryLinkStyle(d))
        linkDraft = d
    }
    val hideWords = linkDraft?.takeIf { it.url == linkUrl } != null &&
        linkStyle == TextStoryRules.LinkStyle.POSTER && TextStoryRules.isBareLink(text)

    fun setFocus(on: Boolean) {
        if (on) { requester.requestFocus(); keyboard?.show() } else { focus.clearFocus(force = true); keyboard?.hide() }
    }
    fun close() { if (text.isBlank()) onClose() else { setFocus(false); askDiscard = true } }
    LaunchedEffect(active) { if (!active && focused) setFocus(false) }

    BackHandler(enabled = active) { close() }

    Box(modifier.fillMaxSize().background(bg).onSizeChanged { cardWidth = it.width }) {
        // The whole card is the one gesture: tap to type, tap again to stop.
        // A real tap only: a flick between CAMERA and TEXT travels across the card and must not
        // raise the keyboard on the way.
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val slop = viewConfiguration.touchSlop
                var moved = false
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if ((c.position - down.position).getDistance() > slop) moved = true
                    if (!c.pressed) break
                }
                if (!moved) setFocus(!focused)
            }
        })

        // The words' box is what the keyboard leaves (plus 68 dp for the button row above it).
        Box(
            Modifier.fillMaxSize().statusBarsPadding()
                // One continuous value as the keyboard slides (owner 2026-11-09: closing it, the words jumped
                // when the keyboard reached zero and the gap switched to the resting one in one frame).
                .padding(top = 6.dp, bottom = maxOf(ime + 68.dp, bottomReserve)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            linkDraft?.takeIf { it.url == linkUrl }?.let { d ->
                val cardW = with(density) { (cardWidth * TextStoryRules.LINK_CARD_WIDTH).toDp() }
                val cardH = with(density) { storyLinkCardHeight(d, linkStyle, cardWidth * TextStoryRules.LINK_CARD_WIDTH).toDp() }
                // A tap on the card moves to the next style (iOS: the card's only control).
                androidx.compose.foundation.Canvas(
                    Modifier.size(cardW, cardH)
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null) { onLinkStyle(linkStyle.next()) }
                        .semantics { contentDescription = "Change link card" },
                ) {
                    drawIntoCanvas { drawStoryLinkCard(it.nativeCanvas, d, linkStyle, 0f, 0f, size.width, size.height) }
                }
                Spacer(Modifier.height(with(density) { (cardWidth * TextStoryRules.LINK_GAP).toDp() }))
            }
            // Words that are only the link sit under the tall card's own capsule: not drawn twice
            // (still there to type in, as on iOS).
            Box(if (hideWords) Modifier.height(0.dp).then(Modifier.alpha(0f)) else Modifier, contentAlignment = Alignment.Center) {
            if (text.isEmpty()) {
                Text("Type something…", style = textStyle.copy(color = ink.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = TextStoryRules.CARD_SIDE_DP.dp))
            }
            BasicTextField(
                text, { onText(it.take(TextStoryRules.CHAR_LIMIT)) },
                textStyle = textStyle,
                cursorBrush = SolidColor(ink),
                modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(horizontal = TextStoryRules.CARD_SIDE_DP.dp)
                    .focusRequester(requester)
                    .onFocusChanged { focused = it.isFocused; onTyping(it.isFocused) },
            )
            }
            }
        }

        GlassCircle(ink, "Close", Modifier.statusBarsPadding().padding(14.dp), onClick = { close() }) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), contentDescription = null, tint = ink)
        }

        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 14.dp)
                .padding(bottom = 14.dp + maxOf(ime, bottomReserve)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassCircle(ink, "Change font", onClick = onNextFont) {
                    Text("Aa", color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                GlassCircle(ink, "Change colour", onClick = onNextStyle) {
                    Box(Modifier.size(21.dp).border(2.dp, ink, CircleShape))
                }
            }
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(focused, enter = fadeIn(), exit = fadeOut()) {
                GlassCircle(ink, "Done", onClick = { setFocus(false) }) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), contentDescription = null, tint = ink)
                }
            }
        }
    }

    if (askDiscard) GlassAlert(
        onDismissRequest = { askDiscard = false; setFocus(true) },
        title = { Text("Discard this status?") },
        confirmButton = { TextButton({ askDiscard = false; onClose() }) { Text("Discard", color = Color(0xFFE5484D)) } },
        dismissButton = { TextButton({ askDiscard = false; setFocus(true) }) { Text("Cancel") } },
    )
}

/** The card's controls: one 46 dp round shape, a light glass over the colour. */
@Composable
private fun GlassCircle(ink: Color, label: String, modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier.size(46.dp).clip(CircleShape)
            .background(if (ink == Color.White) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.3f))
            .clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}
