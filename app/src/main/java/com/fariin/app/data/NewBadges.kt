package com.fariin.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The "New" pills (iOS NewBadges). The admin panel writes `config/features` field
 * `newBadges` = { spotId: endMs }, ids from [NewBadgeSpots]. A spot shows its pill until the panel
 * removes the key or the end time passes; opening the spot does not clear it (owner's rule).
 *
 * Fed by the one live `config/features` listener in [AppFeatures]; unlike the switches this is
 * live, so a pill added or removed on the panel shows at once.
 */
object NewBadges {
    private val _ends = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Row key to end time (epoch ms), as the panel last said. Past ends are kept; [active] drops them. */
    val ends: StateFlow<Map<String, Long>> = _ends.asStateFlow()

    fun update(raw: Any?) { _ends.value = parse(raw) }

    /** Catalogued ids with a numeric end only; unknown ids and other values are ignored. */
    fun parse(raw: Any?): Map<String, Long> {
        val m = raw as? Map<*, *> ?: return emptyMap()
        return buildMap {
            m.forEach { (k, v) ->
                val key = k as? String ?: return@forEach
                if (key !in NewBadgeSpots.ALL) return@forEach
                val end = when (v) {
                    is Long -> v
                    is Int -> v.toLong()
                    is Double -> if (v.isFinite()) v.toLong() else return@forEach
                    is Number -> v.toLong()
                    else -> return@forEach
                }
                put(key, end)
            }
        }
    }

    /** The keys whose pill shows at [now]. */
    fun active(ends: Map<String, Long>, now: Long): Set<String> =
        ends.filterValues { it > now }.keys

    /** The soonest end still ahead of [now], for the one timer that takes a pill down; null if none. */
    fun nextEnd(ends: Map<String, Long>, now: Long): Long? =
        ends.values.filter { it > now }.minOrNull()
}
