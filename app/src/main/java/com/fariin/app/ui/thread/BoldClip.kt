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
    fun copy(c: Context, text: String, bold: List<TextFormat.Span>) {
        val cm = c.getSystemService(ClipboardManager::class.java) ?: return
        val runs = TextFormat.normalize(bold, text.length)
        val clip = if (runs.isEmpty()) ClipData.newPlainText("", text) else ClipData.newHtmlText("", text, html(text, runs))
        runCatching { cm.setPrimaryClip(clip) }
    }

    private fun html(text: String, runs: List<TextFormat.Span>): String {
        fun esc(s: String) = Html.escapeHtml(s).replace("\n", "<br>")
        val b = StringBuilder()
        var at = 0
        for (r in runs) {
            b.append(esc(text.substring(at, r.start))).append("<b>").append(esc(text.substring(r.start, r.end))).append("</b>")
            at = r.end
        }
        return b.append(esc(text.substring(at))).toString()
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
