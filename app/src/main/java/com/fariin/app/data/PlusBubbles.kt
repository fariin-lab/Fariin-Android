package com.fariin.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

/**
 * FARIIN PLUS bubble styles (iOS PlusBubbles, feature `bubbleStyles`).
 *
 * A Plus member picks ONE style; it lives on the profile, `users/{uid}.plusBubble = {s, u, at}`, which
 * only the callable `plusBubbleStyle` writes (never this app). It draws only while `u` is ahead, only
 * on TEXT bubbles in ONE-TO-ONE chats; a message's own free style (`bs`) wins. The other person's
 * comes from the users-doc listener the chat already has, mine from my own; never per message.
 * Receivers see the bubble only, nothing that says Plus.
 */
data class PlusBubble(val style: String, val untilMs: Long) {
    val active: Boolean get() = untilMs > System.currentTimeMillis()

    companion object {
        /** The stored map, checked: a known id and an end. Anything else is no style. */
        fun parse(raw: Any?): PlusBubble? {
            val d = raw as? Map<*, *> ?: return null
            val s = d["s"] as? String ?: return null
            if (s !in PlusBubbles.IDS) return null
            val u = (d["u"] as? Number)?.toLong() ?: return null
            return if (u > 0) PlusBubble(s, u) else null
        }
    }
}

object PlusBubbles {
    val IDS = listOf("sunset", "ocean", "dusk")
    const val CLASSIC = "Classic"

    /** One look for light and dark: a diagonal gradient (top-left to bottom-right) and its text colour. */
    data class Look(val id: String, val name: String, val from: Long, val to: Long, val text: Long)

    val all = listOf(
        Look("sunset", "Sunset", 0xFFFF5E7A, 0xFFFF9F0A, 0xFF2B0710),
        Look("ocean", "Ocean", 0xFF2AD17A, 0xFF0A9FB5, 0xFF00262B),
        Look("dusk", "Dusk", 0xFF7D5CFF, 0xFF0590F7, 0xFFFFFFFF),
    )
    /** High contrast: black text on the light styles, deeper stops under Dusk's white text. */
    val strong = listOf(
        Look("sunset", "Sunset", 0xFFFF5E7A, 0xFFFF9F0A, 0xFF000000),
        Look("ocean", "Ocean", 0xFF2AD17A, 0xFF0A9FB5, 0xFF000000),
        Look("dusk", "Dusk", 0xFF5B3FD9, 0xFF0368C2, 0xFFFFFFFF),
    )

    fun look(id: String?, strong: Boolean = false): Look? = id?.let { i -> (if (strong) this.strong else all).firstOrNull { it.id == i } }

    /** What a stored style draws right now: null when there is none or its end has passed. */
    fun drawn(b: PlusBubble?, strong: Boolean = false): Look? = if (b == null || !b.active) null else look(b.style, strong)

    fun name(id: String?) = look(id)?.name ?: CLASSIC

    /** Mine, from my own users-doc listener (the chat's), shown at once after a choice. */
    var mine by mutableStateOf<PlusBubble?>(null); private set
    fun record(v: PlusBubble?) { if (v != mine) mine = v }
    fun clear() { mine = null }

    /** `plusBubbleStyle({style})`; null = Classic. Throws a sentence to show. */
    suspend fun choose(id: String?) {
        try {
            val d = FirebaseFunctions.getInstance(PlusStore.REGION).getHttpsCallable("plusBubbleStyle")
                .call(mapOf("style" to (id ?: "classic"))).await().getData() as? Map<*, *>
            val u = (d?.get("until") as? Number)?.toLong()
            mine = if (id != null && u != null) PlusBubble(id, u) else if (id == null) null else mine
        } catch (e: FirebaseFunctionsException) {
            val refused = PlusStore.detailsCode(e) != null || e.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED
            throw IllegalStateException(if (refused && !e.message.isNullOrBlank()) e.message else "Couldn't change your bubble style. Try again.")
        } catch (_: Exception) {
            throw IllegalStateException("Couldn't change your bubble style. Try again.")
        }
    }
}
