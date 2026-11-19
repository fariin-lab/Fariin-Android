package com.fariin.app.data

import android.content.Context
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

/**
 * FARIIN PLUS send effects (feature `sendEffects`). A single-bubble text may carry plain `fx` (one of
 * [IDS]). The sender plays it once at the tap. A receiver plays it only for someone else's message
 * that is not deleted, not played here before (the newest 400 "cid/mid" kept), sent within 3 days,
 * and new and in view; and only after `plusEffects({cid, mids})` names it in `play` (never trusts fx
 * alone). Marked played BEFORE asking. Nothing with remove-animations on. Cleared on sign-out.
 */
object SendEffects {
    val IDS = listOf("hearts", "confetti", "balloons", "sparkles")
    fun title(id: String) = when (id) { "hearts" -> "Hearts"; "confetti" -> "Confetti"; "balloons" -> "Balloons"; else -> "Sparkles" }

    private const val PREFS = "fariin_fx"
    private const val KEEP = 400
    private const val MAX_AGE_MS = 3L * 86_400_000
    private var played: ArrayDeque<String>? = null

    private fun load(c: Context): ArrayDeque<String> = played ?: ArrayDeque(
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("played", "").orEmpty().split('\n').filter { it.isNotEmpty() }
    ).also { played = it }

    private fun save(c: Context, q: ArrayDeque<String>) {
        while (q.size > KEEP) q.removeFirst()
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("played", q.joinToString("\n")).apply()
    }

    /** The system's "remove animations" (animator duration scale 0). */
    fun animationsOff(c: Context): Boolean = try {
        android.provider.Settings.Global.getFloat(c.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) { false }

    /** Which of [msgs] (newest last) may ask the server: others', not deleted, with fx, recent, not played. */
    fun candidates(c: Context, cid: String, me: String, msgs: List<Message>, now: Long = System.currentTimeMillis()): List<Message> {
        val q = load(c)
        return msgs.filter { m ->
            m.fx != null && m.authorId != me && !m.deleted && now - m.createdAtMillis.toLong() <= MAX_AGE_MS && "$cid/${m.id}" !in q
        }
    }

    /**
     * Marks [mids] played, then asks the server which to play; returns the newest mid it names (or
     * null). Every failure is silent.
     */
    suspend fun ask(c: Context, cid: String, mids: List<String>): String? {
        if (mids.isEmpty()) return null
        val q = load(c)
        for (m in mids) { val k = "$cid/$m"; if (k !in q) q.addLast(k) }
        save(c, q)
        val d = try {
            FirebaseFunctions.getInstance(PlusStore.REGION).getHttpsCallable("plusEffects")
                .call(mapOf("cid" to cid, "mids" to mids)).await().getData() as? Map<*, *>
        } catch (_: Exception) { null } ?: return null
        val play = (d["play"] as? List<*>).orEmpty().filterIsInstance<String>().toSet()
        return mids.lastOrNull { it in play }
    }

    fun wipe(c: Context) {
        played = null
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
