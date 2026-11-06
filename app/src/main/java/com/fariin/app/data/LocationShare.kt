package com.fariin.app.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The rules of sharing a place, kept apart from the screens so they are tested.
 *
 * What travels is still the one location card ("fariin-location:<lat>|<lon>|<label>", CardRules):
 * no new fields, so the iPhone app and older Android builds read every share. "Approximate" is
 * carried by the numbers themselves (rounded to a 0.01 degree grid, about 1 km) and by a label that
 * names only the town, never a street.
 */
object LocationShare {
    /** iOS's word for a share it could not place precisely (LocationPickerSheet.sendName). */
    const val APPROX_LABEL = "Approximate location"
    private const val NEAR = "Near "

    /** The free map styles (OpenFreeMap, OpenStreetMap data). No key, no account. */
    const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
    const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
    fun styleUrl(dark: Boolean) = if (dark) STYLE_DARK else STYLE_LIGHT

    /** Shown on every map we draw, as the map data's licence asks. */
    const val ATTRIBUTION = "© OpenStreetMap contributors"

    /** Where the pin came from: my own fix, a spot I dragged the map to, or a place I picked from a list. */
    enum class Source { MY_FIX, MAP, PLACE }

    data class Spot(val lat: Double, val lon: Double, val label: String?)

    /** One coordinate snapped to the 0.01 degree grid (about 1.1 km north-south). */
    fun roundApprox(v: Double): Double = (v * 100.0).roundToLong() / 100.0

    /**
     * The town or region for an approximate share: the city first, then the wider areas. Never the
     * street or the neighbourhood, which can narrow a 1 km square down to a few houses.
     */
    fun areaName(locality: String?, subAdmin: String?, admin: String?, country: String?): String? =
        listOf(locality, subAdmin, admin, country).firstOrNull { !it.isNullOrBlank() }?.trim()

    /** "Near Hargeisa", or iOS's "Approximate location" when no town came back. */
    fun areaLabel(area: String?): String = area?.trim()?.takeIf { it.isNotEmpty() }?.let { NEAR + it } ?: APPROX_LABEL

    /**
     * What Send puts in the card. Exact: the spot as it is, under its name (or no label, so the
     * other phone says "Location"). Approximate: the rounded spot under the town's name only.
     */
    fun outgoing(lat: Double, lon: Double, exact: Boolean, name: String?, area: String?): Spot =
        if (exact) Spot(lat, lon, name?.trim()?.takeIf { it.isNotEmpty() })
        else Spot(roundApprox(lat), roundApprox(lon).coerceIn(-180.0, 180.0), areaLabel(area))

    /**
     * With only "approximate" permission, my own fix is kilometres wide: it cannot be sent as an
     * exact spot (iOS sendsApproximateFix). A spot the person dragged to or picked is their choice.
     */
    fun mustBeApproximate(source: Source, coarseOnly: Boolean): Boolean = source == Source.MY_FIX && coarseOnly

    /**
     * The honest size of the fix, from the phone's own estimate (the radius it is 68% sure of),
     * rounded UP so the words never promise more than the fix holds. Null when there is no estimate.
     */
    fun accuracyText(meters: Float?): String? {
        val m = meters?.takeIf { it.isFinite() && it > 0f } ?: return null
        return when {
            m < 100f -> "Within ${ceil(m.toDouble()).toInt()} m"
            m < 1000f -> "Within ${(ceil(m / 10.0) * 10).toInt()} m"
            else -> "Within " + String.format(Locale.US, "%.1f", ceil(m / 100.0) / 10.0) + " km"
        }
    }

    /** The small line under the pin's address: how sure the spot is, in plain words. */
    fun pinNote(source: Source, accuracyM: Float?, coarseOnly: Boolean, hasFix: Boolean): String = when (source) {
        Source.MY_FIX -> when {
            !hasFix -> "Finding your location…"
            coarseOnly -> APPROX_LABEL
            else -> accuracyText(accuracyM) ?: "Your location"
        }
        Source.MAP -> "Spot chosen on the map"
        Source.PLACE -> "Place chosen from the list"
    }

    /** One line under Exact / Approximate, saying what the other person will get. */
    fun privacyLine(exact: Boolean, locked: Boolean): String = when {
        locked -> "Your phone lets Fariin see only your approximate location."
        exact -> "Sends this exact spot and its name."
        else -> "Sends only the area, about 1 km, with no street."
    }

    /**
     * A received card that was sent as approximate: iOS's label, or numbers that sit on the 0.01
     * degree grid (a real fix or a picked place practically never does). Those cards show the area
     * zoomed out with a soft circle, not a sharp pin, so nobody reads them as a front door.
     */
    fun looksApproximate(l: CardRules.Location): Boolean =
        l.label == APPROX_LABEL || (onGrid(l.lat) && onGrid(l.lon))

    private fun onGrid(v: Double) = abs(v * 100.0 - (v * 100.0).roundToLong()) < 1e-6

    /** The zoom of the card's small map: street level, or town level for an approximate share. */
    fun cardZoom(approximate: Boolean): Double = if (approximate) 12.0 else 15.0

    /** The disk/memory key of one rendered card map. Five decimals is about a metre. */
    fun cardKey(lat: Double, lon: Double, zoom: Double, dark: Boolean, wPx: Int, hPx: Int): String =
        String.format(Locale.US, "%.5f_%.5f_z%.1f_%s_%dx%d", lat, lon, zoom, if (dark) "d" else "l", wPx, hPx)

    /**
     * A new fix replaces the one on screen when it is at least as sure, or when the old one is over
     * 10 seconds old (the person may have walked). A rough network fix never knocks out a fresh GPS one.
     */
    fun takeNewFix(oldAccuracyM: Float?, oldAgeMs: Long?, newAccuracyM: Float?): Boolean {
        if (oldAccuracyM == null || oldAgeMs == null) return true
        if (oldAgeMs > 10_000L) return true
        val n = newAccuracyM ?: return false
        return n <= oldAccuracyM
    }

    /** Metres between two points (haversine), to tell a real move of the fix from jitter. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
