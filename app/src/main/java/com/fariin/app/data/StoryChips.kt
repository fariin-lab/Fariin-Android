package com.fariin.app.data

import java.net.URLEncoder

/** The words and the tap address of a story chip (iOS StoryLinkSticker.label, addPlaceSticker). */
object StoryChips {
    /** A web address as typed (https added when missing); the chip shows its host, as iOS does. */
    fun link(raw: String): Pair<String, String>? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
        val host = runCatching { java.net.URI(s).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.contains('.') } ?: return null
        return host.uppercase() to s
    }

    /** A place by name, opened in the phone's maps (a web address both apps' viewers accept). */
    fun place(raw: String): Pair<String, String>? {
        val name = raw.trim().split(' ', '\t', '\n').filter { it.isNotEmpty() }.joinToString(" ").take(40)
        if (name.isEmpty()) return null
        return name.uppercase() to "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode(name, "UTF-8")
    }

    /** A picked place opened in the phone's maps at its exact spot, at its coordinates (the name is on the chip). */
    fun placeUrl(name: String, lat: Double, lon: Double): String =
        "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode(String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon), "UTF-8")
}
