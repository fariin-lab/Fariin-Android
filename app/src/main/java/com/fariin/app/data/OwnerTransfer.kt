package com.fariin.app.data

import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * A channel's words on the pages groups and channels share (iOS ChannelWords): whole words only,
 * so "remember" never turns into "refollower".
 */
object ChannelWords {
    private val swaps = listOf(
        "Join Requests" to "Follow Requests", "join requests" to "follow requests",
        "Join Request" to "Follow Request", "join request" to "follow request",
        "New Members" to "New Followers", "Members" to "Followers", "members" to "followers",
        "Member" to "Follower", "member" to "follower",
        "Messages" to "Posts", "messages" to "posts", "Message" to "Post", "message" to "post",
        "Groups" to "Channels", "groups" to "channels",
        "Group" to "Channel", "group" to "channel",
    )

    fun apply(s: String): String = swaps.fold(s) { text, (from, to) ->
        Regex("(?<![\\p{L}])" + Regex.escape(from) + "(?![\\p{L}])").replace(text) { to }
    }

    fun forChat(isChannel: Boolean, s: String): String = if (isChannel) apply(s) else s
}

/**
 * Transfer Ownership (iOS ChatService.checkOwnershipTransfer / transferGroupOwnership,
 * AuthService.reauthPassword, TransferPasswordAlert, ChooseNewOwnerSheet). The server alone moves
 * `createdBy` (functions-groups transferGroupOwnership): it asks for me the owner, them a member, a
 * password on my account older than 5 hours, and a password sign-in in the last 5 minutes.
 *   transferGroupOwnership {cid, to, check?} -> {ok, ready, transferred}; a refusal carries details.reason.
 */
object OwnerTransfer {
    private val fn get() = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)

    /** The server's refusal: its reason ("no-password", "password-created-recently", ...) and words. */
    class Refusal(val reason: String, message: String) : Exception(message) {
        val needsPassword: Boolean get() = reason == "no-password"
    }

    const val OFFLINE = "Could not transfer ownership. Check your connection and try again."
    const val WRONG_PASSWORD = "That password is not right."
    const val NOT_VERIFIED = "Could not verify that. Try again."
    const val CREATE_FIRST = "Create a password in Settings first, then try again."
    const val CANT_TITLE = "Can't Transfer Ownership"
    const val NO_PASSWORD_TITLE = "Create a Password First"
    const val PASSWORD_TITLE = "Enter Password"
    const val FAILED_TITLE = "Couldn't Transfer"
    const val CHOOSE_TITLE = "Choose New Owner"
    const val ROW = "Transfer Ownership"
    const val CHOOSE_ANOTHER = "Choose Another Owner"

    /** The reason in a refusal's details (the literal "details" map), "" when there is none. */
    fun reasonOf(details: Any?): String = ((details as? Map<*, *>)?.get("reason") as? String).orEmpty()

    /** Who may be offered Transfer Ownership: the owner, for another member. */
    fun canTransfer(me: String, owner: String, target: String, users: List<String>): Boolean =
        me.isNotEmpty() && owner == me && target.isNotEmpty() && target != me && target in users

    /** The owner's Leave dialog offers Choose Another Owner while anyone else is in the group. */
    fun offersChooseAnother(me: String, owner: String, users: List<String>): Boolean =
        me.isNotEmpty() && owner == me && users.any { it != me }

    /** iOS noPasswordMessage: with no address a password could go on, Settings is the way. */
    fun noPasswordMessage(isChannel: Boolean, hasAddress: Boolean): String = ChannelWords.forChat(isChannel,
        if (hasAddress) "To transfer group ownership, your account needs a password. You can transfer ownership 5 hours after you create it."
        else "To transfer group ownership, your account needs a password. Add one in Settings, then try again after 5 hours.")

    /** The password dialog's line (TransferPasswordAlert.message). */
    fun passwordMessage(isChannel: Boolean, name: String, leaving: Boolean): String = ChannelWords.forChat(isChannel,
        if (leaving) "Enter your password to finish this. $name will become the owner, then you leave the group."
        else "Enter your password to finish this. $name will have full control of this group, including over you.")

    fun chooseFooter(isChannel: Boolean): String =
        ChannelWords.forChat(isChannel, "The person you choose becomes the owner, then you leave the group.")

    fun doneToast(name: String) = "$name is now the owner"

    /** Everything but the password proof (check: true), so the page can say what is missing first. */
    suspend fun check(cid: String, to: String) { call(mapOf("cid" to cid, "to" to to, "check" to true)) }

    suspend fun transfer(cid: String, to: String) { call(mapOf("cid" to cid, "to" to to)) }

    private suspend fun call(payload: Map<String, Any?>) {
        try {
            fn.getHttpsCallable("transferGroupOwnership").withTimeout(20, TimeUnit.SECONDS).call(payload).await()
        } catch (e: FirebaseFunctionsException) {
            val reason = reasonOf(e.details)
            if (reason.isNotEmpty()) throw Refusal(reason, e.message?.takeIf { it.isNotBlank() } ?: OFFLINE)
            throw Refusal("", OFFLINE)
        } catch (e: Refusal) {
            throw e
        } catch (_: Exception) {
            throw Refusal("", OFFLINE)
        }
    }

    /**
     * AuthService.reauthPassword: proves the PASSWORD on the address it is on (an Apple or Google
     * account may carry it on another address). The fresh ID token then says sign_in_provider
     * password, which the server checks. The password goes to Firebase Auth only. Null = proved,
     * else the words to show.
     */
    suspend fun reauthPassword(password: String): String? {
        val user = FirebaseAuth.getInstance().currentUser ?: return NOT_VERIFIED
        val address = user.providerData.firstOrNull { it.providerId == "password" }?.email?.takeIf { it.isNotEmpty() }
            ?: user.email?.takeIf { it.isNotEmpty() } ?: return NOT_VERIFIED
        val before = TwoStepGate.tokenBefore()
        return try {
            user.reauthenticate(EmailAuthProvider.getCredential(address, password)).await()
            TwoStepGate.renewAfterReauth(before)
            runCatching { user.getIdToken(true).await() }
            null
        } catch (_: FirebaseAuthInvalidCredentialsException) {
            WRONG_PASSWORD
        } catch (_: Exception) {
            NOT_VERIFIED
        }
    }
}

