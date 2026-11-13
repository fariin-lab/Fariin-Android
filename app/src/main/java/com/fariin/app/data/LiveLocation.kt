package com.fariin.app.data

import android.content.Context
import com.fariin.app.crypto.Wire
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Live location (owner 2026-11-13, both apps; contract in fariin-plans/2026-11-13-live-location-plan.md).
 *
 * The message is a `fariin-live:` card (CardRules.Live): where it started and until when. The moving
 * spot lives beside it in `conversations/{cid}/live/{uid}`, one doc per person sharing in that chat:
 *   { msgId, until (timestamp), ended (bool), updatedAt (server time), pos }
 * where `pos` is `{"lat":..,"lon":..,"acc":..}` sealed with the chat's own message crypto in a 1:1 (the
 * server never learns where anyone is) and plain JSON in a group. Overwritten on each update; no
 * history is kept. A scheduled sweep on the server ends a share whose `until` passed.
 */
object LiveLocation {
    data class Spot(
        val uid: String, val msgId: String, val lat: Double, val lon: Double, val acc: Float?,
        val untilMs: Long, val ended: Boolean, val updatedMs: Long,
    ) {
        fun live(now: Long = System.currentTimeMillis()) = !ended && untilMs > now
    }

    /** The share lengths the sheet offers (plan: 15 minutes, 1 hour, 8 hours; 1 hour first). */
    val LENGTHS = listOf(15 * 60_000L to "15 minutes", 60 * 60_000L to "1 hour", 8 * 60 * 60_000L to "8 hours")
    const val DEFAULT_LENGTH = 60 * 60_000L

    private fun col(cid: String) = FirebaseFirestore.getInstance().collection("conversations").document(cid).collection("live")

    private fun posJson(lat: Double, lon: Double, acc: Float?) =
        JSONObject().put("lat", lat).put("lon", lon).apply { if (acc != null) put("acc", acc.toDouble()) }.toString()

    /** 1:1: the spot sealed as a string. Group: a plain map {lat, lon, acc?} (the rules want a map there). */
    private suspend fun pos(c: com.fariin.app.crypto.FariinCrypto, cid: String, lat: Double, lon: Double, acc: Float?): Any =
        if (Wire.isGroupCid(cid)) buildMap { put("lat", lat); put("lon", lon); if (acc != null) put("acc", acc.toDouble()) }
        else c.encryptForConversation(cid, posJson(lat, lon, acc))

    private fun open(c: com.fariin.app.crypto.FariinCrypto, cid: String, raw: Any?): Triple<Double, Double, Float?>? = runCatching {
        when (raw) {
            is Map<*, *> -> Triple((raw["lat"] as Number).toDouble(), (raw["lon"] as Number).toDouble(), (raw["acc"] as? Number)?.toFloat())
            is String -> JSONObject(if (raw.trimStart().startsWith("{")) raw else c.decrypt(raw, cid)).let {
                Triple(it.getDouble("lat"), it.getDouble("lon"), if (it.has("acc")) it.getDouble("acc").toFloat() else null)
            }
            else -> null
        }
    }.getOrNull()

    /** The share's id, the same on the card and the doc: its end time ("u<untilMs>"). */
    fun idFor(untilMs: Long) = "u$untilMs"

    /** Start (or carry on) my share in [cid]: always the whole doc, the same msgId and until (rules b and c). */
    suspend fun put(ctx: Context, me: String, cid: String, untilMs: Long, lat: Double, lon: Double, acc: Float?) {
        val c = CryptoHub.get(ctx)
        col(cid).document(me).set(mapOf(
            "msgId" to idFor(untilMs), "until" to Timestamp(java.util.Date(untilMs)), "ended" to false,
            "updatedAt" to FieldValue.serverTimestamp(), "pos" to pos(c, cid, lat, lon, acc),
        )).await()
    }

    /** Stop my share in [cid]: ended, with no spot left (rule a). */
    suspend fun end(me: String, cid: String, untilMs: Long) {
        runCatching {
            col(cid).document(me).set(mapOf("msgId" to idFor(untilMs), "until" to Timestamp(java.util.Date(untilMs)),
                "ended" to true, "updatedAt" to FieldValue.serverTimestamp())).await()
        }
    }

    /** Everyone sharing (or who shared) in [cid], opened; called again on every change. */
    fun listen(ctx: Context, cid: String, onChange: (List<Spot>) -> Unit): ListenerRegistration {
        val c = CryptoHub.get(ctx)
        return col(cid).addSnapshotListener { snap, _ ->
            val spots = snap?.documents.orEmpty().mapNotNull { d ->
                val until = d.getTimestamp("until")?.toDate()?.time ?: return@mapNotNull null
                val ended = d.getBoolean("ended") == true
                val pos = open(c, cid, d.get("pos"))
                Spot(d.id, d.getString("msgId") ?: "", pos?.first ?: Double.NaN, pos?.second ?: Double.NaN,
                    pos?.third, until, ended, d.getTimestamp("updatedAt")?.toDate()?.time ?: 0L)
            }
            onChange(spots)
        }
    }

    /** "Updated just now" / "Updated 3 min ago" / "Updated 2 h ago". */
    fun updatedWords(updatedMs: Long, now: Long = System.currentTimeMillis()): String {
        val s = ((now - updatedMs) / 1000).coerceAtLeast(0)
        return when {
            updatedMs <= 0 -> "Waiting for the first update"
            s < 60 -> "Updated just now"
            s < 3600 -> "Updated ${s / 60} min ago"
            else -> "Updated ${s / 3600} h ago"
        }
    }
}
