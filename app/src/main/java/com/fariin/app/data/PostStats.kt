package com.fariin.app.data

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * One channel post's Message Statistics (owner 2026-11-19, the same contract as iOS):
 * `groups:postStats {cid, mid}` for the owner and admins, and `groups:notePostShare` after a post
 * is forwarded, since the forwarded copy is sealed and the server can not see shares otherwise.
 */
object PostStats {
    data class Data(
        val views: Int, val reactions: Int, val publicShares: Int, val privateShares: Int,
        val interactions: GroupStats.DaySeries, val emoji: GroupStats.DaySeries,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun fns() = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)

    /** Fire and forget; errors are ignored (a share not counted is not worth a word to the user). */
    fun noteShare(cid: String, mid: String, to: List<String>) {
        // Like iOS: no blanks, never the channel itself, each chat once, at most 20.
        val targets = to.filter { it.isNotEmpty() && it != cid }.distinct().take(20)
        if (targets.isEmpty() || mid.isEmpty() || !com.fariin.app.channel.ChannelRules.safeId(cid)) return
        scope.launch {
            runCatching {
                fns().getHttpsCallable("notePostShare").withTimeout(15, TimeUnit.SECONDS)
                    .call(mapOf("cid" to cid, "mid" to mid, "to" to targets)).await()
            }
        }
    }

    suspend fun load(cid: String, mid: String): Data {
        val r = fns().getHttpsCallable("postStats").withTimeout(20, TimeUnit.SECONDS)
            .call(mapOf("cid" to cid, "mid" to mid)).await()
        return parse(r.getData() as? Map<*, *> ?: emptyMap<String, Any>())
    }

    fun parse(d: Map<*, *>): Data {
        val rows = (d["days"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
            .mapNotNull { r -> (r["d"] as? String)?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull() }?.let { it to r } }
        val days = rows.map { it.first }
        val interactions = if (rows.isEmpty()) GroupStats.DaySeries(emptyList(), emptyList()) else GroupStats.DaySeries(days, listOf(
            GroupStats.Series("Views", rows.map { GroupStats.int(it.second["views"]) }),
            GroupStats.Series("Shares", rows.map { GroupStats.int(it.second["shares"]) }),
        ))
        // Reactions by emoji, stacked: the ten most used each a series, the rest as "Other".
        val perDay = rows.map { (_, r) ->
            val m = HashMap<String, Int>()
            for ((e, n) in (r["reactions"] as? Map<*, *>).orEmpty()) {
                val k = e as? String ?: continue
                val cnt = GroupStats.int(n); if (cnt <= 0) continue
                // "ce_" and "cu_" keys are one "Custom" series, as on iOS.
                val name = if (com.fariin.app.ui.thread.CustomReaction.isCustom(k)) "Custom" else k
                m[name] = (m[name] ?: 0) + cnt
            }
            m
        }
        val totals = HashMap<String, Int>()
        perDay.forEach { m -> m.forEach { (k, n) -> totals[k] = (totals[k] ?: 0) + n } }
        val ranked = totals.entries.sortedWith(compareBy({ -it.value }, { it.key })).map { it.key }
        val top = ranked.take(10)
        val names = if (ranked.size > top.size) top + "Other" else top
        val emoji = if (ranked.isEmpty()) GroupStats.DaySeries(emptyList(), emptyList()) else GroupStats.DaySeries(days, names.map { name ->
            GroupStats.Series(name, perDay.map { m -> if (name == "Other") m.filterKeys { it !in top }.values.sum() else m[name] ?: 0 })
        })
        return Data(GroupStats.int(d["views"]), GroupStats.int(d["reactions"]), GroupStats.int(d["publicShares"]),
            GroupStats.int(d["privateShares"]), interactions, emoji)
    }
}