/**
 * Join Requests (iOS GroupInviteService.joinRequests / approveJoin / denyJoin, GroupRequestsView).
 * A request is conversations/{cid}/joinRequests/{uid} {uid, name, photo, via, at}, written by the
 * server when someone uses a link with Approve New Members on; admins with Add members read them.
 *   approveJoinRequest {cid, uid} / denyJoinRequest {cid, uid} -> {ok}
 */
object JoinRequests {
    data class Request(val uid: String, val name: String, val photo: String?)

    fun from(uid: String, d: Map<String, Any?>): Request =
        Request(uid, (d["name"] as? String)?.takeIf { it.isNotEmpty() } ?: "New member", (d["photo"] as? String)?.takeIf { it.isNotEmpty() })

    /** Answered on the page: gone at once, back only if the server refuses. A new request shows again. */
    fun shown(all: List<Request>, answered: Set<String>): List<Request> = all.filter { it.uid !in answered }

    /** The answered set kept only for requests still on the server (iOS takeRequests). */
    fun keepAnswered(all: List<Request>, answered: Set<String>): Set<String> = answered.intersect(all.map { it.uid }.toSet())

    fun title(isChannel: Boolean) = if (isChannel) "Follow Requests" else "Join Requests"
    fun empty(isChannel: Boolean) = if (isChannel) "No one is waiting to follow." else "No one is waiting to join."
    fun header(isChannel: Boolean) = if (isChannel) "Waiting to follow" else "Waiting to join"
    fun emptyFooter(isChannel: Boolean, title: String) =
        ChannelWords.forChat(isChannel, "People who ask to join “${title.ifEmpty { "this group" }}” with a group link show up here.")
    fun rowValue(count: Int) = if (count == 0) "None" else "$count"

