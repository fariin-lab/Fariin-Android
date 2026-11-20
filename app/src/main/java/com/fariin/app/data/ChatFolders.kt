package com.fariin.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * FARIIN PLUS: CHAT FOLDERS (feature `chatFolders`; iOS ChatFolders.swift, round 2).
 *
 * Chats sorted into folders shown as chips over the chat list, in the person's own order. All Chats
 * is always first; a folder takes whole chat types plus chats picked one by one, minus chats left out.
 * The suggestions (1:1, Unread, Groups, Channels) are only templates: Add makes an ordinary folder.
 *
 * They live in plusFolders/{uid} on the server (only plusFoldersGet / plusFoldersSet touch it), so they
 * follow the person to every phone; a copy is kept here per account for an instant start. Shown only
 * while Plus is visible and `chatFolders` is on. When Plus ends nothing is deleted: the chips hide and
 * come back with Plus.
 */
data class ChatFolder(
    val id: String,
    val name: String,
    val kind: String,                       // "all" | "custom" (old saves: direct, unread, groups, channels)
    val types: List<String> = emptyList(),  // "direct" | "groups" | "channels" (old: "unread")
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val unreadOnly: Boolean = false,
    val includeMuted: Boolean = true,
) {
    val isAll: Boolean get() = kind == "all"

    class Suggestion(val id: String, val name: String, val types: List<String>, val unreadOnly: Boolean, val summary: String) {
        fun make() = ChatFolder(newId(), name, "custom", types, unreadOnly = unreadOnly)
        fun matches(f: ChatFolder) = f.kind == "custom" && f.include.isEmpty() && f.exclude.isEmpty() &&
            f.types.toSet() == types.toSet() && f.unreadOnly == unreadOnly
    }

    /** The suggestion this folder still is, if any (it keeps that one's icon and grey line). */
    val suggestion: Suggestion? get() = SUGGESTIONS.firstOrNull { it.matches(this) }

    /** Folders saved before round 2, read as what they meant. */
    fun normalized(): ChatFolder {
        var f = when (kind) {
            "all", "custom" -> this
            "direct" -> copy(kind = "custom", types = listOf("direct"))
            "groups" -> copy(kind = "custom", types = listOf("groups"))
            "channels" -> copy(kind = "custom", types = listOf("channels"))
            "unread" -> copy(kind = "custom", types = listOf("direct", "groups", "channels"), unreadOnly = true)
            else -> this
        }
        if (f.kind == "custom" && "unread" in f.types) {
            val rest = f.types.filter { it != "unread" }
            f = if (rest.isEmpty()) f.copy(types = listOf("direct", "groups", "channels"), unreadOnly = true) else f.copy(types = rest)
        }
        return f
    }

    /** The grey line under the name on the Chat Folders page. */
    val summary: String? get() {
        if (isAll) return null
        suggestion?.let { return it.summary }
        val parts = ArrayList<String>()
        if (types.isNotEmpty()) parts += if (types.size == 1) "1 Chat Type" else "${types.size} Chat Types"
        if (include.isNotEmpty()) parts += if (include.size == 1) "1 Chat" else "${include.size} Chats"
        if (exclude.isNotEmpty()) parts += if (exclude.size == 1) "1 Chat Excluded" else "${exclude.size} Chats Excluded"
        return parts.joinToString(", ").ifEmpty { null }
    }

    fun typeTakes(c: Conversation, me: String): Boolean {
        val channel = c.isChannel || c.id == OfficialChannel.CID
        val direct = !c.isGroup && !channel
        val group = c.isGroup && !channel
        return types.any { t ->
            when (t) {
                "direct" -> direct
                "groups" -> group
                "channels" -> channel
                "unread" -> !c.isBlockedByMe(me) && c.hasUnreadMark(me)
                else -> false
            }
        }
    }

    /** Taken in at all (picked or by a type, and not left out), before the two switches. */
    fun covers(c: Conversation, me: String): Boolean = when {
        isAll -> true
        kind != "custom" -> false
        c.id in exclude -> false
        else -> c.id in include || typeTakes(c, me)
    }

    /** Shows in the folder now: (picked OR by type) AND NOT left out AND unread-if-asked AND not-muted-if-asked. */
    fun contains(c: Conversation, me: String, now: Double): Boolean {
        if (isAll) return true
        if (!covers(c, me)) return false
        if (unreadOnly && (c.isBlockedByMe(me) || !c.hasUnreadMark(me))) return false
        if (!includeMuted && c.isMuted(me, now)) return false
        return true
    }

    fun toMap(): Map<String, Any> = mapOf("id" to id, "name" to name, "kind" to kind, "types" to types,
        "include" to include, "exclude" to exclude, "unreadOnly" to unreadOnly, "includeMuted" to includeMuted)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("kind", kind); put("types", JSONArray(types))
        put("include", JSONArray(include)); put("exclude", JSONArray(exclude))
        put("unreadOnly", unreadOnly); put("includeMuted", includeMuted)
    }

    companion object {
        const val MAX_FOLDERS = 20
        const val NAME_MAX = 32
        const val NAME_RAW_MAX = 128
        const val CHATS_MAX = 200
        val ALL = ChatFolder("all", "All Chats", "all")
        val PICKABLE = listOf("direct", "groups", "channels")
        fun typeName(t: String) = when (t) { "direct" -> "1:1 Chats"; "groups" -> "Groups"; else -> "Channels" }

        val SUGGESTIONS = listOf(
            Suggestion("direct", "1:1 Chats", listOf("direct"), false, "Only chats with one person"),
            Suggestion("unread", "Unread", listOf("direct", "groups", "channels"), true, "Chats with unread messages"),
            Suggestion("groups", "Groups", listOf("groups"), false, "Only group chats"),
            Suggestion("channels", "Channels", listOf("channels"), false, "Only channels"),
        )

        fun newId(): String = "f" + java.util.UUID.randomUUID().toString().replace("-", "").take(12).lowercase()

        /** Cleaned as the server cleans: control chars and line breaks to spaces, spaces collapsed, trimmed, cut. */
        fun cleanName(raw: String): String {
            val sb = StringBuilder()
            raw.codePoints().forEach { v -> if (v < 32 || v == 127 || v == 0x2028 || v == 0x2029) sb.append(' ') else sb.appendCodePoint(v) }
            val collapsed = sb.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            // Characters as a person sees them (grapheme clusters), as the server counts them.
            val it = java.text.BreakIterator.getCharacterInstance()
            it.setText(collapsed)
            var end = 0; var n = 0
            while (n < NAME_MAX) { val next = it.next(); if (next == java.text.BreakIterator.DONE) { end = collapsed.length; break }; end = next; n++ }
            var out = collapsed.substring(0, end)
            // Over the server's code-point cap: whole characters come off the end (as iOS), never
            // half an emoji.
            while (out.isNotEmpty() && out.codePointCount(0, out.length) > NAME_RAW_MAX) {
                val b = java.text.BreakIterator.getCharacterInstance().apply { setText(out) }
                out = out.substring(0, b.preceding(out.length).coerceAtLeast(0))
            }
            return out.trim()
        }

        fun fromMap(d: Map<*, *>): ChatFolder? {
            val id = (d["id"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
            val name = (d["name"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
            val kind = (d["kind"] as? String)?.takeIf { it in setOf("all", "custom", "direct", "unread", "groups", "channels") } ?: return null
            fun strs(v: Any?) = (v as? List<*>).orEmpty().filterIsInstance<String>()
            return ChatFolder(id, name, kind, strs(d["types"]).filter { it in setOf("direct", "groups", "channels", "unread") },
                strs(d["include"]), strs(d["exclude"]), d["unreadOnly"] == true, d["includeMuted"] as? Boolean ?: true).normalized()
        }

        fun fromJson(o: JSONObject): ChatFolder? {
            fun strs(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.optString(it) }
            return fromMap(mapOf("id" to o.optString("id"), "name" to o.optString("name"), "kind" to o.optString("kind"),
                "types" to strs(o.optJSONArray("types")), "include" to strs(o.optJSONArray("include")),
                "exclude" to strs(o.optJSONArray("exclude")), "unreadOnly" to o.optBoolean("unreadOnly"),
                "includeMuted" to o.optBoolean("includeMuted", true)))
        }

        fun list(any: Any?): List<ChatFolder> = (any as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let(::fromMap) }
    }
}

object ChatFoldersStore {
    private val fns get() = FirebaseFunctions.getInstance(PlusStore.REGION)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The folders in order, All Chats first; empty until this account has any. */
    var folders by mutableStateOf<List<ChatFolder>>(emptyList()); private set
    var selectedId by mutableStateOf(ChatFolder.ALL.id); private set
    /** A save the server refused with a reason the person can act on (the edit is kept). */
    var saveError by mutableStateOf<String?>(null)

    private var app: Context? = null
    private var uid: String? = null
    private var dirty = false
    private var editSeq = 0
    private var refusedSeq: Int? = null
    private var base: List<ChatFolder> = emptyList()
    private var baseAt: Double? = null
    private var saveJob: Job? = null
    private var lastFetchAt = 0L
    private var fetching = false

    // ---- Who sees them ----

    /** The Settings row and the page: Plus visible and the feature not off. */
    val settingsShown: Boolean get() = PlusStore.visible && PlusStore.state("chatFolders") != PlusStore.State.OFF
    /** Folders may be made, changed and shown. */
    val featureOn: Boolean get() = PlusStore.visible && PlusStore.state("chatFolders") == PlusStore.State.ON
    /** The chips show: the feature is on and there is more than All Chats. */
    val active: Boolean get() = featureOn && folders.size > 1
    /** The ≡ menu's Hide Folder Tabs (iOS 2026-11-20), per account on this phone; the first folder shows them again. */
    var tabsHidden by mutableStateOf(false); private set
    /** The tab bar is drawn. */
    val tabsShown: Boolean get() = active && !tabsHidden
    /** The chosen folder, null for All Chats or while folders are not in use. */
    val current: ChatFolder? get() = if (!tabsShown) null else folders.firstOrNull { it.id == selectedId && !it.isAll }

    fun hideTabs(hidden: Boolean) {
        if (tabsHidden == hidden) return
        tabsHidden = hidden
        val me = uid ?: return
        app?.let { prefs(it).edit().putBoolean("tabsHidden.$me", hidden).apply() }
    }

    /** A menu filter that already is a folder (by its rule, not its name): "unread", "groups" or "channels". */
    fun hasFolderFor(suggestionId: String): Boolean =
        active && ChatFolder.SUGGESTIONS.firstOrNull { it.id == suggestionId }?.let { s -> folders.any { s.matches(it) } } == true
    val customFolders: List<ChatFolder> get() = folders.filter { !it.isAll }
    val canAddFolder: Boolean get() = featureOn && maxOf(folders.size, 1) < ChatFolder.MAX_FOLDERS

    // ---- Account ----

    /** This account's copy from the phone (once per account), then the server when the last answer is old. */
    fun refresh(c: Context, maxAgeMs: Long = 300_000) {
        val me = com.fariin.app.Fb.auth.currentUser?.uid ?: return
        app = c.applicationContext
        if (uid != me) switchTo(me)
        if (!settingsShown) return
        if (lastFetchAt > 0 && System.currentTimeMillis() - lastFetchAt < maxAgeMs && !dirty) return
        fetch()
    }

    fun clear(c: Context) {
        val p = prefs(c); val e = p.edit()
        p.all.keys.filter { it.startsWith(CACHE_PREFIX) }.forEach { e.remove(it) }
        e.apply()
        uid = null; saveJob?.cancel(); saveJob = null
        dirty = false; refusedSeq = null; base = emptyList(); baseAt = null; lastFetchAt = 0L; editSeq++
        if (folders.isNotEmpty()) folders = emptyList()
        if (selectedId != ChatFolder.ALL.id) selectedId = ChatFolder.ALL.id
        saveError = null
    }

    private fun switchTo(me: String) {
        saveJob?.cancel(); saveJob = null
        uid = me; lastFetchAt = 0L; editSeq++; refusedSeq = null
        val cached = readCache(me)
        folders = cached?.folders ?: emptyList()
        selectedId = cached?.selected ?: ChatFolder.ALL.id
        dirty = cached?.dirty ?: false
        base = cached?.base ?: emptyList()
        baseAt = cached?.baseAt
        tabsHidden = app?.let { prefs(it).getBoolean("tabsHidden.$me", false) } ?: false
    }

    fun select(id: String) {
        if (selectedId == id) return
        selectedId = id
        writeCache()
    }

    // ---- Changes (each one saves) ----

    fun replaceFolders(list: List<ChatFolder>) {
        val next = ArrayList(list.filter { !it.isAll })
        next.add(0, folders.firstOrNull { it.isAll } ?: ChatFolder.ALL)
        val capped = next.take(ChatFolder.MAX_FOLDERS)
        if (capped == folders) return
        // The first folder brings the tabs (back) by themselves.
        if (folders.size <= 1 && capped.size > 1) hideTabs(false)
        folders = capped
        if (folders.none { it.id == selectedId }) selectedId = ChatFolder.ALL.id
        editSeq++; dirty = true
        writeCache()
        scheduleSave()
    }

    fun upsert(f: ChatFolder) {
        val list = (if (folders.isEmpty()) listOf(ChatFolder.ALL) else folders).toMutableList()
        val i = list.indexOfFirst { it.id == f.id }
        if (i >= 0) list[i] = f else list.add(f)
        replaceFolders(list)
    }

    fun remove(id: String) { if (id != ChatFolder.ALL.id) replaceFolders(folders.filter { it.id != id }) }

    /** Moves the custom folder at [from] (index among the custom folders) to [to]. */
    fun move(from: Int, to: Int) {
        val rest = customFolders.toMutableList()
        if (from !in rest.indices) return
        val f = rest.removeAt(from)
        rest.add(to.coerceIn(0, rest.size), f)
        replaceFolders(listOf(folders.firstOrNull { it.isAll } ?: ChatFolder.ALL) + rest)
    }

    val missingSuggestions: List<ChatFolder.Suggestion>
        get() = ChatFolder.SUGGESTIONS.filter { s -> folders.none { s.matches(it) } }

    fun addSuggestion(s: ChatFolder.Suggestion) {
        if (!canAddFolder || folders.any { s.matches(it) }) return
        upsert(s.make())
    }

    /** Add to Folder: picked by hand and off the left-out list. Returns the notice to show. */
    fun add(c: Conversation, folderId: String, me: String): String? {
        if (!featureOn) return null
        var f = folders.firstOrNull { it.id == folderId && it.kind == "custom" } ?: return null
        if (f.covers(c, me)) return "Already in ${f.name}"
        f = f.copy(exclude = f.exclude - c.id)
        if (c.id !in f.include) {
            if (f.include.size >= ChatFolder.CHATS_MAX) return "${f.name} is full"
            f = f.copy(include = f.include + c.id)
        }
        upsert(f)
        return "Added to ${f.name}"
    }

    /** Remove from This Folder: off the picked list; onto the left-out list if a type would bring it back. */
    fun remove(c: Conversation, folderId: String, me: String): String? {
        if (!featureOn) return null
        var f = folders.firstOrNull { it.id == folderId && it.kind == "custom" } ?: return null
        f = f.copy(include = f.include - c.id)
        if (f.typeTakes(c, me) && c.id !in f.exclude) {
            if (f.exclude.size >= ChatFolder.CHATS_MAX) return "${f.name} is full"
            f = f.copy(exclude = f.exclude + c.id)
        }
        upsert(f)
        return "Removed from ${f.name}"
    }

    // ---- Server ----

    private fun fetch() {
        val me = uid ?: return
        if (fetching) return
        fetching = true
        val seq = editSeq
        scope.launch {
            val d = try { fns.getHttpsCallable("plusFoldersGet").call(emptyMap<String, Any>()).await().getData() as? Map<*, *> } catch (_: Exception) { null }
            fetching = false
            if (uid != me || d == null) return@launch
            lastFetchAt = System.currentTimeMillis()
            val list = ChatFolder.list(d["folders"])
            val at = (d["updatedAt"] as? Number)?.toDouble() ?: 0.0
            if (dirty || seq != editSeq) {
                if (baseAt == null || at > (baseAt ?: 0.0)) mergeRemote(list, at)
                if (featureOn) scheduleSave(0)
                return@launch
            }
            base = list; baseAt = at
            if (list != folders) {
                folders = list
                if (list.none { it.id == selectedId }) selectedId = ChatFolder.ALL.id
            }
            writeCache()
        }
    }

    /** Another phone saved since: merge folder by folder against [base] (see the iOS store). */
    private fun mergeRemote(remote: List<ChatFolder>, at: Double) {
        val local = folders
        val b = base.associateBy { it.id }; val l = local.associateBy { it.id }; val r = remote.associateBy { it.id }
        val merged = HashMap<String, ChatFolder>()
        for (id in b.keys + l.keys + r.keys) {
            val bf = b[id]; val lf = l[id]; val rf = r[id]
            if (lf != bf) { if (lf != null && !(bf != null && rf == null)) merged[id] = lf }
            else if (rf != null) merged[id] = rf
        }
        fun common(list: List<ChatFolder>) = list.map { it.id }.filter { it in b && it in l }
        val movedHere = common(local) != common(base)
        val first = if (movedHere) local.map { it.id } else remote.map { it.id }
        val second = if (movedHere) remote.map { it.id } else local.map { it.id }
        val order = LinkedHashSet<String>()
        for (id in first + second) if (merged.containsKey(id)) order.add(id)
        val next = order.mapNotNull { merged[it] }.filter { !it.isAll }.toMutableList()
        next.add(0, remote.firstOrNull { it.isAll } ?: local.firstOrNull { it.isAll } ?: ChatFolder.ALL)
        base = remote; baseAt = at
        val capped = next.take(ChatFolder.MAX_FOLDERS)
        if (capped != folders) {
            folders = capped
            if (folders.none { it.id == selectedId }) selectedId = ChatFolder.ALL.id
        }
        editSeq++; dirty = true
        writeCache()
    }

    private fun scheduleSave(delayMs: Long = 800) {
        saveJob?.cancel()
        saveJob = scope.launch { if (delayMs > 0) delay(delayMs); send() }
    }

    private suspend fun send() {
        val me = uid ?: return
        if (!featureOn || !dirty) return
        val seq = editSeq
        if (refusedSeq == seq) return
        val list = folders.ifEmpty { listOf(ChatFolder.ALL) }
        val payload = HashMap<String, Any>()
        payload["folders"] = list.map { it.toMap() }
        payload["baseUpdatedAt"] = baseAt ?: 0.0
        try {
            val d = fns.getHttpsCallable("plusFoldersSet").call(payload).await().getData() as? Map<*, *>
            if (uid != me) return
            saveError = null
            lastFetchAt = System.currentTimeMillis()
            val clean = ChatFolder.list(d?.get("folders"))
            if (clean.isNotEmpty()) base = clean
            (d?.get("updatedAt") as? Number)?.toDouble()?.let { baseAt = it }
            if (seq != editSeq) { writeCache(); return }
            dirty = false
            if (clean.isNotEmpty() && clean != folders) folders = clean
            writeCache()
        } catch (e: FirebaseFunctionsException) {
            if (uid != me) return
            val details = e.details as? Map<*, *>
            when {
                e.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> scheduleSave(1200)
                e.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION && details?.get("code") == "STALE" -> {
                    mergeRemote(ChatFolder.list(details["folders"]), (details["updatedAt"] as? Number)?.toDouble() ?: 0.0)
                    scheduleSave(1200)
                }
                e.code == FirebaseFunctionsException.Code.INVALID_ARGUMENT -> {
                    saveError = e.message; refusedSeq = seq; writeCache()
                }
                e.code == FirebaseFunctionsException.Code.PERMISSION_DENIED || e.code == FirebaseFunctionsException.Code.NOT_FOUND -> {
                    dirty = false; writeCache()
                }
                else -> {}
            }
        } catch (_: Exception) {}
    }

    // ---- This phone's copy ----

    private class Cache(val folders: List<ChatFolder>, val selected: String, val dirty: Boolean, val base: List<ChatFolder>, val baseAt: Double?)
    private const val CACHE_PREFIX = "chatFolders.v1."
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("fariin_chat_folders", Context.MODE_PRIVATE)

    private fun readCache(me: String): Cache? {
        val c = app ?: return null
        val s = prefs(c).getString(CACHE_PREFIX + me, null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            fun fl(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.getJSONObject(it) }.mapNotNull(ChatFolder::fromJson)
            Cache(fl(o.optJSONArray("folders")), o.optString("selected", ChatFolder.ALL.id), o.optBoolean("dirty"),
                fl(o.optJSONArray("base")), if (o.has("baseAt")) o.getDouble("baseAt") else null)
        }.getOrNull()
    }

    private fun writeCache() {
        val me = uid ?: return
        val c = app ?: return
        val o = JSONObject().apply {
            put("folders", JSONArray(folders.map { it.toJson() })); put("selected", selectedId); put("dirty", dirty)
            put("base", JSONArray(base.map { it.toJson() })); baseAt?.let { put("baseAt", it) }
        }
        prefs(c).edit().putString(CACHE_PREFIX + me, o.toString()).apply()
    }
}
