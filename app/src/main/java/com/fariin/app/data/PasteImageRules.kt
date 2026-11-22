package com.fariin.app.data

/**
 * A picture pasted into the chat composer (iOS ChatComposerView, decision D-composer-5): it goes to
 * the same photo editor a picked photo opens, never straight out. Pure rules, unit tested.
 *
 * iOS takes any picture the clipboard holds as a still image (a GIF arrives as its first frame,
 * so it is a photo, not a GIF message). There is no size cap on a pasted picture there; the
 * 2,000,000 byte cap in iOS TextFormat.pastedBold is for pasted rich text, not pictures.
 */
object PasteImageRules {
    /** The picture kinds a paste may bring. SVG is a drawing script, not a photo, so it stays out. */
    private val ACCEPTED = setOf(
        "image/png", "image/jpeg", "image/jpg", "image/pjpeg", "image/webp",
        "image/heic", "image/heif", "image/gif", "image/bmp",
    )

    /** True for a picture kind the photo path can take. */
    fun accepts(mime: String?): Boolean = mime?.trim()?.lowercase()?.substringBefore(';')?.trim() in ACCEPTED

    /**
     * Pictures win unless the clipboard also carries real words (iOS pastedImages): a picture
     * copied from a web page comes with its address, which is not what the person meant to paste.
     * No words, blank words, or a single address with a scheme: the picture is pasted.
     */
    fun pictureWins(clipText: String?): Boolean {
        val s = clipText?.trim().orEmpty()
        if (s.isEmpty()) return true
        return URL_WITH_SCHEME.matches(s)
    }

    /** At most this many pictures from one paste: the same limit as a pick from the attach sheet. */
    fun take(count: Int, perPick: Int): Int = count.coerceAtLeast(0).coerceAtMost(perPick.coerceAtLeast(1))

    /** One word with a scheme and no spaces ("https://...", "mailto:..."): what iOS URL(string:) gives a scheme. */
    private val URL_WITH_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:\\S+$")
}
