package com.fariin.app.ui.debug

import com.fariin.app.data.Conversation
import com.fariin.app.data.Message
import com.fariin.app.data.PeopleApi
import com.fariin.app.data.Person
import com.fariin.app.ui.thread.ThreadActions
import com.fariin.app.ui.thread.ThreadUi
import kotlinx.coroutines.delay

/**
 * Debug demo only: the people pages with made-up people. Nothing here touches the network;
 * every "write" changes memory and returns. The demo key for Sahra is 123456.
 */
object DemoPeople : PeopleApi {
    override val me = DemoChats.ME
    private var myName = "Abdi"
    private var myHandle = "abdi_demo"
    private val blocked = HashSet<String>()
    private val everyone = listOf(
        Person("amina", "Amina Yusuf", "amina", about = "Nairobi, Mombasa, home."),
        Person("omar", "Omar", "omar_h"),
        Person("hodan", "Hodan", "hodan"),
        Person("ali", "Ali", "ali_m"),
        Person("sahra", "Sahra", "sahra", privacy = mapOf("messages" to "contacts")),
        Person("farah", "Farah", "farah"),
        Person("ilhan", "Ilhan Warsame", "ilhan_w"),
        Person("mustafe", "Mustafe", "mustafe"),
    )

    override suspend fun search(query: String): List<Person> {
        delay(300)
        val q = query.trim().removePrefix("@").lowercase()
        return everyone.filter { it.handle.startsWith(q) || it.name.lowercase().contains(q) }
    }
    override suspend fun profile(uid: String) = if (uid == me) Person(me, myName, myHandle) else everyone.firstOrNull { it.uid == uid }
    override suspend fun chatKeyExists(uid: String) = uid == "sahra" || uid == "ilhan"
    override suspend fun verifyChatKey(uid: String, pin: String): Result<String> {
        delay(400)
        return if (pin == "123456") Result.success("d4") else Result.failure(Exception("Unable to verify Chat Key."))
    }
    override suspend fun openChat(p: Person) = DemoChats.chats().firstOrNull { !it.isGroup && it.users.contains(p.uid) }?.id ?: "d1"
    override suspend fun acceptRequest(cid: String) = true
    override suspend fun declineRequest(cid: String) = true
    override suspend fun isBlocked(uid: String) = uid in blocked
    override suspend fun setBlocked(uid: String, on: Boolean): Boolean { if (on) blocked.add(uid) else blocked.remove(uid); return true }
    override suspend fun setMuted(cid: String, on: Boolean) = true
    override suspend fun report(uid: String, cid: String, reason: String) = true
    override suspend fun safetyNumber(uid: String) = "376024918350127448190365518072934106628451993072"
    override suspend fun checkUsername(h: String): Pair<Boolean, String?> {
        delay(200)
        val taken = h.lowercase() in setOf("amina", "omar_h", "hodan", "fariin", "abdirahman")
        return !taken to if (taken) "taken" else null
    }
    override suspend fun claimUsername(h: String) { myHandle = h }
    override suspend fun saveName(name: String) { myName = name.trim() }
    override suspend fun uploadPhoto(jpegOrAny: ByteArray) {}
    override suspend fun createGroup(title: String, members: List<String>, photo: ByteArray?) = "d3"
    override suspend fun addMembers(cid: String, uids: List<String>): String? = null
    override suspend fun leaveGroup(cid: String) {}
    override suspend fun renameGroup(cid: String, title: String) {}
    override suspend fun setGroupPhoto(cid: String, photo: ByteArray) {}
    override suspend fun setGroupDescription(cid: String, text: String) {}
    override suspend fun removeGroupPhoto(cid: String) {}

    /** A request from someone new, for the Message Requests page. */
    fun requests(nowMs: Long = System.currentTimeMillis()) = listOf(
        Conversation(id = "demo-me_mustafe", users = listOf(me, "mustafe"), names = mapOf("mustafe" to "Mustafe"),
            lastMessageCipher = "x", lastSender = "mustafe", startedBy = "mustafe", accepted = false,
            updatedAtMillis = nowMs - 5 * 60_000.0),
    )

    /** The demo group document for Group Info. */
    fun groupDoc(cid: String): Map<String, Any?> {
        val c = DemoChats.chats().firstOrNull { it.id == cid } ?: return emptyMap()
        return mapOf("users" to c.users, "admins" to listOf(me), "createdBy" to me, "title" to c.title,
            "names" to c.names + (me to myName), "photos" to c.photos)
    }

    /** Reactions on the made-up messages and two unread, so the chips and the divider show. */
    fun decorate(u: ThreadUi): ThreadUi {
        val theirs = u.messages.filter { it.authorId != me && it.mediaKind == null && !it.isSystem }
        val mine = u.messages.filter { it.authorId == me && it.mediaKind == null }
        val r = HashMap<String, Map<String, String>>()
        theirs.getOrNull(0)?.let { r[it.id] = mapOf(me to "❤️") }
        mine.getOrNull(0)?.let { m -> r[m.id] = u.members.ifEmpty { listOf("amina", "hodan", "ali") }.filter { it != me }.associateWith { "😂" } }
        theirs.getOrNull(1)?.let { r[it.id] = mapOf("hodan" to "👍", "ali" to "👍", me to "🙏", "amina" to "👍", "omar" to "👍") }
        return u.copy(messages = u.messages.map { m -> r[m.id]?.let { m.copy(reactions = it) } ?: m },
            unreadAtOpen = 2, members = DemoChats.chats().firstOrNull { it.id == u.cid }?.users ?: emptyList(),
            memberReads = mapOf("hodan" to System.currentTimeMillis().toDouble()), photos = u.photos)
    }

    /** The made-up chat's actions: react, reply, edit and delete change the list in memory. */
    fun threadActions(get: () -> ThreadUi, set: (ThreadUi) -> Unit, onTitle: () -> Unit): ThreadActions {
        fun change(id: String, f: (Message) -> Message) { val u = get(); set(u.copy(messages = u.messages.map { if (it.id == id) f(it) else it })) }
        return ThreadActions(
            onReact = { m, e -> change(m.id) { it.copy(reactions = if (e == null) it.reactions - me else it.reactions + (me to e)) } },
            onSendReply = { t, r ->
                val now = System.currentTimeMillis().toDouble()
                val u = get()
                set(u.copy(messages = u.messages + Message(id = "local-$now", authorId = me, text = t.trim(), createdAtMillis = now,
                    clientTs = now, replyTo = Message.ReplyRef(r.id, r.authorId, r.bodyText))))
            },
            onEdit = { m, t, _ -> change(m.id) { it.copy(text = t.trim(), edited = true) } },
            onDeleteForEveryone = { m -> change(m.id) { it.copy(deleted = true, text = "", reactions = emptyMap(), type = "text", imageUrl = null) } },
            onDeleteForMe = { m -> val u = get(); set(u.copy(messages = u.messages.filter { it.id != m.id })) },
            onTitleClick = onTitle,
        )
    }
}
