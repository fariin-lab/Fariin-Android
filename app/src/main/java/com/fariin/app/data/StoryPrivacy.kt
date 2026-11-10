package com.fariin.app.data

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * One of my story lists, as iOS StoryAudience writes it to `users/{me}/storyLists/{id}`:
 * `myFriends` ("My Chats": all / except / only + Allow Replies) and `hiddenFrom` (the people the
 * Everyone audience leaves out, iOS "Hide my stories from"). Custom lists use the same shape.
 */
data class StoryListDoc(
    val id: String,
    val kind: String,
    val mode: String = StoryPrivacyRules.MODE_ALL,
    val members: List<String> = emptyList(),
    val allowReplies: Boolean = true,
    val name: String = "",
    /** A custom list's own emoji; "" = the folder drawing (iOS StoryAudience.emoji). */
    val emoji: String = "",
    /** iOS createdAt: the epoch for the built-ins, the moment it was made for a custom list. */
    val createdAtMs: Long = 0L,
)

/** Pure rules behind the story privacy pages and the post (iOS StoryAudienceStore + resolveAudience). */
object StoryPrivacyRules {
    const val MY_FRIENDS_ID = "myFriends"
    const val HIDDEN_ID = "hiddenFrom"
    const val MODE_ALL = "all"
    const val MODE_EXCEPT = "except"
    const val MODE_ONLY = "only"

    val defaultMyFriends = StoryListDoc(MY_FRIENDS_ID, "myFriends")
    val emptyHidden = StoryListDoc(HIDDEN_ID, "hidden", mode = MODE_EXCEPT)
    /** The Glowers audience (iOS StoryAudience.glowersAudience): `members` are who to LEAVE OUT. */
    const val GLOWERS_ID = "glowers"
    val defaultGlowers = StoryListDoc(GLOWERS_ID, "glowers", name = "Glowers")

    /** iOS ShareStorySheet.glowersRecipients: the relationship less the people hidden from my glow. */
    fun glowersRecipients(l: StoryListDoc, relationship: Set<String>): Set<String> =
        relationship - (if (l.mode == MODE_EXCEPT) l.members.toSet() else emptySet())

    /** iOS StoryAudience.subtitle for Glowers, counted off the live relationship. */
    fun glowersSubtitle(l: StoryListDoc, relationship: Set<String>): String {
        val n = glowersRecipients(l, relationship).size
        if (n == 0) return "People you have a Glow with"
        val people = "$n ${if (n == 1) "person" else "people"} you have a Glow with"
        return if (l.mode == MODE_EXCEPT && l.members.isNotEmpty()) "$people · ${l.members.size} hidden" else people
    }

    /** iOS PostRefusal.glowAudienceEmpty / glowAudienceUnknown: a Glowers post that would reach nobody. */
    const val GLOW_EMPTY = "You don't have any Glows yet, so this story would reach nobody. Pick another audience."
    const val GLOW_UNKNOWN = "Couldn't load your Glows, so this story would reach nobody. Check your connection and try again."

    /** iOS StoryAudienceStore.write: every field, `emoji` "" and the built-ins' epoch date. */
    fun encode(l: StoryListDoc): Map<String, Any> = mapOf(
        "kind" to l.kind,
        "name" to l.name,
        "mode" to l.mode,
        "members" to l.members,
        "allowReplies" to l.allowReplies,
        "emoji" to l.emoji,
        "createdAt" to Timestamp(Date(l.createdAtMs)),
    )

