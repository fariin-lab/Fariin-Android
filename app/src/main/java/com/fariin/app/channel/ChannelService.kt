package com.fariin.app.channel

import com.fariin.app.data.AuthRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The channel's server calls and my own writes, to the iOS contract (Kulan-iOS ChannelService.swift,
 * GroupDirectory.swift; server functions-groups). Callables live in me-central1.
 *
 *   discoverChannels {country?}            -> {country, channels:[{cid, handle, title, photoUrl, description, followerCount, admins, verified, label, official, local}]}
 *   publicGroupPreview {cid? | username?}   -> {cid, username, title, photoUrl, description, memberCount, followerCount, isMember, channel, createdAt, verified, label}
 *   followChannel {cid, code?}              -> {status: following|already|requested, cid, followerCount?, pending?}
 *   unfollowChannel {cid}                   -> {followerCount?, pending?}
 *   reactChannelPost {cid, mid, emoji?}     -> {counts:{emoji:n}}   (no emoji = take mine back)
 *   channelViews {cid, mids:[<=100]}        -> {counted}
 *   makeChannel {cid, country?}             -> {ok, cid}
 *   listChannelFollowers {cid, after?}      -> {followers:[{uid, name, photoUrl, at}], more}
 *   removeChannelFollower {cid, uid, ban}   -> {ok}
 *   makeChannelAdmin {cid, uid}             -> {state: admin|already}
 *   dismissChannelAdmin {cid, uid}          -> {ok}
 *
 * Direct writes (firestore.rules allow them): users/{me}/channels/{cid} read / muted / mutedUntil,
 * reports, a channel's restrictedBy (Protect Content, admins).
 */
