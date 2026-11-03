package com.fariin.app.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/*
 * My QR code and the profile link (iOS QRViews.qrImage, KulanApp.userLink / route, InviteShare).
 * Pure, so the link shapes and the code's layout are unit tested; drawing is ui/settings/QrPages.kt.
 */
object QrRules {
    const val LINK_HOST = "https://fariin.com"

    /** iOS InviteShare.sentence and title. */
    const val INVITE_SENTENCE = "Let's chat on Fariin. Here's my link"
    const val INVITE_TITLE = "Invite to Fariin"

    /** KulanApp.userLink: https://fariin.com/u/<handle>, the handle percent-encoded. */
    fun userLink(handle: String): String = "$LINK_HOST/u/" + java.net.URLEncoder.encode(handle, "UTF-8").replace("+", "%20")

    /** InviteShare.link: my profile link, or the site while I have no username. */
    fun inviteLink(handle: String?): String = if (handle.isNullOrEmpty()) LINK_HOST else userLink(handle)

    /** iOS safeSegment: a value that can be a document id. */
    fun safeSegment(v: String): Boolean =
        v.isNotEmpty() && '/' !in v && v != "." && v != ".." && !(v.startsWith("__") && v.endsWith("__")) && v.length <= 128

    /**
     * The username in a scanned code, as KulanApp.route reads a user link: https://fariin.com/u/<h>
     * (or www.), or kulan://u/<h>. Anything else is not a Fariin user code.
     */
    fun handleFrom(code: String): String? {
        val uri = runCatching { java.net.URI(code.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val parts = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
        val value = when (scheme) {
            "kulan" -> if (uri.host?.lowercase() == "u") parts.lastOrNull() else null
            "https" -> {
                val host = uri.host?.lowercase()
                if ((host == "fariin.com" || host == "www.fariin.com") && parts.size >= 2 && parts[0] == "u") parts[1] else null
            }
            else -> null
        } ?: return null
        return value.takeIf { safeSegment(it) }
    }

    /** The code's modules, error correction H, no quiet zone (iOS qrCodeGenerator "H"). true = dark. */
    fun matrix(text: String): Array<BooleanArray> {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 0))
        return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
    }

    /** The three finder squares' top-left modules (7 x 7 each). */
    fun eyes(n: Int): List<Pair<Int, Int>> = listOf(0 to 0, n - 7 to 0, 0 to n - 7)

    fun inEye(x: Int, y: Int, n: Int): Boolean = eyes(n).any { (ex, ey) -> x >= ex && x < ex + 7 && y >= ey && y < ey + 7 }

    /** The modules left clear for the centre mark: [lo, hi) on both axes, same parity as n (iOS). */
    fun clearRange(n: Int, clearCentre: Float): IntRange {
        if (clearCentre <= 0f) return IntRange.EMPTY
        var hole = Math.round(n * clearCentre)
        if (hole % 2 != n % 2) hole += 1
        val lo = (n - hole) / 2
        return lo until lo + hole
    }
}
