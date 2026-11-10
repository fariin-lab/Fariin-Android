package com.fariin.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * The group management writes and calls, to iOS ChatService (promoteGroupAdmin, demoteGroupAdmin,
 * setAdminRights, removeGroupMember, setOnlyAdminsSend, setMemberSend, setMemberSendKind,
 * setGroupSlowMode, setGroupPermission, setGroupRestricted), GroupInviteService (the link
 * callables) and GroupDirectory (checkGroupUsername / setGroupUsername). Direct writes go through
 * firestore.rules as the iPhone's do; links and the public name are the server's.
 *
 *   createGroupLink {cid, primary?, name?, expireAt?, usageLimit?, requestApproval?} -> {link}
 *   editGroupLink {cid, code, name?, expireAt?, usageLimit?, requestApproval?}       -> {link}
 *   revokeGroupLink {cid, code}                                                      -> {link}
 *   deleteGroupLink {cid, code} | {cid, allRevoked: true}                            -> {deleted}
 *   listGroupLinks {cid}                                -> {primary, links, revoked, isOwner}
 *   checkGroupUsername {cid, username} -> {available, code, reason}; setGroupUsername {cid, username ("" = private)}
 */
object GroupManage {
    private val db get() = FirebaseFirestore.getInstance()
    private val fn get() = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)
    private val me get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    private fun conv(cid: String) = db.collection("conversations").document(cid)

    private suspend fun call(name: String, data: Map<String, Any?>, seconds: Long = 20): Map<*, *>? =
        fn.getHttpsCallable(name).withTimeout(seconds, TimeUnit.SECONDS).call(data).await().getData() as? Map<*, *>

    /** GroupInviteService.errorText: the server's sentence when it is one to act on, else the fallback. */
    fun errorText(e: Exception, fallback: String): String {
        val f = e as? FirebaseFunctionsException ?: return fallback
        val told = setOf(FirebaseFunctionsException.Code.INVALID_ARGUMENT, FirebaseFunctionsException.Code.PERMISSION_DENIED,
            FirebaseFunctionsException.Code.NOT_FOUND, FirebaseFunctionsException.Code.FAILED_PRECONDITION,
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED, FirebaseFunctionsException.Code.DEADLINE_EXCEEDED)
        return if (f.code in told) f.message?.takeIf { it.isNotBlank() } ?: fallback else fallback
    }

    /** myName(): my name as the group shows it, else my profile's. */
    internal suspend fun myName(cid: String): String {
        val fromGroup = runCatching { (conv(cid).get().await().get("names") as? Map<*, *>)?.get(me) as? String }.getOrNull()
        if (!fromGroup.isNullOrBlank()) return fromGroup
        return runCatching { db.collection("users").document(me).get().await().getString("name") }.getOrNull()?.ifBlank { null } ?: "Someone"
    }

    /**
     * Chat History for new members (iOS ChatService.setHideHistory, owner 2026-10-20): Hidden stamps
     * the moment (server time); Visible removes it. The group is told in one line.
     */
    suspend fun setHideHistory(cid: String, hidden: Boolean) {
        conv(cid).update(mapOf("hideHistoryAt" to (if (hidden) FieldValue.serverTimestamp() else FieldValue.delete()),
            "updatedAt" to FieldValue.serverTimestamp())).await()
        val who = myName(cid)
        runCatching { systemLine(cid, if (hidden) "$who hid chat history from new members" else "$who made chat history visible to new members") }
    }

    /**
     * Where my chat in a group starts (iOS Conversation.historyStart): my join time when I came in
     * after history was hidden; null (everything) otherwise, including for anyone already in it then.
     */
    fun historyStart(doc: Map<String, Any?>, me: String): Double? {
        val hide = com.fariin.app.data.Conversation.millis(doc["hideHistoryAt"])
        if (hide <= 0) return null
        val since = com.fariin.app.data.Conversation.millis((doc["memberSince"] as? Map<*, *>)?.get(me))
        return if (since > 0 && since >= hide) since else null
    }

    /** writeSystemMessage: the line, and the chat list summary pointing at it. */
    internal suspend fun systemLine(cid: String, text: String) {
        val b = db.batch()
        b.set(conv(cid).collection("messages").document(), mapOf("text" to text, "authorId" to me, "type" to "system",
            "createdAt" to FieldValue.serverTimestamp()))
        b.update(conv(cid), mapOf("lastMessage" to text, "lastSender" to me, "updatedAt" to FieldValue.serverTimestamp()))
        b.commit().await()
    }

    // ---- Admins --------------------------------------------------------------------------------

    /** promoteGroupAdmin: no chat line (iOS 2026-10-23); restrictions cleared only by who may lift them. */
    suspend fun promote(cid: String, uid: String, rights: List<String>, team: GroupAdmins.Team) {
        val data = hashMapOf<String, Any>(
            "admins" to FieldValue.arrayUnion(uid),
            "adminRights.$uid" to rights,
            "adminBy.$uid" to me,
            "adminEdit" to mapOf("uid" to uid, "by" to me),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        if (team.isOwner(me) || team.can(me, GroupAdmins.Right.BAN_USERS)) {
            data["restrictedFlags.$uid"] = FieldValue.delete()
            data["restrictedUntil.$uid"] = FieldValue.delete()
        }
        conv(cid).update(data).await()
    }

    /** demoteGroupAdmin. */
    suspend fun demote(cid: String, uid: String) {
        conv(cid).update(mapOf(
            "admins" to FieldValue.arrayRemove(uid),
            "adminRights.$uid" to FieldValue.delete(),
            "adminBy.$uid" to FieldValue.delete(),
            "adminEdit" to mapOf("uid" to uid, "by" to me),
            "updatedAt" to FieldValue.serverTimestamp(),
        )).await()
    }

    /** setAdminRights. */
    suspend fun setRights(cid: String, uid: String, rights: List<String>) {
        conv(cid).update(mapOf(
            "adminRights.$uid" to rights,
            "adminEdit" to mapOf("uid" to uid, "by" to me),
            "updatedAt" to FieldValue.serverTimestamp(),
        )).await()
    }

    /**
     * removeGroupMember: ONE batch. Out of users and admins, onto bannedUids (a removal sticks
     * until they are added again), their admin and restriction state gone; the "<me> removed X"
     * line only when the message rule would take mine.
     */
    suspend fun removeMember(cid: String, uid: String, name: String) {
        val ref = conv(cid)
        val d = ref.get().await().data ?: emptyMap()
        val mod = (d["mod"] as? Map<*, *>)?.get("state") as? String ?: "ok"
        val canNotice = GroupAdmins.announcementBlock(d, me) == null && mod == "ok"
        val update = hashMapOf<String, Any>(
            "users" to FieldValue.arrayRemove(uid),
            "admins" to FieldValue.arrayRemove(uid),
            "bannedUids" to FieldValue.arrayUnion(uid),
            "adminRights.$uid" to FieldValue.delete(),
            "adminBy.$uid" to FieldValue.delete(),
            "restrictedFlags.$uid" to FieldValue.delete(),
            "restrictedUntil.$uid" to FieldValue.delete(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        val b = db.batch()
        if (canNotice) {
            val text = "${myName(cid)} removed $name"
            b.set(ref.collection("messages").document(), mapOf("text" to text, "authorId" to me, "type" to "system",
                "createdAt" to FieldValue.serverTimestamp()))
            update["lastMessage"] = text
            update["lastSender"] = me
            update["unreadCount.$uid"] = FieldValue.delete()
        }
        b.update(ref, update)
        b.commit().await()
    }

    // ---- Member restrictions (iOS ChatService.restrictMember / muteMember / unmuteMember) --------

    /**
     * restrictMember: `restrictedFlags.<uid>` and `restrictedUntil.<uid>` (epoch ms), or both
     * deleted when [flags] is empty or [until] is not ahead. The rules: the Remove members right,
     * and never for the owner or an admin. The chat line is best effort, as iOS's `try?`.
     */
    suspend fun restrictMember(cid: String, uid: String, name: String, flags: List<String>, until: Double) {
        val lift = flags.isEmpty() || until <= System.currentTimeMillis()
        conv(cid).update(if (lift) mapOf(
            "restrictedFlags.$uid" to FieldValue.delete(),
            "restrictedUntil.$uid" to FieldValue.delete(),
            "updatedAt" to FieldValue.serverTimestamp(),
        ) else mapOf(
            "restrictedFlags.$uid" to flags,
            "restrictedUntil.$uid" to until,
            "updatedAt" to FieldValue.serverTimestamp(),
        )).await()
        runCatching { systemLine(cid, if (lift) "${myName(cid)} lifted the restrictions on $name" else "${myName(cid)} restricted $name") }
    }

    /** muteMember: may send nothing for [seconds] (0 = until lifted, about 50 years). */
    suspend fun muteMember(cid: String, uid: String, name: String, seconds: Double) =
        restrictMember(cid, uid, name, MemberRestrict.MUTE_ALL, MemberRestrict.muteEnd(seconds, System.currentTimeMillis().toDouble()))

    /** unmuteMember: every restriction lifted. */
    suspend fun unmuteMember(cid: String, uid: String, name: String) = restrictMember(cid, uid, name, emptyList(), 0.0)

    /**
     * ChatService.reportSpam (the admin delete sheet): the long-press Report's `reports` doc with
     * reason "spam" and the ids of the sender's messages in this delete (100 at most), then the
     * day's report counter. Best effort, as iOS.
     */
    suspend fun reportSpam(reportedUid: String, cid: String, messageIds: List<String>) {
        val data = hashMapOf<String, Any>("reporterUid" to me, "reportedUid" to reportedUid, "cid" to cid, "reason" to "spam",
            "createdAt" to System.currentTimeMillis().toDouble(), "handled" to false)
        if (messageIds.isNotEmpty()) { data["messageIds"] = messageIds.take(100); data["messageId"] = messageIds.first() }
        val ok = runCatching { db.collection("reports").add(data).await() }.isSuccess
        if (ok) countDaily("reports")
    }

    /** MessageRequests.countDaily: `users/{me}/limits/{name}`, a 24 h window. Best effort. */
    private suspend fun countDaily(name: String) {
        val ref = db.collection("users").document(me).collection("limits").document(name)
        try {
            val snap = ref.get().await()
            val start = snap.getTimestamp("windowStart")?.toDate()?.time
            if (snap.exists() && start != null && System.currentTimeMillis() - start < 24 * 3600_000L) {
                ref.update("count", FieldValue.increment(1)).await()
            } else {
                ref.set(mapOf("windowStart" to FieldValue.serverTimestamp(), "count" to 1)).await()
            }
        } catch (_: Exception) {}
    }

    /**
     * unbanGroupMember (iOS Banned Users): off `bannedUids`, so a link or a request works for them
     * again. It does not add them back. The rules: the Remove members right (or Add members, shrink only).
     */
    suspend fun unban(cid: String, uid: String) {
        conv(cid).update(mapOf("bannedUids" to FieldValue.arrayRemove(uid), "updatedAt" to FieldValue.serverTimestamp())).await()
    }

    // ---- Permissions ---------------------------------------------------------------------------

    /** setOnlyAdminsSend, with its line. */
    suspend fun setOnlyAdminsSend(cid: String, value: Boolean) {
        conv(cid).update(mapOf("onlyAdminsSend" to value, "updatedAt" to FieldValue.serverTimestamp())).await()
        val n = myName(cid)
        systemLine(cid, if (value) "$n restricted messaging to admins only" else "$n allowed everyone to send messages")
    }

    /** setGroupPermission: membersCanAdd, membersCanEditInfo or onlyAdminsCall. No line. */
    suspend fun setPermission(cid: String, key: String, value: Boolean) {
        require(key in setOf("membersCanAdd", "membersCanEditInfo", "onlyAdminsCall"))
        conv(cid).update(mapOf(key to value, "updatedAt" to FieldValue.serverTimestamp())).await()
    }

    /** setMemberSend: every kind at once, with the "allowed all media" / "turned off all media" line. */
    suspend fun setAllMedia(cid: String, on: Boolean) {
        val all = GroupAdmins.SendKind.entries.associate { it.slug to on }
        conv(cid).update(mapOf("memberSend" to all, "updatedAt" to FieldValue.serverTimestamp())).await()
        systemLine(cid, "${myName(cid)} ${if (on) "allowed all media" else "turned off all media for members"}")
    }

    /** setMemberSendKind: one field path; photos/videos drop the old `media` key, keeping the other's value. */
    suspend fun setKind(cid: String, kind: GroupAdmins.SendKind, on: Boolean, d: Map<String, Any?>) {
        val data = hashMapOf<String, Any>("memberSend.${kind.slug}" to on, "updatedAt" to FieldValue.serverTimestamp())
        val ms = d["memberSend"] as? Map<*, *>
        if (ms?.get("media") != null && (kind == GroupAdmins.SendKind.PHOTOS || kind == GroupAdmins.SendKind.VIDEOS)) {
            val other = if (kind == GroupAdmins.SendKind.PHOTOS) GroupAdmins.SendKind.VIDEOS else GroupAdmins.SendKind.PHOTOS
            data["memberSend.${other.slug}"] = GroupAdmins.membersMaySend(d, other)
            data["memberSend.media"] = FieldValue.delete()
        }
        conv(cid).update(data).await()
    }

    /** setGroupSlowMode, with its line. */
    suspend fun setSlowMode(cid: String, seconds: Int) {
        conv(cid).update(mapOf("slowMode" to seconds, "updatedAt" to FieldValue.serverTimestamp())).await()
        systemLine(cid, "${myName(cid)} ${GroupAdmins.slowNotice(seconds)}")
    }

    /** setGroupRestricted (Protect Content): on writes my key, off clears every key. */
    suspend fun setProtected(cid: String, on: Boolean) {
        val f = hashMapOf<String, Any>(
            "restrictedBy" to if (on) mapOf(me to true) else FieldValue.delete(),
            "restrictRequest" to FieldValue.delete(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        for (r in listOf("noScreenshots", "noForwarding", "noSaving")) f[r] = false   // ChatRestriction.allCases
        conv(cid).update(f).await()
        runCatching { systemLine(cid, "${myName(cid)} turned ${if (on) "on" else "off"} Restricted group.") }
    }

    // ---- Links (server) ------------------------------------------------------------------------

    suspend fun listLinks(cid: String): GroupLinkList = GroupLinkList.from(call("listGroupLinks", mapOf("cid" to cid)))

    /** iOS GroupInviteService.shareCode: the primary link's code (getShareLink), "" when the group has none. */
    suspend fun shareCode(cid: String): String =
        (call("getShareLink", mapOf("cid" to cid))?.get("code") as? String)?.takeIf { QrRules.safeSegment(it) } ?: ""

    private fun linkOf(d: Map<*, *>?): GroupLink =
        GroupLink.from(d?.get("link") as? Map<*, *>) ?: throw IllegalStateException("The server sent back no link. Try again.")

    suspend fun createPrimary(cid: String): GroupLink = linkOf(call("createGroupLink", mapOf("cid" to cid, "primary" to true)))

    suspend fun createLink(cid: String, options: Map<String, Any?>): GroupLink =
        linkOf(call("createGroupLink", options + ("cid" to cid)))

    suspend fun editLink(cid: String, code: String, changes: Map<String, Any?>): GroupLink =
        linkOf(call("editGroupLink", changes + mapOf("cid" to cid, "code" to code)))

    suspend fun revokeLink(cid: String, code: String): GroupLink = linkOf(call("revokeGroupLink", mapOf("cid" to cid, "code" to code)))

    suspend fun deleteLink(cid: String, code: String) { call("deleteGroupLink", mapOf("cid" to cid, "code" to code)) }

    suspend fun deleteAllRevoked(cid: String) { call("deleteGroupLink", mapOf("cid" to cid, "allRevoked" to true)) }
}
