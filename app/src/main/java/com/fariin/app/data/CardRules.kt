package com.fariin.app.data

import java.util.Locale

/**
 * Contact and location cards, read from Kulan-iOS Models.swift (SharedContactCard,
 * SharedLocationCard, safeText), MainShell's chat-list preview and ThreadView's location send.
 * Neither card has fields of its own: each rides the sealed text pipeline as a marker, so the
 * server sees one more ciphertext and nothing else.
 *
 *   "fariin-contact:<uid>|<photoURL-or-empty>|<name>"   (name last; uid and photo never hold "|")
 *   "fariin-location:<lat>|<lon>|<label-or-empty>"
 *
 * Pure, so it is tested.
 */
object CardRules {
    const val CONTACT = "fariin-contact:"
    const val LOCATION = "fariin-location:"
    /** Live location (owner 2026-11-13, both apps): start spot, end time, optional note (last). */
    const val LIVE = "fariin-live:"

    data class Contact(val uid: String, val name: String, val photo: String?)
    data class Location(val lat: Double, val lon: Double, val label: String?)
    data class Live(val lat: Double, val lon: Double, val untilMs: Long, val note: String?)

    /** "fariin-live:<lat>|<lon>|<untilMs>|<note-or-empty>" (plan 2026-11-13-live-location-plan.md). */
    fun live(text: String): Live? {
        if (!text.startsWith(LIVE)) return null
        val p = text.substring(LIVE.length).split("|", limit = 4)
        if (p.size < 3) return null
        val lat = p[0].toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
        val lon = p[1].toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
        val until = p[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return Live(lat, lon, until, p.getOrNull(3)?.ifEmpty { null })
    }

    fun liveText(lat: Double, lon: Double, untilMs: Long, note: String?) = "$LIVE$lat|$lon|$untilMs|${note ?: ""}"

    /** Message.contactCard: three parts, uid and name not empty. */
    fun contact(text: String): Contact? {
        if (!text.startsWith(CONTACT)) return null
        val p = text.substring(CONTACT.length).split("|", limit = 3)
        if (p.size != 3 || p[0].isEmpty() || p[2].isEmpty()) return null
        return Contact(p[0], p[2], p[1].ifEmpty { null })
    }

    /** Message.locationCard: two numbers, then an optional label. */
    fun location(text: String): Location? {
        if (!text.startsWith(LOCATION)) return null
        val p = text.substring(LOCATION.length).split("|", limit = 3)
        if (p.size < 2) return null
        val lat = p[0].toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
        val lon = p[1].toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
        return Location(lat, lon, p.getOrNull(2)?.ifEmpty { null })
    }

    fun contactText(uid: String, name: String, photo: String?) = "$CONTACT$uid|${photo ?: ""}|$name"

    /** Swift prints a Double in its shortest form ("52.3676"), and so does Kotlin; each side reads the other's. */
    fun locationText(lat: Double, lon: Double, label: String?) = "$LOCATION$lat|$lon|${label ?: ""}"

    fun isCard(text: String) = text.startsWith(CONTACT) || text.startsWith(LOCATION) || text.startsWith(LIVE)

    /** Message.safeText and the chat-list preview: a noun, never the raw marker. */
    fun previewWord(text: String?): String? = when {
        text == null -> null
        text.startsWith(CONTACT) -> "Contact"
        text.startsWith(LOCATION) -> "Location"
        text.startsWith(LIVE) -> "Live location"
        else -> null
    }

    /** The bubble's title: the label that travelled, else "Location" (MessageRowModelBuilder). */
    fun title(l: Location): String = l.label?.takeIf { it.isNotBlank() } ?: "Location"

    /** The picker's fallback when no place name came back (LocationPickerSheet.sendButtonSubtitle). */
    fun coords(lat: Double, lon: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lon)

    /**
     * The phone's maps app with a pin on the spot: `geo:lat,lon?q=lat,lon(label)`, the label
     * percent-encoded (a bracket or "&" in a place name would end the query early).
     */
    fun geoUri(l: Location): String {
        val ll = "${l.lat},${l.lon}"
        val q = l.label?.takeIf { it.isNotBlank() }?.let { "$ll(${encode(it)})" } ?: ll
        return "geo:$ll?q=$q"
    }

    /**
     * Turn-by-turn to the spot, for the navigation apps that take it. The coordinates go to an app
     * on the phone through an intent, never into a web link: no website learns where the place is.
     */
    fun navigationUri(l: Location): String = "google.navigation:q=${l.lat},${l.lon}"

    /**
     * LocationPickerSheet.resolveName: the building or landmark, then the street, the area, the
     * city, the region, the country. Android's feature name is often just the house number; a
     * bare number is no name, so the street wins there.
     */
    fun placeName(feature: String?, street: String?, subLocality: String?, locality: String?,
                  subAdmin: String?, admin: String?, country: String?): String? {
        val f = feature?.trim()?.takeIf { it.isNotEmpty() && !it.all { ch -> ch.isDigit() || ch == '-' || ch == '/' } }
        return listOf(f, street, subLocality, locality, subAdmin, admin, country)
            .firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    /** The line under a search result: street, city, country (resolvedAddress). */
    fun addressLine(street: String?, locality: String?, country: String?): String =
        listOfNotNull(street, locality, country).filter { it.isNotBlank() }.joinToString(", ")

    /** A contact card offers Message unless it is me or the person this chat is already with. */
    fun canMessage(card: Contact, me: String, cid: String): Boolean =
        card.uid != me && com.fariin.app.crypto.Wire.directCid(me, card.uid) != cid

    private fun encode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val ch = b.toInt() and 0xFF
            if ((ch in 'a'.code..'z'.code) || (ch in 'A'.code..'Z'.code) || (ch in '0'.code..'9'.code) || ch in "-_.~".map { it.code })
                sb.append(ch.toChar())
            else sb.append('%').append(String.format(Locale.US, "%02X", ch))
        }
        return sb.toString()
    }
}