    private val fn get() = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)

    suspend fun approve(cid: String, uid: String) {
        fn.getHttpsCallable("approveJoinRequest").withTimeout(20, TimeUnit.SECONDS).call(mapOf("cid" to cid, "uid" to uid)).await()
    }

    suspend fun deny(cid: String, uid: String) {
        fn.getHttpsCallable("denyJoinRequest").withTimeout(20, TimeUnit.SECONDS).call(mapOf("cid" to cid, "uid" to uid)).await()
    }

    /** Live requests; a refused or failed read keeps the last list (iOS). */
    fun listen(cid: String, onChange: (List<Request>) -> Unit): ListenerRegistration =
        FirebaseFirestore.getInstance().collection("conversations").document(cid).collection("joinRequests")
            .addSnapshotListener { s, _ -> s?.let { snap -> onChange(snap.documents.map { from(it.id, it.data ?: emptyMap()) }) } }

    /** The server's words when they are for the person, else "Could not do that" with ours. */
    fun errorText(e: Exception): String = GroupManage.errorText(e, "Couldn't answer the request. Check your connection and try again.")
}

/**
 * Channel Settings > Reactions (iOS ChannelReactionsSettingsView, ChannelService.setReactions,
 * functions-groups setChannelReactions + channels.normReactSet): one switch, on = only the ones
 * picked (up to 12), off = none. The server keeps `reactSet {mode, list}` on the channel; an old "all"
 * (or no reactSet) becomes the default set when the owner opens the page.
 */
object ChannelReactions {
    /** Server REACT_LIST_MAX. */
    const val MAX = 12
    /** A fresh Selected list starts with this many from the palette. */
    const val START = 6
    /** The editor's bar holds this many per row. */
    const val PER_ROW = 6
    /** iOS palette: where Selected Emoji starts the first time. */
    val palette = listOf("👍", "❤️", "🔥", "😂", "😮", "😢", "🙏", "👏", "🎉", "💯", "🤔", "😍",
        "👎", "😡", "🤝", "💪", "✅", "⭐️", "🌙", "☀️", "⚽️", "📌", "💡", "🙌")

    const val FOOTER = "Nobody sees who reacted, only how many."
    const val FOOTER_OFF = "Followers can't react to posts."

    /** ONE SWITCH (iOS 2dc585e8, owner 2026-11-07): on = the picks, off = none; "Any Emoji" is gone. */
    fun footer(mode: String) = if (mode == "off") FOOTER_OFF else FOOTER

    /** What a new channel starts with, and the switch's ON when nothing was picked before. */
    val defaultSet: List<String> get() = palette.take(START)

    /** The switch turned on: the earlier picks, else the default set. */
    fun onList(current: List<String>, lastPicks: List<String>): List<String> =
        current.ifEmpty { lastPicks.ifEmpty { defaultSet } }

    /** The Channel Settings row's value: Off or Any (Selected shows the emoji themselves). */
    fun value(mode: String) = if (mode == "off") "Off" else "Any"

    fun pickFooter(list: List<String>) = when {
        list.isEmpty() -> "Pick at least one, or reactions will be off."
        list.size > 1 -> "Tap to replace an emoji, or tap its x to remove it"
        else -> "Tap to replace an emoji"
    }

    /** normReactSet: a known mode, the list only for "some", at most twelve, no repeats; "some" with none = off. */
    fun normalize(mode: String, list: List<String>): Pair<String, List<String>> {
        val m = if (mode == "some" || mode == "off") mode else "all"
        val l = list.filter { it.isNotEmpty() }.distinct().take(MAX)
        if (m == "some" && l.isEmpty()) return "off" to emptyList()
        return m to (if (m == "some") l else emptyList())
    }

    /** Choosing Selected again brings back the last picks, else the palette's first six. */
    fun startList(current: List<String>, lastPicks: List<String>): List<String> =
        current.ifEmpty { lastPicks.ifEmpty { palette.take(START) } }

    /** The emoji sheet's pick: replaces the tapped place (one already in the bar swaps), or joins the end. Null = no change. */
    fun place(list: List<String>, replacing: Int?, e: String): List<String>? {
        val out = list.toMutableList()
        if (replacing != null && replacing in out.indices) {
            val j = out.indexOf(e)
            if (j >= 0) { out[j] = out[replacing]; out[replacing] = e } else out[replacing] = e
        } else if (e !in out && out.size < MAX) out.add(e) else return null
        return out
    }

