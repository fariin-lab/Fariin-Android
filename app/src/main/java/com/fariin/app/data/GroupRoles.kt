package com.fariin.app.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * A member's tag in a group (iOS GroupRole, the `roles.<uid>` map): a few words, an optional emoji,
 * and one of eight colours (grey = none). Everyone in the group sees it next to the name.
 */
data class GroupRole(val text: String, val emoji: String? = null, val color: String? = null) {
    val display: String get() = emoji?.let { "$it $text" } ?: text

    companion object {
        const val MAX_TEXT = 24
        const val MAX_EMOJI = 16
        /** iOS GroupRole.colors, in the editor's order. */
        val colors: List<Pair<String, Long>> = listOf(
            "grey" to 0x8E8E93, "blue" to 0x2392FE, "green" to 0x22A55B, "teal" to 0x14B8A6,
            "orange" to 0xF59E0B, "red" to 0xEF4444, "pink" to 0xEC4899, "purple" to 0x8B5CF6,
        )
        fun rgb(name: String?): Long? = name?.takeIf { it != "grey" }?.let { n -> colors.firstOrNull { it.first == n }?.second }

        fun from(raw: Any?): GroupRole? {
            val m = raw as? Map<*, *> ?: return null
            val t = (m["text"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
            return GroupRole(t, (m["emoji"] as? String)?.takeIf { it.isNotEmpty() }, (m["color"] as? String)?.takeIf { rgb(it) != null })
        }

        /** Every member's tag in a group record. */
        fun all(doc: Map<String, Any?>?): Map<String, GroupRole> =
            (doc?.get("roles") as? Map<*, *>)?.mapNotNull { (k, v) -> (k as? String)?.let { uid -> from(v)?.let { uid to it } } }?.toMap().orEmpty()

        /** iOS GroupRole.clean: one line, single spaces, at most [MAX_TEXT] UTF-16 units. */
        fun clean(raw: String): String = fit(raw.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" "), MAX_TEXT)

        fun fit(s: String, max: Int): String {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val step = Character.charCount(s.codePointAt(i))
                if (out.length + step > max) break
                out.append(s, i, i + step); i += step
            }
            return out.toString()
        }

        /** iOS canSetRole: not in a channel; the owner for anyone, an admin with Change info for anyone but the owner. */
        fun canSet(me: String, target: String, doc: Map<String, Any?>): Boolean {
            if (doc["channel"] == true) return false
            val team = GroupAdmins.Team.from(doc)
            if (target !in team.users) return false
            if (team.isOwner(me)) return true
            return team.can(me, GroupAdmins.Right.CHANGE_INFO) && !team.isOwner(target)
        }

        /** iOS ChatService.setRole: the tag, or its removal when [text] cleans to nothing. */
        suspend fun set(cid: String, me: String, target: String, text: String, emoji: String?, color: String?) {
            val clean = clean(text)
            val role = buildMap<String, Any> {
                put("text", clean)
                emoji?.let { fit(it, MAX_EMOJI) }?.takeIf { it.isNotEmpty() }?.let { put("emoji", it) }
                color?.takeIf { rgb(it) != null }?.let { put("color", it) }
            }
            FirebaseFirestore.getInstance().collection("conversations").document(cid).update(mapOf(
                "roles.$target" to (if (clean.isEmpty()) FieldValue.delete() else role),
                "roleEdit" to mapOf("uid" to target, "by" to me),
            )).await()
        }
    }
}
