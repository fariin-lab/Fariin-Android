package com.fariin.app.push

import android.content.Context
import android.net.Uri
import com.fariin.app.data.Conversation
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * What the app hands the notification code (iOS Shared/NotifShared.swift + Kulan/Services/NotifShare.swift):
 * the signed-in uid, per chat the name and photos this phone shows, and copies of the profile
 * photos the app was allowed to open. On Android the push service runs in the app's own process,
 * so the crypto key needs no shared copy (it is read straight from the Keystore store).
 *
 * Nothing here is secret (names and photo links the app already holds); all of it goes on sign-out.
 */
object NotifStore {
    private const val PREFS = "fariin_notif"
    private const val UID = "notif.uid.v1"
    private const val CHATS = "notif.chats.v1"
    private const val AVATARS = "NotifAvatars"
    /** Group member photos kept per group (iOS NotifShare caps the same map). */
    private const val MEMBER_CAP = 64
    private const val AVATAR_MAX = 2_000_000

    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var lastSaved: String? = null

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun uid(c: Context): String = prefs(c).getString(UID, null) ?: ""

    /** The chat list changed: save what a notification needs from it. */
    fun saveChats(c: Context, me: String, convs: List<Conversation>) {
        val root = JSONObject()
        for (conv in convs) {
            val o = JSONObject()
            if (conv.isGroup) {
                o.put("g", "1")
                o.put("n", conv.displayName(me))
                conv.displayPhoto(me)?.let { o.put("p", it) }
                val m = JSONObject()
                conv.photos.entries.filter { it.key != me && it.value.isNotEmpty() }.take(MEMBER_CAP)
                    .forEach { m.put(it.key, it.value) }
                if (m.length() > 0) o.put("m", m)
            } else {
                o.put("n", conv.name(me))
                conv.photoUrl(me)?.let { o.put("p", it) }
            }
            root.put(conv.id, o)
        }
        val json = root.toString()
        if (json == lastSaved) return
        lastSaved = json
        prefs(c).edit().putString(UID, me).putString(CHATS, json).apply()
    }

    fun chat(c: Context, cid: String): ChatInfo? {
        val raw = prefs(c).getString(CHATS, null) ?: return null
        val o = try { JSONObject(raw).optJSONObject(cid) } catch (_: Exception) { null } ?: return null
        val m = o.optJSONObject("m")
        val members = HashMap<String, String>()
        m?.keys()?.forEach { k -> m.optString(k).takeIf { it.isNotEmpty() }?.let { members[k] = it } }
        return ChatInfo(
            isGroup = o.optString("g") == "1",
            name = o.optString("n").takeIf { it.isNotEmpty() },
            photo = o.optString("p").takeIf { it.isNotEmpty() },
            members = members,
        )
    }

    // ------------------------------------------------------------------ avatars

    /** The same file name for a URL everywhere: a hash of the URL (NotifShared.avatarFile). */
    fun avatarName(url: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return h.take(16).joinToString("") { "%02x".format(it) } + ".img"
    }

    fun avatarFile(c: Context, url: String): File = File(File(c.applicationContext.filesDir, AVATARS), avatarName(url))

    /** NotifShared.saveAvatar: a copy of each profile photo the app opened. A new photo or audience
     *  is a new `v`, so a new name; a file that is there is never rewritten. */
    fun saveAvatar(c: Context, url: String, data: ByteArray) {
        if (data.isEmpty() || data.size > AVATAR_MAX) return
        val f = avatarFile(c, url)
        if (f.exists()) return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        try { tmp.writeBytes(data); tmp.renameTo(f) } catch (_: Exception) { tmp.delete() }
    }

    /** Off the caller's thread, profile photos only (`fariin-photo://`), as iOS saveAvatarSoon. */
    fun saveAvatarSoon(c: Context, url: String, data: ByteArray) {
        if (!url.startsWith("fariin-photo://")) return
        val app = c.applicationContext
        io.execute { saveAvatar(app, url, data) }
    }

    fun cachedAvatar(c: Context, url: String): ByteArray? {
        val f = avatarFile(c, url)
        return try { if (f.exists()) f.readBytes().takeIf { it.isNotEmpty() } else null } catch (_: Exception) { null }
    }

    /** Sign-out and account deletion: the uid, the chat details and the saved faces. */
    fun clearAccount(c: Context) {
        lastSaved = null
        prefs(c).edit().remove(UID).remove(CHATS).apply()
        File(c.applicationContext.filesDir, AVATARS).deleteRecursively()
    }

    /** `fariin-photo://profiles/x.jpg?v=1` to its Storage path (same rule as FariinPhotoFetcher). */
    fun storagePath(url: String): String? {
        val u = try { Uri.parse(url) } catch (_: Exception) { return null }
        if (u.scheme != "fariin-photo") return null
        val path = (u.authority ?: return null) + (u.path ?: "")
        if (!path.startsWith("profiles/") || path.contains("..")) return null
        return path
    }
}
