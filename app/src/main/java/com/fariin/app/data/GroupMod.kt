package com.fariin.app.data

/**
 * A group's moderation state, as iOS reads it (Models.swift GroupMod + the Conversation parse).
 * Fariin writes `mod.state` (ok / limited / suspended / disabled, `until`, `reason`, `notice`). An
 * owner's End Group writes only `endedAt`; iOS turns that into the state "ended", and so does this,
 * so every check that stops sending, editing, adding or calling stops there too. Pure.
 */
object GroupMod {
    private fun mod(d: Map<String, Any?>) = d["mod"] as? Map<*, *>

    /** The state: "ended" when the owner ended the group, else Fariin's `mod.state` (unknown -> "ok"). */
    fun state(d: Map<String, Any?>): String {
        if (d["endedAt"] != null && (d["type"] as? String) == "group") return "ended"
        val s = mod(d)?.get("state") as? String ?: "ok"
        return if (s in listOf("ok", "limited", "suspended", "disabled", "ended")) s else "ok"
    }

    fun ended(d: Map<String, Any?>): Boolean = state(d) == "ended"

    /** A suspension's end in ms; 0 = no end date. */
    fun untilMillis(d: Map<String, Any?>): Double =
        (mod(d)?.get("until") as? com.google.firebase.Timestamp)?.toDate()?.time?.toDouble()
            ?: (mod(d)?.get("until") as? Number)?.toDouble() ?: 0.0

    /** blocksSending: nobody may send (suspended and not over, disabled, or ended by its owner). */
    fun blocksSending(d: Map<String, Any?>, nowMs: Double = System.currentTimeMillis().toDouble()): Boolean {
        return when (state(d)) {
            "disabled", "ended" -> true
            "suspended" -> untilMillis(d).let { it <= 0 || it > nowMs }
            else -> false
        }
    }

    /** noticeText: the sentence in place of the message bar (iOS words). */
    fun noticeText(d: Map<String, Any?>): String {
        val state = state(d)
        if (state == "ended") return "This group has ended."
        var text = (mod(d)?.get("notice") as? String ?: "").take(200)
        if (text.isEmpty()) {
            val why = when (mod(d)?.get("reason") as? String ?: "") {
                "spam" -> "spam"
                "scam" -> "scams or fraud"
                "copyright" -> "copyright complaints"
                "fake" -> "impersonation"
                "abuse" -> "abuse or violence"
                "adult" -> "adult content"
                "illegal" -> "illegal content"
                else -> "breaking the Fariin rules"
            }
            text = if (state == "disabled") "This group was closed for $why." else "This group is suspended for $why."
        }
        val until = untilMillis(d)
        if (state == "suspended" && until > 0) {
            val f = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            text += " Until " + f.format(java.util.Date(until.toLong())) + "."
        }
        return text
    }

    /** The bar's sentence when sending is closed, else null. */
    fun sendBlock(d: Map<String, Any?>, nowMs: Double = System.currentTimeMillis().toDouble()): String? =
        if (blocksSending(d, nowMs)) noticeText(d) else null
}
