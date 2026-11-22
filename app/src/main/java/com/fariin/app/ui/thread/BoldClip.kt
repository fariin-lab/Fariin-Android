package com.fariin.app.ui.thread

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.Html
import android.text.style.StyleSpan
import com.fariin.app.data.TextFormat

/**
 * Bold kept through copy and paste (iOS bold-everywhere, owner 2026-11-11). Copy puts the plain
 * words and an HTML copy with <b> runs on the clipboard; a paste into the composer of exactly those
 * words takes the <b> runs back as bold. Other apps that read HTML see the bold too.
 */
internal object BoldClip {
    /** The author's linked words go along too, as links in the HTML copy (iOS TextFormat.copy). */
    fun copy(c: Context, text: String, bold: List<TextFormat.Span>, links: List<TextFormat.Link> = emptyList()) {
        val cm = c.getSystemService(ClipboardManager::class.java) ?: return
        val runs = TextFormat.normalize(bold, text.length)
        val ls = TextFormat.normalizeLinks(links, text.length)
        val clip = if (runs.isEmpty() && ls.isEmpty()) ClipData.newPlainText("", text) else ClipData.newHtmlText("", text, html(text, runs, ls))
        runCatching { cm.setPrimaryClip(clip) }
    }

    private fun html(text: String, runs: List<TextFormat.Span>, links: List<TextFormat.Link>): String {
        fun esc(s: String) = Html.escapeHtml(s).replace("\n", "<br>")
        // Cut the words wherever a bold run or a link starts or ends, then mark each piece.
        val cuts = (listOf(0, text.length) + runs.flatMap { listOf(it.start, it.end) } + links.flatMap { listOf(it.start, it.end) })
            .distinct().sorted()
        val b = StringBuilder()
        var open: TextFormat.Link? = null
        for (i in 0 until cuts.size - 1) {
            val x = cuts[i]
            val y = cuts[i + 1]
            if (y <= x) continue
            val link = links.firstOrNull { it.start <= x && y <= it.end }
            if (link != open) {
                if (open != null) b.append("</a>")
                if (link != null) b.append("<a href=\"").append(Html.escapeHtml(link.url)).append("\">")
                open = link
            }
            val bold = runs.any { it.start <= x && y <= it.end }
            val words = esc(text.substring(x, y))
            if (bold) b.append("<b>").append(words).append("</b>") else b.append(words)
        }
        if (open != null) b.append("</a>")
        return b.toString()
    }

    /**
     * The bold runs a paste brought, placed in [new]; empty when the change was not a paste of the
     * clipboard's words or the clipboard has no HTML. The clipboard is read only when it holds HTML
     * and at least two characters came in at once, so plain typing never touches it.
     */
    fun pasted(c: Context, old: String, new: String): List<TextFormat.Span> {
        if (new.length - old.length < 2) return emptyList()
        var p = 0
        val max = minOf(old.length, new.length)
        while (p < max && old[p] == new[p]) p++
        var s = 0
        while (s < max - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        val added = new.substring(p, new.length - s)
        if (added.length < 2) return emptyList()
        val cm = c.getSystemService(ClipboardManager::class.java) ?: return emptyList()
        val d = runCatching { cm.primaryClipDescription }.getOrNull() ?: return emptyList()
        if (!d.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) return emptyList()
        val item = runCatching { cm.primaryClip?.getItemAt(0) }.getOrNull() ?: return emptyList()
        val html = item.htmlText ?: return emptyList()
        if (item.text?.toString() != added) return emptyList()
        val sp = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        val at = sp.toString().indexOf(added)
        if (at < 0) return emptyList()
        return sp.getSpans(0, sp.length, StyleSpan::class.java)
            .filter { it.style == Typeface.BOLD || it.style == Typeface.BOLD_ITALIC }
            .mapNotNull { st ->
                val a = (sp.getSpanStart(st) - at).coerceAtLeast(0)
                val b = (sp.getSpanEnd(st) - at).coerceAtMost(added.length)
                if (b > a) TextFormat.Span(p + a, p + b) else null
            }
    }
}