    /** iOS decode, with its self-heal: My Chats narrowed to nobody is just "all". */
    fun decode(id: String, data: Map<String, Any?>): StoryListDoc? {
        val kind = data["kind"] as? String ?: return null
        val members = (data["members"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        var mode = (data["mode"] as? String)?.takeIf { it in listOf(MODE_ALL, MODE_EXCEPT, MODE_ONLY) } ?: MODE_ALL
        if (kind == "myFriends" && mode != MODE_ALL && members.isEmpty()) mode = MODE_ALL
        val at = (data["createdAt"] as? Timestamp)?.toDate()?.time ?: System.currentTimeMillis()
        return StoryListDoc(id, kind, mode, members, data["allowReplies"] as? Boolean ?: true, data["name"] as? String ?: "",
            data["emoji"] as? String ?: "", at)
    }

    /** iOS StoryAudience.nameLimit and StoryAudienceStore.maxCustom. */
    const val NAME_LIMIT = 25
    const val MAX_CUSTOM = 5
    const val KIND_CUSTOM = "custom"

    /** iOS StoryAudience.subtitle for a custom list. */
    fun customSubtitle(l: StoryListDoc): String {
        val n = l.members.size
        return "Custom story · $n ${if (n == 1) "viewer" else "viewers"}"
    }

    /** iOS NameStoryView footer: the count shows once it is nearly spent. */
    fun nameFooter(name: String): String =
        if (name.length >= NAME_LIMIT - 5) "Only you can see the name of this story. ${NAME_LIMIT - name.length} left."
        else "Only you can see the name of this story."

    /** Clamped as it is typed (iOS onChange prefix). */
    fun clampName(s: String): String = s.take(NAME_LIMIT)

    /** iOS StoryAudienceStore.createCustom: a new list, `only` over its members. */
    fun newCustom(id: String, name: String, members: Collection<String>, allowReplies: Boolean, emoji: String, nowMs: Long) =
        StoryListDoc(id, KIND_CUSTOM, MODE_ONLY, members.toList(), allowReplies, name.trim(), emoji, nowMs)

    /** Custom lists posted to: the union of their members within my chats (iOS postToLists). */
    fun listsRecipients(lists: List<StoryListDoc>, contacts: Set<String>): Set<String> =
        lists.flatMap { it.members }.toSet().intersect(contacts)

    /** Replies only when every ticked list allows them (iOS: the strictest list wins). */
    fun listsAllowReplies(lists: List<StoryListDoc>): Boolean = lists.all { it.allowReplies }

    /** The name a custom story of mine shows (iOS storyName): its lists' names now, else the name at posting. */
    fun storyName(link: StoryAudienceLink?, custom: List<StoryListDoc>): String? {
        link ?: return null
        val live = link.listIds.mapNotNull { id -> custom.firstOrNull { it.id == id }?.name }.filter { it.isNotEmpty() }
        if (live.isNotEmpty() && live.size == link.listIds.size) return live.joinToString(", ")
        return link.name.ifEmpty { null }
    }

    /**
     * iOS SelectViewersView.sections: by first letter, A to Z, then "#" for names that do not start
     * with a letter; names sorted inside each.
     */
    fun <T> letterSections(items: List<T>, name: (T) -> String): List<Pair<String, List<T>>> =
        items.groupBy { t ->
            val f = name(t).trim().firstOrNull()
            if (f != null && f.isLetter()) f.uppercaseChar().toString() else "#"
        }.toList()
            .sortedWith(compareBy<Pair<String, List<T>>> { it.first == "#" }.thenBy { it.first })
            .map { (k, v) -> k to v.sortedBy { name(it).lowercase() } }

    /** iOS tapAudience: custom lists toggle, so a second tick adds a list. Empty = back to My Chats. */
    fun toggleList(ticked: Set<String>, id: String): Set<String> = if (id in ticked) ticked - id else ticked + id

    /** The Stories settings row subtitles (iOS StoryAudience.subtitle). */
    fun everyoneSubtitle() = "All Fariin connections"
    fun myChatsSubtitle(l: StoryListDoc): String = when (l.mode) {
        MODE_EXCEPT -> "All chats you accepted except ${l.members.size}"
        MODE_ONLY -> "${l.members.size} selected"
        else -> "All chats you accepted"
    }

    /** The detail line under each My Chats choice (iOS MyFriendsPrivacyView.modeRow). */
    fun modeDetail(l: StoryListDoc, mode: String, contacts: Int): String? = when (mode) {
        MODE_ALL -> "$contacts ${if (contacts == 1) "Viewer" else "Viewers"}"
        MODE_EXCEPT -> if (l.mode == MODE_EXCEPT) "${l.members.size} excluded" else null
        else -> if (l.mode == MODE_ONLY) "${l.members.size} selected" else null
    }

    /**
     * Who a post reaches and its `audience.mode`, as iOS ShareStorySheet.post + resolveAudience:
     * Everyone leaves out the hidden list; My Chats follows its own except / only list; a hand
     * picked list (Custom, One-Time) is only those people. Always within my accepted 1:1 chats.
     */
    fun resolve(
        contacts: Set<String>,
        choice: StoryAudienceChoice,
        picked: Set<String>,
        myFriends: StoryListDoc,
        hidden: Set<String>,
        glowers: Set<String> = emptySet(),
        blocked: Set<String> = emptySet(),
    ): Pair<Set<String>, String> {
        // Glowers travels as an explicit list and may reach people I have never chatted with
        // (iOS resolveAudience glowReach), never anyone blocked either way.
        if (choice == StoryAudienceChoice.GLOWERS) return glowers.filter { it in contacts || it !in blocked }.toSet() to MODE_ONLY
        val included: Set<String> = when {
            choice.needsPeople -> picked
            choice == StoryAudienceChoice.MY_CHATS && myFriends.mode == MODE_ONLY -> myFriends.members.toSet()
            else -> emptySet()
        }
        val excluded: Set<String> = when (choice) {
            StoryAudienceChoice.EVERYONE -> hidden
            StoryAudienceChoice.MY_CHATS -> if (myFriends.mode == MODE_EXCEPT) myFriends.members.toSet() else emptySet()
            else -> emptySet()
        }
        val pool = if (choice == StoryAudienceChoice.EVERYONE) contacts - hidden else contacts
        if (included.isNotEmpty()) return included.intersect(contacts) to MODE_ONLY
        if (choice.needsPeople) return emptySet<String>() to MODE_ONLY
        if (excluded.isNotEmpty()) return (pool - excluded) to MODE_EXCEPT
        return pool to MODE_ALL
    }

    /** `allowsReplies` on the story: the My Chats switch; every other audience allows them (iOS). */
    fun allowsReplies(choice: StoryAudienceChoice, myFriends: StoryListDoc, glowers: StoryListDoc = defaultGlowers): Boolean = when (choice) {
        StoryAudienceChoice.MY_CHATS -> myFriends.allowReplies
        StoryAudienceChoice.GLOWERS -> glowers.allowReplies
        else -> true
    }

    /** `captureProtected`: always on for a one-time story, else the Block screenshots switch. */
    fun captureProtected(choice: StoryAudienceChoice, switchOn: Boolean): Boolean =
        choice == StoryAudienceChoice.ONE_TIME || switchOn

    /** iOS captureFooter / captureLockedFooter. */
    fun captureFooter(choice: StoryAudienceChoice): String =
        if (choice == StoryAudienceChoice.ONE_TIME) "Always on for a one-time story." else "Screenshots and screen recordings come out blank."

    /**
     * May I reply to this story (iOS StoryViewer `canMessage`): the author lets replies in and is one
     * of my accepted 1:1 chats. Otherwise the viewer says [REPLY_LOCKED].
     */
    fun canReply(s: Story, contacts: Set<String>): Boolean = s.allowsReplies && s.groupId == null && s.authorUid in contacts

    const val REPLY_LOCKED = "You can’t reply to this story"
    /** The react bar (iOS StoryViewer emojis), its line, and the reply field's placeholder. */
    val REACTIONS = listOf("😭", "😍", "🤣", "❤️", "😄", "🔥", "❤️‍🔥")
    const val REACT_LINE = "Send reaction as a private message"
}

/**
 * My story lists, live from `users/{me}/storyLists` (iOS StoryAudienceStore). Writes are
 * fire-and-forget with a merge, as iOS; the listener confirms them.
 */
object StoryPrivacyStore {
    private val _myFriends = MutableStateFlow(StoryPrivacyRules.defaultMyFriends)
    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    private val _glowers = MutableStateFlow(StoryPrivacyRules.defaultGlowers)
    private val _custom = MutableStateFlow<List<StoryListDoc>>(emptyList())
    private val _links = MutableStateFlow<Map<String, StoryAudienceLink>>(emptyMap())
    val myFriends: StateFlow<StoryListDoc> = _myFriends
    val hidden: StateFlow<Set<String>> = _hidden
    /** The Glowers audience, its except-list (iOS StoryAudienceStore.glowers). */
    val glowers: StateFlow<StoryListDoc> = _glowers
    /** My custom lists, oldest first (iOS StoryAudienceStore.custom). */
    val custom: StateFlow<List<StoryListDoc>> = _custom
    /** Story id > the list(s) it went to (iOS storyLinks, users/{me}/storyAudiences). */
    val links: StateFlow<Map<String, StoryAudienceLink>> = _links
    private var reg: ListenerRegistration? = null
    private var linkReg: ListenerRegistration? = null
    private var loadedFor = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db get() = FirebaseFirestore.getInstance()

    fun start(me: String) {
        if (me.isEmpty() || me == loadedFor) return
        reg?.remove(); linkReg?.remove()
        loadedFor = me
        _myFriends.value = StoryPrivacyRules.defaultMyFriends
        _hidden.value = emptySet()
        _glowers.value = StoryPrivacyRules.defaultGlowers
        _custom.value = emptyList()
        _links.value = emptyMap()
        val user = db.collection("users").document(me)
        reg = user.collection("storyLists").addSnapshotListener { snap, _ ->
            snap ?: return@addSnapshotListener
            // The whole snapshot each time, as iOS: a deleted list or a missing doc is its default again.
            var friends = StoryPrivacyRules.defaultMyFriends
            var hidden = emptySet<String>()
            var glowers = StoryPrivacyRules.defaultGlowers
            val lists = mutableListOf<StoryListDoc>()
            for (d in snap.documents) {
                val l = StoryPrivacyRules.decode(d.id, d.data ?: continue) ?: continue
                when {
                    d.id == StoryPrivacyRules.MY_FRIENDS_ID -> friends = l
                    d.id == StoryPrivacyRules.GLOWERS_ID -> glowers = l
                    l.kind == "hidden" -> hidden = l.members.toSet()
                    l.kind == StoryPrivacyRules.KIND_CUSTOM -> lists.add(l)
                }
            }
            _myFriends.value = friends
            _hidden.value = hidden
            _glowers.value = glowers
            _custom.value = lists.sortedBy { it.createdAtMs }
        }
        linkReg = user.collection("storyAudiences").addSnapshotListener { snap, _ ->
            snap ?: return@addSnapshotListener
            _links.value = snap.documents.associate { d ->
                d.id to StoryAudienceLink((d.get("listIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList(), d.getString("name") ?: "")
            }
        }
    }

    fun stop() { reg?.remove(); reg = null; linkReg?.remove(); linkReg = null; loadedFor = "" }

    val canAddCustom: Boolean get() = _custom.value.size < StoryPrivacyRules.MAX_CUSTOM

    /** iOS createCustom: shown at once; the write is fire-and-forget and the listener confirms it. */
    fun createCustom(name: String, members: Collection<String>, allowReplies: Boolean, emoji: String): StoryListDoc {
        val l = StoryPrivacyRules.newCustom(java.util.UUID.randomUUID().toString().uppercase(), name, members, allowReplies, emoji,
            System.currentTimeMillis())
        _custom.value = _custom.value + l
        write(l)
        return l
    }

    fun updateCustom(l: StoryListDoc) {
        if (_custom.value.none { it.id == l.id }) return
        _custom.value = _custom.value.map { if (it.id == l.id) l else it }
        write(l)
    }

    /** iOS delete: the list goes; stories already sent to it are not touched. */
    fun deleteCustom(id: String) {
        val me = loadedFor.ifEmpty { return }
        _custom.value = _custom.value.filterNot { it.id == id }
        db.collection("users").document(me).collection("storyLists").document(id).delete()
    }

    /**
     * iOS linkStory: which custom list(s) a story went to, at users/{me}/storyAudiences/{storyId}
     * {listIds, name, createdAt}. Any other audience removes a link the story had.
     */
    fun linkStory(storyId: String, label: String, listIds: List<String>, name: String) {
        val me = loadedFor.ifEmpty { return }
        val ref = db.collection("users").document(me).collection("storyAudiences").document(storyId)
        if (label != StoryPrivacyRules.KIND_CUSTOM || (listIds.isEmpty() && name.isEmpty())) {
            if (_links.value[storyId] == null) return
            _links.value = _links.value - storyId
            ref.delete()
            return
        }
        val link = StoryAudienceLink(listIds.take(StoryPrivacyRules.MAX_CUSTOM), name.take(200))
        _links.value = _links.value + (storyId to link)
        ref.set(mapOf("listIds" to link.listIds, "name" to link.name, "createdAt" to FieldValue.serverTimestamp()))
    }

    private fun write(l: StoryListDoc) {
        val me = loadedFor.ifEmpty { return }
        db.collection("users").document(me).collection("storyLists").document(l.id)
            .set(StoryPrivacyRules.encode(l), com.google.firebase.firestore.SetOptions.merge())
    }

    fun updateMyFriends(l: StoryListDoc) { _myFriends.value = l; write(l) }

    /**
     * Hide my stories from people (the Everyone page), and back. Hiding also takes them out of my
     * live public stories, as iOS setHidden > revokeAudience(publicOnly); unhiding gives nothing back.
     */
    fun setHidden(uids: Set<String>, hidden: Boolean) {
        val now = if (hidden) _hidden.value + uids else _hidden.value - uids
        _hidden.value = now
        write(StoryPrivacyRules.emptyHidden.copy(members = now.toList()))
        if (hidden) { val me = loadedFor; scope.launch { uids.forEach { revokePublic(me, it) } } }
    }

    private suspend fun revokePublic(me: String, uid: String) {
        val snap = runCatching { db.collection("stories").whereEqualTo("authorUid", me).get().await() }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        for (d in snap.documents) {
            val exp = d.getTimestamp("expiresAt")?.toDate()?.time ?: continue
            if (exp <= now || d.getBoolean("public") != true) continue
            runCatching {
                d.reference.update("recipientUids", FieldValue.arrayRemove(StoryAudienceToken.token(uid), uid)).await()
            }
        }
    }
}

/** Which custom list(s) one of my stories went to (iOS StoryAudienceLink), mine alone. */
data class StoryAudienceLink(val listIds: List<String>, val name: String)

/**
 * Get notifications for one person's stories (iOS StoryNotifyStore): off for everybody until turned
 * on for someone. Each one on is `users/{me}/storyNotify/{them}` {at: server time, author}. The push
 * itself is the server's (onStoryMediaReady > notifyStoryFollowers).
 */
object StoryNotify {
    private val _on = MutableStateFlow<Set<String>>(emptySet())
    val on: StateFlow<Set<String>> = _on
    private var loadedFor = ""
    private val db get() = FirebaseFirestore.getInstance()

    fun load(me: String) {
        if (me.isEmpty() || me == loadedFor) return
        loadedFor = me
        _on.value = emptySet()
        db.collection("users").document(me).collection("storyNotify").get().addOnSuccessListener { snap ->
            // Union, not replace: one tapped while this was on its way is already in.
            if (loadedFor == me) _on.value = _on.value + snap.documents.map { it.id }
        }
    }

    /** Turns it on or off for [author] and answers the new state; a refused write puts it back. */
    fun toggle(me: String, author: String): Boolean {
        if (me.isEmpty() || author.isEmpty() || author == me) return false
        val ref = db.collection("users").document(me).collection("storyNotify").document(author)
        if (author in _on.value) {
            _on.value = _on.value - author
            ref.delete().addOnFailureListener { if (loadedFor == me) _on.value = _on.value + author }
            return false
        }
        _on.value = _on.value + author
        ref.set(StoryNotifyRules.doc(author, FieldValue.serverTimestamp()))
            .addOnFailureListener { if (loadedFor == me) _on.value = _on.value - author }
        return true
    }
}

/** The pure half of Get notifications: the document, the menu item and the bar (iOS StoryNotifyBar). */
object StoryNotifyRules {
    fun doc(author: String, at: Any): Map<String, Any> = mapOf("at" to at, "author" to author)
    fun menuTitle(on: Boolean) = if (on) "Mute notifications" else "Get notifications"
    /** The bar's line around the bold name: before, name, after. */
    fun barParts(on: Boolean, name: String): Triple<String, String, String> =
        if (on) Triple("You will receive a notification whenever ", name, " posts a story.")
        else Triple("You will no longer receive a notification when ", name, " posts a story.")
}

/**
 * Hide Stories (iOS StoryPrefs "hiddenStories"): people whose stories leave Recent and Viewed for
 * the dimmed Hidden Stories list. On this phone only, as iOS.
 */
object StoryMutes {
    private const val PREFS = "fariin_stories"
    private const val KEY = "hiddenStories"
    private val _set = MutableStateFlow<Set<String>>(emptySet())
    val set: StateFlow<Set<String>> = _set
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        _set.value = parse(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: "")
    }

    /** Sign-out (iOS SessionWipe.wipeAccountPrefs): the next account must not inherit whose stories
     *  this one hid, nor its story view-receipts choice. */
    fun wipe(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).remove("storyViewReceipts").apply()
        _set.value = emptySet()
        loaded = false
    }

    fun setHidden(ctx: Context, uid: String, hidden: Boolean) {
        if (uid.isEmpty()) return
        load(ctx)
        val now = if (hidden) _set.value + uid else _set.value - uid
        _set.value = now
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, join(now)).apply()
    }

    /** iOS keeps the set as one space-separated string. */
    fun parse(s: String): Set<String> = s.split(' ').filter { it.isNotEmpty() }.toSet()
    fun join(s: Set<String>): String = s.joinToString(" ")

    /** Splits the rings into what the list shows and the Hidden Stories list (iOS visibleFriends / hiddenFriends). */
    fun split(groups: List<StoryGroup>, hidden: Set<String>): Pair<List<StoryGroup>, List<StoryGroup>> =
        groups.partition { g -> g.stories.firstOrNull()?.authorUid?.let { it !in hidden } ?: true }
}

/** Posted Stories (iOS PostedStoriesView): the filter and the folders by audience, pure. */
object PostedStoriesRules {
    enum class Filter(val title: String, val explain: String) {
        ALL("All posted stories", "Every story you have posted that is still live"),
        FRIENDS("My Chats", "Stories visible to everyone you chat with"),
        CUSTOM("All Custom", "Stories shared with a custom audience"),
        ONE_TIME("View once", "Stories each person can watch once"),
        ;

        fun matches(s: Story): Boolean = when (this) {
            ALL -> true
            FRIENDS -> s.audienceLabel == "friends" || s.audienceLabel == "everyone"
            CUSTOM -> s.audienceLabel == "custom"
            ONE_TIME -> s.oneTime
        }
    }

    data class Folder(val key: String, val name: String, val kind: String, val stories: List<Story>)

    /** iOS StoryShareRights.allows for my own story: only one that went to Everyone, not one-time, not screenshot-blocked. */
    fun canShare(s: Story): Boolean = s.audienceLabel == "everyone" && !s.oneTime && !s.captureProtected && s.groupId == null

    /** Edit Viewers: a one-time story's audience is spent as it is watched, and a group story's is its group. */
    fun canEditViewers(s: Story): Boolean = !s.oneTime && s.groupId == null

    /**
     * iOS KulanApp.storyLink: fariin.com/<username>/s/<id>, or /s/<id> with no username yet. Android
     * story ids are already the short 10-character form.
     */
    fun storyLink(id: String, handle: String?): String {
        val i = enc(id)
        val h = handle?.trim().orEmpty()
        return if (h.isEmpty()) "https://fariin.com/s/$i" else "https://fariin.com/${enc(h)}/s/$i"
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** One folder per audience in the order first met (iOS PostedStoriesView.folders). */
    fun folders(rows: List<Story>, mergeCustom: Boolean = true, customName: (Story) -> String? = { null }): List<Folder> {
        val out = LinkedHashMap<String, Triple<String, String, MutableList<Story>>>()
        for (s in rows) {
            val (kind, name) = when {
                s.oneTime -> "oneTime" to "View once"
                s.audienceLabel == "everyone" -> "everyone" to "Everyone"
                s.audienceLabel == "friends" -> "friends" to "My Chats"
                s.audienceLabel == "glowers" -> "glowers" to "Glowers"
                s.audienceLabel == "group" -> "group" to "Group Stories"
                // All Custom tells the lists apart by name; All keeps them in one folder (iOS mergeCustom).
                s.audienceLabel == "custom" -> "custom" to (if (mergeCustom) "Custom" else customName(s) ?: "Custom")
                else -> "friends" to "My Chats"
            }
            out.getOrPut("$kind:$name") { Triple(kind, name, mutableListOf()) }.third.add(s)
        }
        return out.map { (k, v) -> Folder(k, v.second, v.first, v.third) }
    }
}
