package com.fariin.app.data

/**
 * F-27: the link card's address comes from the sender, so it is checked on read. Only http and
 * https, and only when the text itself holds a link to the same site: a card can no longer say
 * one bank in the text and open another, and a `file:` card never reaches the system.
 */
object LinkCardRules {
    /** The card's address to keep, or null to drop the card. */
    fun safeUrl(cardUrl: String, text: String): String? {
        val clean = TextFormat.cleanUrl(cardUrl) ?: return null
        val low = clean.lowercase()
        if (!low.startsWith("http://") && !low.startsWith("https://")) return null
        val host = hostOf(clean) ?: return null
        val inText = text.split(Regex("\\s+")).asSequence()
            .map { it.trim('(', ')', '[', ']', '<', '>', '"', '\'', ',', '.', '!', '?', ';', ':') }
            .filter { it.isNotEmpty() && '.' in it }
            .mapNotNull { TextFormat.cleanUrl(it) }
            .filter { it.lowercase().startsWith("http") }
            .mapNotNull { hostOf(it) }
        return if (inText.any { it == host }) clean else null
    }

    /** The host, lowercased, without a leading "www.". */
    internal fun hostOf(url: String): String? {
        val h = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').lowercase().removePrefix("www.")
        return h.ifEmpty { null }
    }
}
