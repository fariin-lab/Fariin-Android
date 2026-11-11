package com.fariin.app.data

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/** One public group or channel from global search (iOS PublicGroupHit): only what its join page shows anybody. */
data class PublicGroupHit(
    val cid: String, val handle: String, val title: String, val photoUrl: String?,
    val memberCount: Int, val isMember: Boolean, val verified: Boolean, val isChannel: Boolean,
) {
    /** "@name · 12 members" (a channel counts followers). */
    val subtitle: String get() {
        if (memberCount <= 0) return "@$handle"
        val word = if (isChannel) (if (memberCount == 1) "follower" else "followers") else (if (memberCount == 1) "member" else "members")
        return "@$handle · $memberCount $word"
    }
}

/**
 * Public groups and channels (iOS GroupDirectory): an @name in the same namespace as people, found in
 * global search (`searchUsernames` with groupsOnly, its own rate budget) and joined through the server
 * (`joinPublicGroup`). Every write goes through the server; the group document stays members-only.
 */
object PublicGroups {
    private val fns get() = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)

    suspend fun search(query: String): List<PublicGroupHit> {
        val q = query.trim().removePrefix("@")
        if (q.length < 3) return emptyList()
        val rows = runCatching {
            (fns.getHttpsCallable("searchUsernames").withTimeout(8, TimeUnit.SECONDS)
                .call(mapOf("query" to q, "groupsOnly" to true)).await().getData() as? Map<*, *>)?.get("results") as? List<*>
        }.getOrNull().orEmpty()
        return rows.mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            if (m["kind"] != "group") return@mapNotNull null
            val cid = (m["cid"] as? String)?.takeIf { com.fariin.app.channel.ChannelRules.safeId(it) } ?: return@mapNotNull null
            val handle = (m["handle"] as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            PublicGroupHit(cid, handle, (m["title"] as? String)?.trim()?.ifEmpty { null } ?: "Group",
                (m["photoUrl"] as? String)?.ifEmpty { null }, (m["memberCount"] as? Number)?.toInt() ?: 0,
                m["isMember"] == true, m["verified"] == true, m["channel"] == true)
        }
    }

    /** A bare fariin.com/<name> that is a public group's @name: its id, else null (iOS GroupDirectory.resolve). */
    suspend fun resolveGroup(name: String): String? = runCatching {
        val d = fns.getHttpsCallable("resolveUsername").withTimeout(8, TimeUnit.SECONDS)
            .call(mapOf("username" to name.trim().removePrefix("@"), "groups" to true)).await().getData() as? Map<*, *>
        if (d?.get("found") == true && d["kind"] == "group") (d["cid"] as? String)?.takeIf { com.fariin.app.channel.ChannelRules.safeId(it) } else null
    }.getOrNull()

    /** Explore's Groups tab (iOS GroupDirectory.discover): the biggest public groups the panel allows, pinned first. */
    suspend fun discover(): List<com.fariin.app.channel.DiscoveredGroup> {
        val d = fns.getHttpsCallable("discoverGroups").withTimeout(20, TimeUnit.SECONDS).call(emptyMap<String, Any>()).await().getData() as? Map<*, *>
        return (d?.get("groups") as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(::discovered) } ?: emptyList()
    }

    fun discovered(d: Map<*, *>): com.fariin.app.channel.DiscoveredGroup? {
        val cid = (d["cid"] as? String)?.takeIf { com.fariin.app.channel.ChannelRules.safeId(it) } ?: return null
        return com.fariin.app.channel.DiscoveredGroup(cid, d["handle"] as? String ?: "",
            (d["title"] as? String)?.takeIf { it.isNotBlank() } ?: "Group", (d["photoUrl"] as? String)?.ifEmpty { null },
            (d["memberCount"] as? Number)?.toInt() ?: 0, d["verified"] == true, d["label"] as? String ?: "")
    }

    /** "joined", "requested" or "already"; a refusal throws with the server's own words. */
    suspend fun join(cid: String): String = try {
        (fns.getHttpsCallable("joinPublicGroup").withTimeout(20, TimeUnit.SECONDS)
            .call(mapOf("cid" to cid)).await().getData() as? Map<*, *>)?.get("status") as? String ?: "joined"
    } catch (e: FirebaseFunctionsException) {
        throw IllegalStateException(e.message ?: "Couldn't join. Try again.")
    } catch (e: Exception) {
        throw IllegalStateException("Couldn't join. Check your connection and try again.")
    }
}
