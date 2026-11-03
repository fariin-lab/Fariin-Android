package com.fariin.app.data

import java.net.URI

/**
 * A text story (iOS StoryText / TextStoryStyles / renderTextStory), kept pure so the rules are
 * tested off the device.
 *
 * A text story is not a new kind of story: the words are drawn onto their colour and posted as an
 * ordinary photo story (type "image"), exactly as iOS posts it, so every build on either phone
 * shows it. The picture is 1080 x 2700 drawn at 1.5x (1620 x 4050), JPEG 95: taller than any
 * screen so it fills a viewer edge to edge; the words stay in the middle, where every phone shows them.
 *
 * Every size is a RATIO of the words' box width, so the card he types on and the posted picture
 * pick the same size for the same words.
 */
object TextStoryRules {
    /** iOS StoryText.charLimit. */
    const val CHAR_LIMIT = 720

    /** One background and the ink that reads on it (ARGB). The first is the one the card opens on. */
    data class Style(val bg: Long, val inkIsWhite: Boolean)

    val STYLES = listOf(
        Style(0xFF8B87D8, false),
        Style(0xFFF4D06F, false),
        Style(0xFF7ED8A5, false),
        Style(0xFFF08A8A, false),
        Style(0xFF1E2A44, true),
        Style(0xFF0E3B36, true),
        Style(0xFF5B2333, true),
        Style(0xFF111113, true),
    )

    fun style(index: Int): Style = STYLES[Math.floorMod(index, STYLES.size)]

    /** Four faces, cycled by the Aa button: 0 bold, 1 black (rounded on iOS), 2 serif, 3 monospaced. */
    const val FACES = 4
    fun face(index: Int): Int = Math.floorMod(index, FACES)

    /** The words' box is width x width * [BOX_ASPECT], centred. */
    const val BOX_ASPECT = 1.6f
    /** ~30 dp on a phone-wide card. */
    const val MAX_SIZE_RATIO = 0.081f
    /** ~12 dp; 720 characters land around 15, so this is a backstop. */
    const val MIN_SIZE_RATIO = 0.032f
    /** The card keeps this much room down each side of the words (iOS 28 pt each side). */
    const val CARD_SIDE_DP = 28f

    /** The posted picture: 1080 x 2700 at 1.5x, 80 of side margin, JPEG 95. */
    const val RENDER_W = 1080f
    const val RENDER_H = RENDER_W * 2.5f
    const val RENDER_PAD = 80f
    const val RENDER_SCALE = 1.5f
    const val RENDER_QUALITY = 95

    /**
     * The biggest size these words still fit at (iOS fittedSize): steps down from the top size by 3%
     * (at least 0.5) until [measureHeight] of the paragraph at that size is within the box.
     * [measureHeight] answers for the given font size at [boxWidth].
     */
    fun fittedSize(text: String, boxWidth: Float, measureHeight: (Float) -> Float): Float {
        val maxSize = boxWidth * MAX_SIZE_RATIO
        if (boxWidth <= 1f || text.isEmpty()) return maxSize
        val minSize = boxWidth * MIN_SIZE_RATIO
        val maxHeight = boxWidth * BOX_ASPECT * 0.98f
        val step = maxOf(0.5f, maxSize * 0.03f)
        var size = maxSize
        while (size > minSize) {
            if (measureHeight(size) <= maxHeight) return size
            size -= step
        }
        return minSize
    }

    /**
     * The posted picture shows a short address, not the one typed (iOS StoryText.shortenedURLs):
     * no scheme, no query (a "?..." marks that there was one), the host and path whole, cut at 42.
     */
    fun shortenedUrls(text: String): String {
        val spans = TextLinks.find(text).filter { it.kind == TextLinks.Kind.WEB }
        if (spans.isEmpty()) return text
        val out = StringBuilder(text)
        for (s in spans.reversed()) out.replace(s.start, s.end, shortened(s.target))
        return out.toString()
    }

    fun shortened(url: String): String {
        val u = runCatching { URI(url) }.getOrNull() ?: return url
        val host = u.host ?: return url
        var s = host
        val path = u.rawPath ?: ""
        if (path.length > 1) s += if (path.endsWith("/")) path.dropLast(1) else path
        if (s.length > 42) s = s.take(42) + "…"
        if (u.rawQuery != null || u.rawFragment != null) s += "?..."
        return s
    }

    /** iOS: the send arrow is live only when there are words. */
    fun hasText(text: String): Boolean = text.isNotBlank()
}
