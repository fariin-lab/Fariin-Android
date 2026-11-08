package com.fariin.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.fariin.app.crypto.B64
import com.fariin.app.crypto.FariinCrypto
import com.fariin.app.crypto.SafetyNumber
import com.fariin.app.crypto.Wire
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** A person as the people pages show them (`users/{uid}`, iOS UserProfile's fields used here). */
data class Person(
    val uid: String,
    val name: String = "",
    val handle: String = "",
    val photoUrl: String? = null,
    val about: String = "",
    val privacy: Map<String, String> = emptyMap(),
    val hasKey: Boolean = true,
    /** iOS UserProfile.links: up to two, shown under the bio (behind the profileLinks switch). */
    val links: List<ProfileLink> = emptyList(),
    /** iOS Verification.showsBadge: users/{uid}.verification with status active (or isVerified). */
    val verified: Boolean = false,
) {
    val shownName: String get() = name.ifEmpty { handle.ifEmpty { "Fariin user" } }

    companion object {
        fun from(uid: String, d: Map<String, Any?>): Person = Person(
            uid = uid,
            name = d["name"] as? String ?: "",
            handle = d["handle"] as? String ?: "",
            photoUrl = (d["photoUrl"] as? String)?.takeIf { it.isNotEmpty() },
            about = d["about"] as? String ?: "",
            privacy = (d["privacy"] as? Map<*, *>)?.mapNotNull { (k, v) -> (k as? String)?.let { it to (v as? String ?: "") } }?.toMap() ?: emptyMap(),
            hasKey = !(d["publicKey"] as? String).isNullOrEmpty(),
            links = ProfileLink.list(d["links"]),
            verified = (d["verification"] as? Map<*, *>)?.let { v ->
                (v["status"] as? String ?: if (v["isVerified"] == true) "active" else "revoked") == "active"
            } ?: false,
        )

        /** Awaiting deletion or banned: never findable (iOS findByHandle filters). */
        fun hidden(d: Map<String, Any?>): Boolean =
            ((d["deletionScheduledFor"] as? com.google.firebase.Timestamp)?.toDate()?.time ?: 0L) > System.currentTimeMillis() || d["banned"] == true

        /** PrivacyPrefs.allows: everyone, contacts (an accepted 1:1 with me), nobody. */
        fun allows(privacy: Map<String, String>, key: String, contactOfMine: Boolean): Boolean = when (privacy[key]) {
            "nobody" -> false
            "contacts" -> contactOfMine
            else -> true
        }
    }
}

/**
 * Everything the new-chat, requests, profile, edit-profile and group pages read and write. The
 * live one is [FirestorePeople]; the debug demo has its own that never touches the network.
 */
interface PeopleApi {
    val me: String
    suspend fun search(query: String): List<Person>
    /** Exactly the owner of this @username, or null (iOS ChatService.findByHandle). */
    suspend fun byHandle(handle: String): Person? =
        search(handle).firstOrNull { it.handle.equals(handle.trim().trimStart('@'), ignoreCase = true) }
    suspend fun profile(uid: String): Person?
    suspend fun chatKeyExists(uid: String): Boolean?
    /** The chat id the key opened, or the failure sentence. */
    suspend fun verifyChatKey(uid: String, pin: String): Result<String>
    suspend fun openChat(p: Person): String
    suspend fun acceptRequest(cid: String): Boolean
    suspend fun declineRequest(cid: String): Boolean
    suspend fun isBlocked(uid: String): Boolean
    suspend fun setBlocked(uid: String, on: Boolean): Boolean
    suspend fun setMuted(cid: String, on: Boolean): Boolean
    suspend fun safetyNumber(uid: String): String?
    suspend fun checkUsername(h: String): Pair<Boolean, String?>
    suspend fun claimUsername(h: String)
    suspend fun saveName(name: String)
    suspend fun uploadPhoto(jpegOrAny: ByteArray)
    /** iOS ProfileStore.removePhoto: no picture, back to the letter. */
    suspend fun removePhoto() {}
    /** [permissions]: the member switches chosen on New Group (iOS NewGroupView `perms`), written into the new group. */
    suspend fun createGroup(title: String, members: List<String>, photo: ByteArray?, permissions: Map<String, Boolean> = emptyMap()): String
    suspend fun addMembers(cid: String, uids: List<String>): String?
    suspend fun leaveGroup(cid: String)
    suspend fun renameGroup(cid: String, title: String)
    suspend fun setGroupPhoto(cid: String, photo: ByteArray)
    suspend fun removeGroupPhoto(cid: String)
    suspend fun setGroupDescription(cid: String, text: String)
    /** The chat's disappearing timer (iOS ChatService.setDisappear); true when it is now [seconds]. */
    suspend fun setDisappear(cid: String, seconds: Int): Boolean = false
}

class FirestorePeople(
    override val me: String,
    private val crypto: FariinCrypto,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : PeopleApi {
    private val fn get() = FirebaseFunctions.getInstance("me-central1")
    private fun conv(cid: String) = db.collection("conversations").document(cid)

    private suspend fun call(name: String, data: Map<String, Any?>, seconds: Long = 10): Map<*, *>? {
        val c = fn.getHttpsCallable(name).withTimeout(seconds, TimeUnit.SECONDS)
        return c.call(data).await().getData() as? Map<*, *>
    }

    /** ChatService.findByHandle: `usernames/{h}` then the owner's profile (released names lead nowhere). */
    private suspend fun findByHandle(typed: String): Person? {
        val h = Usernames.lookupKey(typed) ?: return null
        val nd = db.collection("usernames").document(h).get().await().data ?: return null
        val owner = nd["uid"] as? String ?: return null
        if (owner.isEmpty() || nd["releasedAt"] != null || owner == me) return null
        val d = db.collection("users").document(owner).get().await().data ?: return null
        return if (Person.hidden(d)) null else Person.from(owner, d)
    }

    /** ChatService.searchUsers: under 3 letters the exact handle; else `searchUsernames` (8 s), else exact. */
    override suspend fun byHandle(handle: String): Person? = findByHandle(handle)

    override suspend fun search(query: String): List<Person> {
        var q = query.trim().lowercase()
        if (q.startsWith("@")) q = q.drop(1)
        if (q.isEmpty()) return emptyList()
        return try {
            if (q.length < 3) return listOfNotNull(findByHandle(q))
            val rows = try { call("searchUsernames", mapOf("query" to q), 8)?.get("results") as? List<*> } catch (_: Exception) { null }
                ?: return listOfNotNull(findByHandle(q))
            val uids = rows.mapNotNull { (it as? Map<*, *>)?.get("uid") as? String }.filter { it != me }
            coroutineScope {
                uids.map { u -> async { withTimeoutOrNull(5_000) { db.collection("users").document(u).get().await() } } }.awaitAll()
            }.mapNotNull { s -> s?.data?.takeUnless(Person::hidden)?.let { Person.from(s.id, it) } }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun profile(uid: String): Person? = try {
        db.collection("users").document(uid).get().await().data?.let { Person.from(uid, it) }
    } catch (_: Exception) { null }

    override suspend fun chatKeyExists(uid: String): Boolean? = try {
        call("chatKeyExists", mapOf("uid" to uid), 8)?.get("exists") as? Boolean
    } catch (_: Exception) { null }

    override suspend fun verifyChatKey(uid: String, pin: String): Result<String> {
        if (!ChatKey.isValid(pin)) return Result.failure(Exception(ChatKey.GENERIC_FAILURE))
        // A lockout this phone already knows of is refused here, in the server's words (iOS ChatPin.verify).
        ChatKey.lockedNow()?.let { return Result.failure(Exception(it)) }
        return try {
            val r = call("verifyChatPin", mapOf("uid" to uid, "pin" to pin), 10)
            val cid = r?.get("cid") as? String
            if (r?.get("ok") == true && !cid.isNullOrEmpty()) { ChatKey.lockedUntilMs = 0L; Result.success(cid) }
            else Result.failure(Exception(ChatKey.GENERIC_FAILURE))
        } catch (e: Exception) {
            val fe = e as? com.google.firebase.functions.FirebaseFunctionsException
            ChatKey.lockDeadline(fe?.code, fe?.details)?.let { ChatKey.lockedUntilMs = it }
            Result.failure(Exception(ChatKey.sentence(e)))
        }
    }

    /** ChatService.openConversation: the 1:1 seed, a request (startedBy, accepted false) when new. */
    override suspend fun openChat(p: Person): String {
        val cid = Wire.directCid(me, p.uid)
        val mine = profile(me)
        val photos = buildMap { mine?.photoUrl?.let { put(me, it) }; p.photoUrl?.let { put(p.uid, it) } }
        val ref = conv(cid)
        val snap = try { ref.get().await() } catch (_: Exception) { null }
        val exists = snap?.exists() == true
        val seed = hashMapOf<String, Any>(
            "users" to listOf(me, p.uid),
            "names" to mapOf(me to (mine?.name?.ifEmpty { null } ?: "Me"), p.uid to p.name.ifEmpty { p.handle }),
            "photos" to photos,
        )
        if (!exists) {
            seed["updatedAt"] = FieldValue.serverTimestamp()
            seed["unreadCount"] = mapOf(me to 0, p.uid to 0)
            seed["typing"] = mapOf(me to false, p.uid to false)
            if (snap != null) {
                seed["startedBy"] = me; seed["accepted"] = false
                // The Settings default timer, born with the chat in its creating write (iOS): a new
                // 1:1 is a request, and a request refuses the notice a follow-up would post.
                val def = Disappearing.defaultSeconds
                if (def > 0) seed["disappearSeconds"] = def
            }
        }
        try { ref.set(seed, SetOptions.merge()).await() } catch (e: Exception) {
            if (seed["unreadCount"] == null) throw e
            // The chat existed: its timer is its own.
            for (k in listOf("startedBy", "accepted", "unreadCount", "typing", "disappearSeconds")) seed.remove(k)
            ref.set(seed, SetOptions.merge()).await()
        }
        return cid
    }

    override suspend fun acceptRequest(cid: String): Boolean = ok { conv(cid).set(mapOf("accepted" to true), SetOptions.merge()).await() }

    /** MessageRequests.decline: a 7-day decline mark for the sender, and the chat deleted. */
    override suspend fun declineRequest(cid: String): Boolean = ok {
        val batch = db.batch()
        val sender = Wire.otherUid(cid, me)
        if (sender.isNotEmpty()) batch.set(db.collection("requestDeclines").document(me).collection("from").document(sender),
            mapOf("at" to FieldValue.serverTimestamp(), "sender" to sender))
        batch.delete(conv(cid))
        batch.commit().await()
    }

    private fun blockedRef(uid: String) = db.collection("users").document(me).collection("blocked").document(uid)

    override suspend fun isBlocked(uid: String): Boolean = try { blockedRef(uid).get().await().exists() } catch (_: Exception) { false }

    /** BlockList.write: `users/{me}/blocked/{uid}` {at}; unblock deletes it and records the span. */
    override suspend fun setBlocked(uid: String, on: Boolean): Boolean = ok {
        if (uid.isEmpty() || uid == me || uid.contains('/')) error("bad uid")
        val ref = blockedRef(uid)
        if (on) {
            ref.set(mapOf("at" to FieldValue.serverTimestamp())).await()
            // As iOS BlockList.setBlocked: no shared-chat copy of the block (the blocked person could
            // read it; an old one goes), and my live stories lose them. Best effort, after the list doc.
            runCatching {
                conv(Wire.directCid(me, uid)).update(mapOf("blockedBy.$me" to FieldValue.delete(),
                    "blockedAt.$me" to FieldValue.delete(), "blockClearedAt.$me" to FieldValue.delete())).await()
            }
            revokeStoryAudience(uid)
            return@ok
        }
        val since = ref.get().await().getTimestamp("at")?.toDate()?.time ?: System.currentTimeMillis()
        val batch = db.batch()
        batch.set(db.collection("users").document(me).collection("blockHistory").document("${uid}_$since"), mapOf(
            "other" to uid, "from" to com.google.firebase.Timestamp(java.util.Date(since)), "to" to FieldValue.serverTimestamp()))
        batch.delete(ref)
        batch.commit().await()
        try {
            conv(Wire.directCid(me, uid)).update(mapOf("blockedBy.$me" to FieldValue.delete(),
                "blockedAt.$me" to FieldValue.delete(), "blockClearedAt.$me" to FieldValue.delete())).await()
        } catch (_: Exception) {}
    }

    /**
     * iOS StoriesRepository.revokeAudience(for:): each live story of mine drops [uid] from its
     * audience, the raw uid and the token form; the `public` flag stays as it is.
     */
    private suspend fun revokeStoryAudience(uid: String) {
        val snap = runCatching { db.collection("stories").whereEqualTo("authorUid", me).get().await() }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        for (d in snap.documents) {
            val exp = d.getTimestamp("expiresAt")?.toDate()?.time ?: continue
            if (exp <= now) continue
            runCatching { d.reference.update("recipientUids", FieldValue.arrayRemove(StoryAudienceToken.token(uid), uid)).await() }
        }
    }

    /** ChatService.setMuted: `mutedBy.<me>` = forever (9 999 999 999 999) or 0. */
    override suspend fun setMuted(cid: String, on: Boolean): Boolean = ok {
        conv(cid).set(mapOf("mutedBy" to mapOf(me to if (on) 9_999_999_999_999.0 else 0.0)), SetOptions.merge()).await()
    }

    override suspend fun safetyNumber(uid: String): String? {
        val mine = crypto.myPublicKeyB64?.let { B64.decode(it) } ?: return null
        val theirs = crypto.publicKey(uid) ?: return null
        return SafetyNumber.compute(me, mine, uid, theirs)
    }

    override suspend fun checkUsername(h: String): Pair<Boolean, String?> {
        val d = call("checkUsername", mapOf("username" to h))
        return (d?.get("available") as? Boolean ?: false) to (d?.get("reason") as? String)
    }

    override suspend fun claimUsername(h: String) { call("claimUsername", mapOf("username" to h)) }

    /** ProfileStore name save: `users/{me}.name`, then `names.<me>` in every chat I am in. */
    override suspend fun saveName(name: String) {
        val n = name.trim()
        db.collection("users").document(me).set(mapOf("name" to n), SetOptions.merge()).await()
        updateMyConvs(mapOf("names.$me" to n))
    }

    private suspend fun updateMyConvs(fields: Map<String, Any>) {
        try {
            val docs = db.collection("conversations").whereArrayContains("users", me).get().await().documents
            for (chunk in docs.chunked(450)) {
                val b = db.batch()
                for (d in chunk) b.update(d.reference, fields)
                try { b.commit().await() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /**
     * ProfileStore.uploadProfileImages: a square JPEG (640, 0.8) to `profiles/{me}.jpg`, the
     * reference `fariin-photo://profiles/{me}.jpg?v=<ms>` on my profile and in every chat.
     * The audience tag is "checked" (Android does not publish the blurred cover yet).
     */
    override suspend fun uploadPhoto(jpegOrAny: ByteArray) {
        val jpeg = squareJpeg(jpegOrAny, 640, 80) ?: error("unreadable photo")
        val path = "profiles/$me.jpg"
        FirebaseStorage.getInstance().reference.child(path).putBytes(jpeg,
            StorageMetadata.Builder().setContentType("image/jpeg").setCustomMetadata("audience", "checked").build()).await()
        val refStr = PhotoRef.reference(path, System.currentTimeMillis())
        db.collection("users").document(me).set(mapOf("photoUrl" to refStr, "photoThumb" to ""), SetOptions.merge()).await()
        updateMyConvs(mapOf("photos.$me" to refStr))
    }

    /**
     * ProfileStore.removePhoto: both crops cleared everywhere (photo and poster, the profile and
     * every chat's copy), 1:1s in batches, groups one by one and best-effort (their rules list
     * fields, one refusal must not undo the 1:1 clears), then the stored files.
     */
    override suspend fun removePhoto() {
        db.collection("users").document(me).set(mapOf("photoUrl" to "", "posterUrl" to "", "photoThumb" to ""), SetOptions.merge()).await()
        val fields = mapOf<String, Any>("photos.$me" to "", "posters.$me" to "")
        try {
            val docs = db.collection("conversations").whereArrayContains("users", me).get().await().documents
            val (groups, ones) = docs.partition { it.getString("type") == "group" }
            for (chunk in ones.chunked(450)) {
                val b = db.batch()
                for (d in chunk) b.update(d.reference, fields)
                try { b.commit().await() } catch (_: Exception) {}
            }
            for (d in groups) try { d.reference.update(fields).await() } catch (_: Exception) {}
        } catch (_: Exception) {}
        val st = FirebaseStorage.getInstance().reference
        try { st.child("profiles/$me.jpg").delete().await() } catch (_: Exception) {}
        try { st.child("profiles/$me-poster.jpg").delete().await() } catch (_: Exception) {}
    }

    private suspend fun isContact(uid: String): Boolean =
        CryptoHub.listedConversation(Wire.directCid(me, uid))?.let { !it.isGroup && (it.startedBy.isEmpty() || it.accepted) } ?: false

    /** ChatService.createGroup (a plain group, e2ee false, as iOS makes them now). */
    override suspend fun createGroup(title: String, members: List<String>, photo: ByteArray?, permissions: Map<String, Boolean>): String {
        val people = members.filter { it != me }.mapNotNull { profile(it) }
        val allowed = people.filter { Person.allows(it.privacy, "groups", isContact(it.uid)) }
        // Their "Groups" privacy refused them: said, not dropped in silence (iOS ChatService.sayRefused).
        val refused = people - allowed.toSet()
        if (refused.isNotEmpty()) com.fariin.app.ui.components.Snack.show(GroupRules.refusedSentence(refused.map { it.shownName }), long = true)
        val users = (allowed.map { it.uid } + me).distinct()
        val mine = profile(me)
        val names = HashMap<String, String>(); val photos = HashMap<String, String>()
        for (p in allowed + listOfNotNull(mine)) { names[p.uid] = p.name.ifEmpty { p.handle }; p.photoUrl?.let { photos[p.uid] = it } }
        val ref = db.collection("conversations").document()
        val data = hashMapOf<String, Any>(
            "type" to "group", "title" to title.trim().take(100), "users" to users, "admins" to listOf(me),
            "createdBy" to me, "createdAt" to FieldValue.serverTimestamp(), "names" to names, "photos" to photos,
            "unreadCount" to users.associateWith { 0 }, "typing" to users.associateWith { false },
            "updatedAt" to FieldValue.serverTimestamp(), "e2ee" to false,
        )
        // iOS ChatService.createGroup: every switch goes in as given, never e2ee.
        for ((k, v) in permissions) if (k != "e2ee") data[k] = v
        ref.set(data).await()
        CryptoHub.notePlainGroup(ref.id)
        systemMessage(ref.id, "${mine?.name?.ifEmpty { null } ?: "Someone"} created the group")
        if (photo != null) try {
            val jpeg = squareJpeg(photo, 640, 80)
            if (jpeg != null) {
                val sref = FirebaseStorage.getInstance().reference.child("profiles/group_${ref.id}.jpg")
                sref.putBytes(jpeg, StorageMetadata.Builder().setContentType("image/jpeg").build()).await()
                ref.update(mapOf("avatarUrl" to sref.downloadUrl.await().toString(), "updatedAt" to FieldValue.serverTimestamp())).await()
            }
        } catch (_: Exception) {}
        return ref.id
    }

    override suspend fun setDisappear(cid: String, seconds: Int): Boolean =
        Disappearing.set(cid, seconds, me, profile(me)?.name ?: "", db)

    private suspend fun systemMessage(cid: String, text: String) {
        val batch = db.batch()
        batch.set(conv(cid).collection("messages").document(), mapOf("text" to text, "authorId" to me, "type" to "system",
            "createdAt" to FieldValue.serverTimestamp()))
        batch.update(conv(cid), mapOf("lastMessage" to text, "lastSender" to me, "updatedAt" to FieldValue.serverTimestamp()))
        batch.commit().await()
    }

    /** ChatService.addGroupMembers: arrayUnion, names/photos/unread per member, the "added" line. */
    override suspend fun addMembers(cid: String, uids: List<String>): String? {
        val people = uids.mapNotNull { profile(it) }
        val ok = people.filter { Person.allows(it.privacy, "groups", isContact(it.uid)) }
        val refused = people - ok.toSet()
        if (ok.isEmpty()) return GroupRules.refusedSentence(refused.map { it.shownName })
        val update = hashMapOf<String, Any>("users" to FieldValue.arrayUnion(*ok.map { it.uid }.toTypedArray()),
            "updatedAt" to FieldValue.serverTimestamp())
        for (p in ok) { update["names.${p.uid}"] = p.name.ifEmpty { p.handle }; p.photoUrl?.let { update["photos.${p.uid}"] = it }; update["unreadCount.${p.uid}"] = 0 }
        // iOS: re-adding a removed (banned) person lifts the ban, and only an admin who may remove
        // members may do that; an arrayRemove is written only when someone here really is banned.
        val d = runCatching { conv(cid).get().await().data }.getOrNull() ?: emptyMap()
        // The member cap (config/limits group_members_max, fallback 30): the rules hold it on every
        // add, this says it in words first. A channel's users are its admins, so it is exempt.
        val max = AppLimits.groupMembersMax
        val count = (d["users"] as? List<*>)?.size ?: 0
        if (d["channel"] != true && count + ok.size > max) return AppLimits.membersCapSentence(max)
        val banned = (d["bannedUids"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        val lifted = ok.map { it.uid }.filter { it in banned }
        if (lifted.isNotEmpty()) {
            if (!GroupAdmins.Team.from(d).can(me, GroupAdmins.Right.BAN_USERS))
                return "An admin removed this person. Only an admin who can remove members can add them back."
            update["bannedUids"] = FieldValue.arrayRemove(*lifted.toTypedArray())
        }
        val text = "${profile(me)?.shownName ?: "Someone"} added ${GroupRules.addedWho(ok.map { it.shownName })}"
        val batch = db.batch()
        batch.set(conv(cid).collection("messages").document(), mapOf("text" to text, "authorId" to me, "type" to "system",
            "createdAt" to FieldValue.serverTimestamp()))
        update["lastMessage"] = text; update["lastSender"] = me
        batch.update(conv(cid), update)
        batch.commit().await()
        return if (refused.isEmpty()) null else GroupRules.refusedSentence(refused.map { it.shownName })
    }

    /** ChatService.leaveGroup: one transaction, the owner hands over to an admin (else the first member). */
    override suspend fun leaveGroup(cid: String) {
        val myName = profile(me)?.shownName ?: "Someone"
        val ref = conv(cid)
        db.runTransaction { txn ->
            val data = txn.get(ref).data ?: emptyMap()
            val plan = GroupRules.leavePlan(me, myName, data)
            for (line in plan.notices) txn.set(ref.collection("messages").document(), mapOf("text" to line, "authorId" to me,
                "type" to "system", "createdAt" to FieldValue.serverTimestamp()))
            txn.update(ref, plan.update)
            null
        }.await()
    }

    /** ChatService.renameGroup: `title` + updatedAt, then "<me> renamed the group to ..." (best effort, as iOS). */
    override suspend fun renameGroup(cid: String, title: String) {
        val t = GroupRules.cleanTitle(title)
        if (t.isEmpty()) return
        conv(cid).update(mapOf("title" to t, "updatedAt" to FieldValue.serverTimestamp())).await()
        try { systemMessage(cid, GroupRules.renamedLine(profile(me)?.name ?: "", t)) } catch (_: Exception) {}
    }

    /**
     * ChatService.uploadGroupAvatar: a plain JPEG (iOS crops to a circle and sends at most 1024 px)
     * to `profiles/group_{cid}.jpg`, its download link as `avatarUrl`. No chat line, as iOS.
     */
    override suspend fun setGroupPhoto(cid: String, photo: ByteArray) {
        val jpeg = squareJpeg(photo, 1024, 80) ?: error("unreadable photo")
        val sref = FirebaseStorage.getInstance().reference.child("profiles/group_$cid.jpg")
        sref.putBytes(jpeg, StorageMetadata.Builder().setContentType("image/jpeg").build()).await()
        conv(cid).update(mapOf("avatarUrl" to sref.downloadUrl.await().toString(), "updatedAt" to FieldValue.serverTimestamp())).await()
    }

    /** ChatService.setGroupDescription: `desc` (trimmed, at most [GroupRules.DESC_MAX]) + updatedAt. No chat line, as iOS. */
    override suspend fun setGroupDescription(cid: String, text: String) {
        conv(cid).update(mapOf("desc" to GroupRules.cleanDesc(text), "updatedAt" to FieldValue.serverTimestamp())).await()
    }

    /** ChatService.removeGroupAvatar: the field goes, the file is tried, then "<me> removed the group photo". */
    override suspend fun removeGroupPhoto(cid: String) {
        conv(cid).update(mapOf("avatarUrl" to FieldValue.delete(), "updatedAt" to FieldValue.serverTimestamp())).await()
        try { FirebaseStorage.getInstance().reference.child("profiles/group_$cid.jpg").delete().await() } catch (_: Exception) {}
        try { systemMessage(cid, GroupRules.photoRemovedLine(profile(me)?.name ?: "")) } catch (_: Exception) {}
    }

    private suspend fun ok(block: suspend () -> Unit): Boolean = try { block(); true } catch (_: Exception) { false }

    companion object {
        /** iOS squareJPEG: centre crop to a square, at most [side] px, JPEG at [quality]. */
        fun squareJpeg(bytes: ByteArray, side: Int, quality: Int): ByteArray? {
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val s = minOf(src.width, src.height)
            val crop = Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 2, s, s)
            val out = if (s > side) Bitmap.createScaledBitmap(crop, side, side, true) else crop
            return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        }
    }
}

/** ProfilePhotoURLProtocol.reference: `fariin-photo://<path>?v=<ms>`. */
object PhotoRef {
    fun reference(path: String, version: Long) = "fariin-photo://$path?v=$version"
}

/** ChatPin: 4 to 6 digits, the verify failure sentences. */
object ChatKey {
    const val MIN = 4
    const val MAX = 6
    const val GENERIC_FAILURE = "Unable to verify Chat Key."
    fun isValid(pin: String) = pin.length in MIN..MAX && pin.all { it.isDigit() }
    /** Paste keeps the digits only, at most six. */
    fun fromPaste(text: String) = text.filter { it.isDigit() }.take(MAX)
    /**
     * iOS ChatPin.lockedUntil: the server's lockout deadline (ms), latched from its refusal so the
     * next try is refused here in the same words without spending an attempt. A key that opened
     * something clears it.
     */
    @Volatile var lockedUntilMs: Long = 0L

    /** iOS ChatPin.lockSentence: minutes, rounded up, never zero. */
    fun lockSentence(untilMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val mins = maxOf(1L, (untilMs - nowMs + 59_999) / 60_000)
        return "Too many attempts. Try again in $mins minute${if (mins == 1L) "" else "s"}."
    }

    /** The live local lock's sentence, or null when there is none. */
    fun lockedNow(nowMs: Long = System.currentTimeMillis()): String? =
        lockedUntilMs.takeIf { it > nowMs }?.let { lockSentence(it, nowMs) }

    /** iOS ChatPin.lockDeadline: `{ lockedUntil: <ms> }` in a RESOURCE_EXHAUSTED refusal's details. */
    fun lockDeadline(code: com.google.firebase.functions.FirebaseFunctionsException.Code?, details: Any?): Long? {
        if (code != com.google.firebase.functions.FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED) return null
        val ms = ((details as? Map<*, *>)?.get("lockedUntil") as? Number)?.toLong() ?: return null
        return ms.takeIf { it > 0 }
    }

    /**
     * iOS ChatPin.sentence: the server's words where they are about the caller (the lockout, "4 to
     * 6 digits", ALREADY_EXISTS "That Chat Key is taken"), "Try again in a moment." offline. Every
     * other refusal, PERMISSION_DENIED included, is the generic line on purpose: it must never say
     * whether somebody's key was right.
     */
    fun sentence(e: Exception): String {
        val code = (e as? com.google.firebase.functions.FirebaseFunctionsException)?.code
        return when (code) {
            com.google.firebase.functions.FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
            com.google.firebase.functions.FirebaseFunctionsException.Code.INVALID_ARGUMENT,
            com.google.firebase.functions.FirebaseFunctionsException.Code.ALREADY_EXISTS -> e.message ?: GENERIC_FAILURE
            com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE,
            com.google.firebase.functions.FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> "Try again in a moment."
            else -> GENERIC_FAILURE
        }
    }
}

/** Group rules from ChatService (leaveGroup, addGroupMembers, refusedSentence). */
object GroupRules {
    /** "1 member", "5 members". */
    fun memberCount(n: Int): String = if (n == 1) "1 member" else "$n members"

    data class LeavePlan(val update: Map<String, Any>, val notices: List<String>, val heir: String?)

    fun refusedSentence(names: List<String>): String {
        val who = if (names.size <= 2) names.joinToString(" and ") else "${names[0]} and ${names.size - 1} others"
        return "$who can't be added because of their privacy settings. You can send them the group link instead."
    }

    fun addedWho(names: List<String>): String =
        if (names.size <= 3) names.joinToString(", ") else "${names.take(2).joinToString(", ")} and ${names.size - 2} others"

    /**
     * leaveGroup's plan: me out of users and admins; when no other admin is left the first other
     * member becomes one; when I own the group, an admin (else the first member) becomes the owner
     * with "<name> is now the owner"; then "<me> left".
     */
    fun leavePlan(me: String, myName: String, data: Map<String, Any?>): LeavePlan {
        val users = (data["users"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        val admins = (data["admins"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        val names = (data["names"] as? Map<*, *>)?.mapNotNull { (k, v) -> (k as? String)?.let { it to (v as? String ?: "") } }?.toMap() ?: emptyMap()
        val owner = data["createdBy"] as? String ?: ""
        val notices = mutableListOf<String>()
        val update = hashMapOf<String, Any>(
            "users" to FieldValue.arrayRemove(me), "admins" to FieldValue.arrayRemove(me), "updatedAt" to FieldValue.serverTimestamp())
        val ownerHeir = if (owner.isNotEmpty() && owner == me)
            admins.firstOrNull { it != me && it in users } ?: users.firstOrNull { it != me } else null
        if (admins.none { it != me }) users.firstOrNull { it != me }?.let { heir ->
            update["admins"] = listOf(heir)
            if (ownerHeir != heir) notices.add("${names[heir] ?: "A member"} is now an admin")
        }
        if (ownerHeir != null) {
            update["createdBy"] = ownerHeir
            if (ownerHeir !in admins) update["admins"] = admins.filter { it != me } + ownerHeir
            update["adminRights.$ownerHeir"] = FieldValue.delete()
            update["adminBy.$ownerHeir"] = FieldValue.delete()
            notices.add("${names[ownerHeir] ?: "A member"} is now the owner")
        }
        val left = "$myName left"
        notices.add(left)
        update["lastMessage"] = left
        update["lastSender"] = me
        return LeavePlan(update, notices, ownerHeir)
    }

    /**
     * GroupInfoView.canEditInfo: may [me] change the name and photo? Never in a group Fariin closed
     * (`mod.state` disabled, or suspended and not over) or one its owner ended. The owner always;
     * an admin with Change group info (a legacy admin with no `adminRights` entry has it); a
     * member when `membersCanEditInfo` is on and no live `changeInfo` restriction stops them.
     */
    fun canEditInfo(me: String, data: Map<String, Any?>, nowMs: Double = System.currentTimeMillis().toDouble()): Boolean {
        if (me.isEmpty()) return false
        val mod = data["mod"] as? Map<*, *>
        val state = mod?.get("state") as? String ?: "ok"
        if (state == "ended" || state == "disabled") return false
        if (state == "suspended") {
            val until = (mod?.get("until") as? com.google.firebase.Timestamp)?.toDate()?.time?.toDouble() ?: 0.0
            if (until <= 0 || until > nowMs) return false
        }
        val owner = data["createdBy"] as? String ?: ""
        if (owner.isNotEmpty() && owner == me) return true
        val admins = (data["admins"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        if (me in admins) {
            val granted = (data["adminRights"] as? Map<*, *>)?.get(me) as? List<*> ?: return true
            return "changeInfo" in granted
        }
        val users = (data["users"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        if (me !in users || data["membersCanEditInfo"] != true) return false
        val until = ((data["restrictedUntil"] as? Map<*, *>)?.get(me) as? Number)?.toDouble() ?: 0.0
        val flags = (data["restrictedFlags"] as? Map<*, *>)?.get(me) as? List<*> ?: emptyList<Any>()
        return !(until > nowMs && "changeInfo" in flags)
    }

    /** AppLimits.groupDescMax: the panel's `group_desc_max`, 200 when it has none (as iOS). */
    val DESC_MAX: Int get() = AppLimits.groupDescMax

    /**
     * Limits.oneParagraph, as the Description field types it: every whitespace (newlines too) is one
     * space, runs of spaces fold to one, at most [max] characters.
     */
    fun oneParagraph(raw: String, max: Int = DESC_MAX): String {
        val s = raw.map { if (it.isWhitespace()) ' ' else it }.joinToString("").replace(Regex(" {2,}"), " ")
        return if (s.length > max) s.take(max) else s
    }

    /** ChatService.setGroupDescription: trimmed, at most [DESC_MAX]. */
    fun cleanDesc(s: String): String = s.trim().take(DESC_MAX)

    /** The group's description on its conversation doc (`desc`), "" when none. */
    fun description(data: Map<String, Any?>): String = (data["desc"] as? String)?.trim() ?: ""

    /** ChatService.renameGroup: trimmed, at most 100 characters. */
    fun cleanTitle(s: String): String = s.trim().take(100)

    /** The system lines iOS writes (ChatService.renameGroup / removeGroupAvatar; "Someone" when nameless). */
    fun renamedLine(myName: String, title: String) = "${myName.ifEmpty { "Someone" }} renamed the group to “$title”"
    fun photoRemovedLine(myName: String) = "${myName.ifEmpty { "Someone" }} removed the group photo"

    /** The line under Leave Group for the owner (iOS LeaveGroupAlert). */
    fun ownerLeaveLine(heirName: String?): String =
        if (heirName == null) "You're the only member. The group will be empty."
        else "You own this group. If you leave, $heirName becomes the owner."
}
