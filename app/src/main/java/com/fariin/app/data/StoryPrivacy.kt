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

    /** iOS StoryAudienceStore.write: every field, `emoji` "" and the built-ins' epoch date. */
    fun encode(l: StoryListDoc): Map<String, Any> = mapOf(
        "kind" to l.kind,
        "name" to l.name,
        "mode" to l.mode,
        "members" to l.members,
        "allowReplies" to l.allowReplies,
        "emoji" to "",
        "createdAt" to Timestamp(Date(0)),
    )

    /** iOS decode, with its self-heal: My Chats narrowed to nobody is just "all". */
    fun decode(id: String, data: Map<String, Any?>): StoryListDoc? {
        val kind = data["kind"] as? String ?: return null
        val members = (data["members"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        var mode = (data["mode"] as? String)?.takeIf { it in listOf(MODE_ALL, MODE_EXCEPT, MODE_ONLY) } ?: MODE_ALL
        if (kind == "myFriends" && mode != MODE_ALL && members.isEmpty()) mode = MODE_ALL
        return StoryListDoc(id, kind, mode, members, data["allowReplies"] as? Boolean ?: true, data["name"] as? String ?: "")
    }

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
    ): Pair<Set<String>, String> {
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
    fun allowsReplies(choice: StoryAudienceChoice, myFriends: StoryListDoc): Boolean =
        if (choice == StoryAudienceChoice.MY_CHATS) myFriends.allowReplies else true

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
    val myFriends: StateFlow<StoryListDoc> = _myFriends
    val hidden: StateFlow<Set<String>> = _hidden
    private var reg: ListenerRegistration? = null
    private var loadedFor = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db get() = FirebaseFirestore.getInstance()

    fun start(me: String) {
        if (me.isEmpty() || me == loadedFor) return
        reg?.remove()
        loadedFor = me
        _myFriends.value = StoryPrivacyRules.defaultMyFriends
        _hidden.value = emptySet()
        reg = db.collection("users").document(me).collection("storyLists").addSnapshotListener { snap, _ ->
            snap ?: return@addSnapshotListener
            for (d in snap.documents) {
                val l = StoryPrivacyRules.decode(d.id, d.data ?: continue) ?: continue
                when (d.id) {
                    StoryPrivacyRules.MY_FRIENDS_ID -> _myFriends.value = l
                    StoryPrivacyRules.HIDDEN_ID -> _hidden.value = l.members.toSet()
                }
            }
        }
    }

    fun stop() { reg?.remove(); reg = null; loadedFor = "" }

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

    /** One folder per audience in the order first met (iOS PostedStoriesView.folders). */
    fun folders(rows: List<Story>): List<Folder> {
        val out = LinkedHashMap<String, Triple<String, String, MutableList<Story>>>()
        for (s in rows) {
            val (kind, name) = when {
                s.oneTime -> "oneTime" to "View once"
                s.audienceLabel == "everyone" -> "everyone" to "Everyone"
                s.audienceLabel == "friends" -> "friends" to "My Chats"
                s.audienceLabel == "glowers" -> "glowers" to "Glowers"
                s.audienceLabel == "group" -> "group" to "Group Stories"
                s.audienceLabel == "custom" -> "custom" to "Custom"
                else -> "friends" to "My Chats"
            }
            out.getOrPut("$kind:$name") { Triple(kind, name, mutableListOf()) }.third.add(s)
        }
        return out.map { (k, v) -> Folder(k, v.second, v.first, v.third) }
    }
}