object ChannelService {
    private val fn get() = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)
    private val db get() = FirebaseFirestore.getInstance()
    private val me get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    private fun region(): String? = Locale.getDefault().country.takeIf { it.length == 2 }

    private suspend fun call(name: String, data: Map<String, Any?>, seconds: Long = 15): Map<*, *>? =
        fn.getHttpsCallable(name).withTimeout(seconds, TimeUnit.SECONDS).call(data).await().getData() as? Map<*, *>

    /** The words a refusal carries, or the iOS offline / generic line. */
    fun errorText(e: Exception, fallback: String = "Couldn't connect. Check your connection and try again."): String =
        (e as? FirebaseFunctionsException)?.takeIf {
            it.code == FirebaseFunctionsException.Code.NOT_FOUND || it.code == FirebaseFunctionsException.Code.PERMISSION_DENIED ||
                it.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION || it.code == FirebaseFunctionsException.Code.INVALID_ARGUMENT
        }?.message?.takeIf { it.isNotBlank() } ?: fallback

    /** iOS ChannelService.similar: up to 10 channels like this one (none under 1,000 followers). */
    suspend fun similar(cid: String): List<DiscoveredChannel> =
        runCatching { DiscoveredChannel.list(call("getSimilarChannels", mapOf("cid" to cid))) }.getOrDefault(emptyList())

    suspend fun discover(): List<DiscoveredChannel> =
        DiscoveredChannel.list(call("discoverChannels", buildMap { region()?.let { put("country", it) } }))

    sealed interface PreviewResult {
        data class Found(val preview: ChannelPreview) : PreviewResult
        data class Refused(val text: String) : PreviewResult
        data class Failed(val text: String) : PreviewResult
    }

    private const val NOT_PUBLIC = "This is private now. Ask one of its admins for an invite link."

    suspend fun preview(cid: String?, username: String? = null): PreviewResult {
        val args = buildMap<String, Any?> {
            if (!username.isNullOrEmpty()) put("username", username)
            if (cid != null && ChannelRules.safeId(cid)) put("cid", cid)
        }
        if (args.isEmpty()) return PreviewResult.Refused(NOT_PUBLIC)
        return try {
            ChannelPreview.from(call("publicGroupPreview", args))?.let { PreviewResult.Found(it) } ?: PreviewResult.Refused(NOT_PUBLIC)
        } catch (e: FirebaseFunctionsException) {
            if (e.code == FirebaseFunctionsException.Code.NOT_FOUND || e.code == FirebaseFunctionsException.Code.PERMISSION_DENIED)
                PreviewResult.Refused(e.message ?: NOT_PUBLIC)
            else PreviewResult.Failed("Couldn't connect. Check your connection and try again.")
        } catch (_: Exception) {
            PreviewResult.Failed("Couldn't connect. Check your connection and try again.")
        }
    }

    /** "following", "already" or "requested". My +1 is noted before the server answers. */
    suspend fun follow(cid: String, code: String? = null): String {
        ChannelFollows.bias(cid, +1)
        val r = try {
            call("followChannel", buildMap { put("cid", cid); if (!code.isNullOrEmpty()) put("code", code) })
        } catch (e: Exception) { ChannelFollows.bias(cid, -1); throw e }
        val status = r?.get("status") as? String ?: "following"
        if (status == "requested") ChannelFollows.bias(cid, -1) else ChannelFollows.adoptCount(cid, r)
        return status
    }

    suspend fun unfollow(cid: String) {
        ChannelFollows.bias(cid, -1)
        val r = try { call("unfollowChannel", mapOf("cid" to cid)) } catch (e: Exception) { ChannelFollows.bias(cid, +1); throw e }
        ChannelFollows.adoptCount(cid, r)
    }

    /** The post's counts with my change in them, as the server answers (null if it sent none). */
    suspend fun react(cid: String, mid: String, emoji: String?): Map<String, Int>? {
        val r = call("reactChannelPost", buildMap { put("cid", cid); put("mid", mid); if (emoji != null) put("emoji", emoji) })
        val raw = r?.get("counts") as? Map<*, *> ?: return null
        return raw.entries.mapNotNull { (k, v) -> val n = (v as? Number)?.toInt() ?: 0; if (k is String && n > 0) k to n else null }.toMap()
    }

    /** Count my view of these posts once each (the server keeps who; 100 per call). */
    suspend fun views(cid: String, mids: List<String>) {
        for (part in mids.filter(ChannelRules::safeId).distinct().chunked(100)) {
            call("channelViews", mapOf("cid" to cid, "mids" to part))
        }
    }

    suspend fun makeChannel(cid: String) {
        call("makeChannel", buildMap { put("cid", cid); region()?.let { put("country", it) } })
    }

    // ---- New channel (NewChannelView.create) --------------------------------------------------

    /**
     * The group that becomes the channel: me alone, admins only send (ChatService.createGroup with
     * onlyAdminsSend and the "Channel created" line), plain like every new group. Returns its id.
     */
    suspend fun createGroupForChannel(title: String, myName: String, myPhoto: String?): String {
        val uid = me
        val ref = db.collection("conversations").document()
        val data = hashMapOf<String, Any>(
            "type" to "group", "title" to title.trim().take(ChannelRules.NAME_MAX), "users" to listOf(uid), "admins" to listOf(uid),
            "createdBy" to uid, "createdAt" to FieldValue.serverTimestamp(), "names" to mapOf(uid to myName),
            "photos" to (myPhoto?.let { mapOf(uid to it) } ?: emptyMap()),
            "unreadCount" to mapOf(uid to 0), "typing" to mapOf(uid to false),
            "updatedAt" to FieldValue.serverTimestamp(), "e2ee" to false, "onlyAdminsSend" to true,
        )
        ref.set(data).await()
        com.fariin.app.data.CryptoHub.notePlainGroup(ref.id)
        val batch = db.batch()
        batch.set(ref.collection("messages").document(), mapOf("text" to "Channel created", "authorId" to uid, "type" to "system",
            "createdAt" to FieldValue.serverTimestamp()))
        batch.update(ref, mapOf("lastMessage" to "Channel created", "lastSender" to uid, "updatedAt" to FieldValue.serverTimestamp()))
        batch.commit().await()
        return ref.id
    }

    /** makeChannel, tried up to six times with a growing wait (the group must be visible to it first). */
    suspend fun makeChannelWithRetry(cid: String): Boolean {
        repeat(6) { attempt ->
            try { makeChannel(cid); return true } catch (_: Exception) {}
            kotlinx.coroutines.delay(400L * (attempt + 1))
        }
        return false
    }

    suspend fun setDescription(cid: String, text: String) {
        val t = text.trim().take(ChannelRules.ABOUT_MAX)
        if (t.isEmpty()) return
        db.collection("conversations").document(cid).update(mapOf("desc" to t, "updatedAt" to FieldValue.serverTimestamp())).await()
    }

    data class Link(val code: String, val requestApproval: Boolean)

    private fun link(d: Map<*, *>?): Link? {
        val l = d ?: return null
        val code = l["code"] as? String ?: return null
        return Link(code, l["requestApproval"] as? Boolean ?: false)
    }

    /** The invite link people follow a private channel with (createGroupLink primary). */
    suspend fun createPrimaryLink(cid: String): Link? = link(call("createGroupLink", mapOf("cid" to cid, "primary" to true))?.get("link") as? Map<*, *>)

    suspend fun primaryLink(cid: String): Link? = link(call("listGroupLinks", mapOf("cid" to cid))?.get("primary") as? Map<*, *>)

    /** Approve New Followers on the primary link (editGroupLink requestApproval). */
    suspend fun setApproval(cid: String, code: String, on: Boolean): Link? =
        link(call("editGroupLink", mapOf("cid" to cid, "code" to code, "requestApproval" to on))?.get("link") as? Map<*, *>)

    /** checkGroupUsername: null = available, else the words to show. */
    suspend fun checkLink(cid: String, name: String): String? {
        ChannelRules.linkProblem(name)?.let { return it }
        return try {
            val d = call("checkGroupUsername", mapOf("cid" to cid, "username" to name))
            when {
                d?.get("available") == true -> null
                d?.get("code") == "USERNAME_TAKEN" -> "This link is already taken."
                else -> "This link can't be used."
            }
        } catch (_: Exception) { "Couldn't check the link. Try again." }
    }

    /** Public under `name`, or private with "" (setGroupUsername). */
    suspend fun setLink(cid: String, name: String) { call("setGroupUsername", mapOf("cid" to cid, "username" to name)) }

    // ---- Followers and admins (ChannelService.followers / removeFollower / dismissAdmin, ChannelAdminInvites.makeAdmin)

    data class Follower(val uid: String, val name: String, val photoUrl: String?)
    data class FollowerPage(val rows: List<Follower>, val more: Boolean)

    /** listChannelFollowers {cid, after?} -> {followers:[{uid, name, photoUrl, at}], more}: 50 a page, newest first. Admins only. */
    suspend fun followers(cid: String, after: String?): FollowerPage {
        val d = call("listChannelFollowers", buildMap { put("cid", cid); if (!after.isNullOrEmpty()) put("after", after) })
        val rows = (d?.get("followers") as? List<*>)?.mapNotNull { f ->
            val m = f as? Map<*, *> ?: return@mapNotNull null
            val uid = m["uid"] as? String ?: return@mapNotNull null
            if (uid.isEmpty()) return@mapNotNull null
            Follower(uid, m["name"] as? String ?: "Fariin user", (m["photoUrl"] as? String)?.takeIf { it.isNotEmpty() })
        } ?: emptyList()
        return FollowerPage(rows, d?.get("more") as? Boolean ?: false)
    }

    /** removeChannelFollower {cid, uid, ban}: they stop following; `ban` keeps them out (bannedUids). */
    suspend fun removeFollower(cid: String, uid: String, ban: Boolean) {
        call("removeChannelFollower", mapOf("cid" to cid, "uid" to uid, "ban" to ban))
    }

    /** makeChannelAdmin {cid, uid}: a follower becomes an admin at once, with the default rights. */
    suspend fun makeAdmin(cid: String, uid: String) { call("makeChannelAdmin", mapOf("cid" to cid, "uid" to uid)) }

    /** An admin invite still waiting for its answer (iOS ChannelAdminInvites.Pending). */
    data class PendingAdmin(val code: String, val uid: String, val name: String, val photoUrl: String?)

    /** listAdminInvites {cid} -> {invites:[{code, uid, name, photoUrl, expiresAt}]} (iOS ChannelAdminInvites.pending). */
    suspend fun pendingAdmins(cid: String): List<PendingAdmin> {
        val d = call("listAdminInvites", mapOf("cid" to cid))
        return (d?.get("invites") as? List<*>)?.mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            val code = m["code"] as? String ?: return@mapNotNull null
            val uid = m["uid"] as? String ?: return@mapNotNull null
            if (m["expiresAt"] !is Number) return@mapNotNull null
            PendingAdmin(code, uid, m["name"] as? String ?: "Fariin user", (m["photoUrl"] as? String)?.takeIf { it.isNotEmpty() })
        } ?: emptyList()
    }

    /** revokeAdminInvite {code}: the invite can no longer be used (iOS ChannelAdminInvites.revoke). */
    suspend fun revokeAdminInvite(code: String) { call("revokeAdminInvite", mapOf("code" to code)) }

    /** dismissChannelAdmin {cid, uid}: they stop being an admin and follow the channel again. */
    suspend fun dismissAdmin(cid: String, uid: String) { call("dismissChannelAdmin", mapOf("cid" to cid, "uid" to uid)) }

    /** ChatService.setAdminRights: a direct write, `adminRights.<uid>` with `adminEdit` naming who (firestore.rules). */
    suspend fun setAdminRights(cid: String, uid: String, rights: List<String>) {
        db.collection("conversations").document(cid).update(mapOf(
            "adminRights.$uid" to rights, "adminEdit" to mapOf("uid" to uid, "by" to me),
            "updatedAt" to FieldValue.serverTimestamp(),
        )).await()
    }

    // ---- Direct writes ------------------------------------------------------------------------

    /** I have read up to `count` posts (only ever forward). */
    fun markRead(cid: String, count: Int) {
        val uid = me
        val cur = ChannelFollows.entry(cid)?.read ?: return
        if (uid.isEmpty() || count <= cur) return
        ChannelFollows.noteLocal(cid, read = count)
        db.collection("users").document(uid).collection("channels").document(cid).update("read", count)
    }

    /** Mute (until ms, 0 = always) or unmute my follow entry; the server skips muted followers. */
    suspend fun setMuted(cid: String, muted: Boolean, until: Double = 0.0): Boolean {
        val uid = me
        if (uid.isEmpty()) return false
        ChannelFollows.noteLocal(cid, muted = muted, mutedUntil = if (muted) until else 0.0)
        return try {
            db.collection("users").document(uid).collection("channels").document(cid)
                .update(mapOf("muted" to muted, "mutedUntil" to if (muted) until else 0.0)).await()
            true
        } catch (_: Exception) { false }
    }

    /**
     * Pinned / archived on MY follow entry, as iOS ChannelService.setFlag: a follower may not write
     * the channel's own document (the archive swipe on a followed channel was refused, 2026-11-06).
     * Shown at once; put back if the server refuses.
     */
    suspend fun setFlag(cid: String, key: String, on: Boolean): Boolean {
        val uid = me
        if (uid.isEmpty() || (key != "pinned" && key != "archived")) return false
        ChannelFollows.noteLocal(cid, pinned = if (key == "pinned") on else null, archived = if (key == "archived") on else null)
        return try {
            db.collection("users").document(uid).collection("channels").document(cid).update(key, on).await()
            true
        } catch (_: Exception) {
            ChannelFollows.noteLocal(cid, pinned = if (key == "pinned") !on else null, archived = if (key == "archived") !on else null)
            false
        }
    }

    /** ChatService.report: reason "channel" for the whole channel, "message" for one post. */
    suspend fun report(cid: String, messageId: String? = null): Boolean = try {
        val data = hashMapOf<String, Any>("reporterUid" to me, "reportedUid" to "", "cid" to cid,
            "reason" to if (messageId == null) "channel" else "message",
            "createdAt" to System.currentTimeMillis().toDouble(), "handled" to false)
        if (messageId != null) data["messageId"] = messageId
        db.collection("reports").add(data).await()
        true
    } catch (_: Exception) { false }

    /** Protect Content (ChatService.setGroupRestricted): restrictedBy {me: true} or gone. */
    suspend fun setProtected(cid: String, on: Boolean) {
        val f = hashMapOf<String, Any>(
            "restrictedBy" to if (on) mapOf(me to true) else FieldValue.delete(),
            "restrictRequest" to FieldValue.delete(), "updatedAt" to FieldValue.serverTimestamp(),
        )
        for (r in listOf("noScreenshots", "noForwarding", "noSaving")) f[r] = false   // ChatRestriction.allCases
        db.collection("conversations").document(cid).update(f).await()
    }
}
