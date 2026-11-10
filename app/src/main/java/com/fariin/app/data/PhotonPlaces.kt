package com.fariin.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Place search and "places in this area" on Photon (photon.komoot.io): free, no key, built on
 * OpenStreetMap. The request carries only what it must: the words typed or the spot on the map,
 * and the app's name as User-Agent. Never the account, the phone's name or anything about the chat.
 *
 * The URL builders and the parser are pure and tested; [fetch] is the one network call.
 */
object PhotonPlaces {
    const val BASE = "https://photon.komoot.io"
    const val USER_AGENT = "Fariin-Android"

    /** The OpenStreetMap kinds that count as "a place" for the nearby list: food, shops, sights, parks. */
    val NEARBY_KINDS = listOf("amenity", "shop", "tourism", "leisure", "historic")

    /**
     * One answer. [name] is what the card will say; [address] is the line under it; [area] is the
     * town (for an approximate share).
     */
    data class Place(val name: String, val address: String, val lat: Double, val lon: Double, val area: String?)

    /** Text search, nudged toward where the map is looking (three decimals, about 100 m, is plenty). */
    fun searchUrl(q: String, biasLat: Double?, biasLon: Double?, limit: Int = 12, localKm: Double? = null): String {
        val sb = StringBuilder("$BASE/api?q=").append(URLEncoder.encode(q.trim(), "UTF-8")).append("&limit=").append(limit)
        if (biasLat != null && biasLon != null) {
            sb.append("&lat=").append(f(biasLat, 3)).append("&lon=").append(f(biasLon, 3))
            // Only inside a box around the map's centre: "coffee" means the coffee shops here, not
            // the towns called Coffee three states away (owner, 2026-11-10).
            if (localKm != null) {
                val dLat = localKm / 111.0
                val dLon = localKm / (111.0 * Math.cos(Math.toRadians(biasLat)).coerceAtLeast(0.1))
                sb.append("&bbox=").append(f(biasLon - dLon, 4)).append(',').append(f(biasLat - dLat, 4))
                    .append(',').append(f(biasLon + dLon, 4)).append(',').append(f(biasLat + dLat, 4))
            }
        }
        return sb.toString()
    }

    /** Named places around the pin, nearest first (iOS asks for 800 m around the centre). */
    fun nearbyUrl(lat: Double, lon: Double, radiusKm: Double = 0.8, limit: Int = 25): String =
        "$BASE/reverse?lat=${f(lat, 4)}&lon=${f(lon, 4)}&radius=${f(radiusKm, 1)}&limit=$limit" +
            NEARBY_KINDS.joinToString("") { "&osm_tag=$it" }

    /** What is at one spot: the fallback when the phone's own Geocoder gives nothing. */
    fun reverseUrl(lat: Double, lon: Double): String = "$BASE/reverse?lat=${f(lat, 5)}&lon=${f(lon, 5)}&limit=1"

    /**
     * Photon's GeoJSON into places. A result with no coordinates is dropped. [namedOnly] keeps only
     * real names (the nearby list: a bare house or an unnamed shop is no place to send).
     */
    fun parse(json: String, namedOnly: Boolean = false): List<Place> {
        val features = runCatching { JSONObject(json).optJSONArray("features") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Place>()
        val seen = HashSet<String>()
        for (i in 0 until features.length()) {
            val ft = features.optJSONObject(i) ?: continue
            val coords = ft.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lon = coords.optDouble(0)
            val lat = coords.optDouble(1)
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            val p = ft.optJSONObject("properties") ?: JSONObject()
            val ownName = p.s("name")
            if (namedOnly && ownName == null) continue
            val street = listOfNotNull(p.s("street"), p.s("housenumber")).joinToString(" ").ifEmpty { null }
            val city = p.s("city") ?: p.s("town") ?: p.s("village")
            val area = city ?: p.s("county") ?: p.s("state") ?: p.s("country")
            val name = ownName ?: street ?: p.s("locality") ?: p.s("district") ?: area ?: continue
            val address = listOfNotNull(street, p.s("locality") ?: p.s("district"), city, p.s("country"))
                .filter { it != name }.distinct().joinToString(", ")
            // The same shop mapped twice (as a point and as a building) shows once.
            if (!seen.add(name + "|" + f(lat, 4) + "|" + f(lon, 4))) continue
            out.add(Place(name, address, lat, lon, area))
        }
        return out
    }

    /** GET one Photon URL off the main thread. Throws on no connection or a bad answer. */
    suspend fun fetch(url: String): String = runInterruptible(Dispatchers.IO) {
        require(url.startsWith(BASE)) { "Photon only" }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) throw IOException("Photon ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun JSONObject.s(k: String): String? = optString(k, "").trim().takeIf { it.isNotEmpty() }
    private fun f(v: Double, d: Int) = String.format(Locale.US, "%.${d}f", v)
}