    /** Each one has its x while more than one is left; the last stays (No Reactions is the way to none). */
    fun remove(list: List<String>, i: Int): List<String> = if (list.size > 1 && i in list.indices) list.filterIndexed { k, _ -> k != i } else list

    /** setChannelReactions {cid, mode, list} -> {mode, list}: what the server kept. */
    suspend fun save(cid: String, mode: String, list: List<String>): Pair<String, List<String>> {
        val r = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("setChannelReactions")
            .withTimeout(20, TimeUnit.SECONDS).call(mapOf("cid" to cid, "mode" to mode, "list" to list)).await().getData() as? Map<*, *>
        val m = r?.get("mode") as? String ?: mode
        val l = (r?.get("list") as? List<*>)?.filterIsInstance<String>() ?: list
        return m to l
    }
}

/**
 * Add Followers / Invite via Link on a channel's Followers page (iOS ChannelFollowersView add /
 * shareInvite, ChannelService.addFollowers / shareLink, functions-groups addChannelFollowers).
 *   addChannelFollowers {cid, uids (<= 50)} -> {added, refused, linkOnly, capped, ...counts}
 */
object ChannelAddFollowers {
    data class Result(val added: List<String>, val refused: List<String>, val linkOnly: List<String>, val capped: List<String>)

    const val ADDS_FULL = "This channel has added 10 people, the most it can. Others can follow it with its link."
    const val NO_LINK = "Couldn't get the channel's link. Check your connection and try again."

    fun parse(d: Map<*, *>?): Result {
        fun l(k: String) = (d?.get(k) as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        return Result(l("added"), l("refused"), l("linkOnly"), l("capped"))
    }

    fun merge(a: Result, b: Result) = Result(a.added + b.added, a.refused + b.refused, a.linkOnly + b.linkOnly, a.capped + b.capped)

    /** The toasts after an add, in iOS order. */
    fun toasts(r: Result, picked: Int): List<String> = buildList {
        val others = r.refused.count { it !in r.linkOnly.toSet() }
        if (r.added.isNotEmpty()) add(if (r.added.size == 1) "1 follower added" else "${r.added.size} followers added")
        else if (r.refused.isEmpty() && r.capped.isEmpty()) add(if (picked == 1) "Already following" else "They already follow this channel")
        if (others > 0) add(if (others == 1) "1 person couldn't be added" else "$others people couldn't be added")
        if (r.capped.isNotEmpty()) add(ADDS_FULL)
    }

    /** Offered the link instead: their own setting refused the add, or the ten adds are used. */
    fun viaLink(r: Result): Set<String> = (r.linkOnly + r.capped).toSet()

    /** InviteInsteadSheet's line. */
    fun inviteInstead(names: List<String>): String {
        val who = when (names.size) {
            0 -> "They"
            1 -> names[0]
            2 -> "${names[0]} and ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
        return "$who only ${if (names.size == 1) "lets" else "let"} certain people add them to channels. Send the channel's link in your chat instead."
    }

    /** The text the share sheet sends (iOS sendInviteLink). */
    fun shareText(title: String, link: String) = "Follow “$title” on Fariin: $link"

    suspend fun add(cid: String, uids: List<String>): Result {
        var out = Result(emptyList(), emptyList(), emptyList(), emptyList())
        for (part in uids.chunked(50)) {
            val d = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("addChannelFollowers")
                .withTimeout(20, TimeUnit.SECONDS).call(mapOf("cid" to cid, "uids" to part)).await().getData() as? Map<*, *>
            out = merge(out, parse(d))
        }
        return out
    }

    /** ChannelService.shareLink: the public address, else the primary invite link (made on the spot if missing). */
    suspend fun link(cid: String, doc: Map<String, Any?>): String? {
        val name = doc["username"] as? String ?: ""
        if (doc["isPublic"] == true && name.isNotEmpty()) return "https://fariin.com/$name"
        return runCatching { GroupManage.listLinks(cid).primary?.url }.getOrNull()
            ?: runCatching { GroupManage.createPrimary(cid).url }.getOrNull()
    }
}
