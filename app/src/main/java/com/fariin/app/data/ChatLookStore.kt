package com.fariin.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * The phone's copy of the chat wallpaper and chat colour picks (iOS WallpaperStore + ChatColorStore,
 * UserDefaults there, one prefs file here, same keys). Local only, never uploaded. [version] is read
 * by the chat and the pages so a change redraws them at once.
 *
 * The photo library (iOS "every gallery photo ever applied is kept and reusable"): files in
 * filesDir/wallpapers/library/<id>.jpg, newest first, deduped by a hash of the encoded picture.
 */
object ChatLookStore {
    private const val FILE = "fariin_look"
    private const val LIBRARY = "wallpaper.library.v1"
    private const val HASHES = "wallpaper.libraryHashes.v1"
    private const val CUSTOM = "chatColor.customLibrary.v1"

    val version = mutableIntStateOf(0)
    /** The chat on screen, so the bubbles (ThreadScreen myFill) know whose colour to draw. */
    val openCid = mutableStateOf<String?>(null)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun reader(c: Context): (String) -> String? { val p = prefs(c); return { p.getString(it, null) } }
    private fun bump() { version.intValue++ }

    // Wallpaper

    fun wallpaper(c: Context, cid: String): ChatWallpaper = ChatLookRules.wallpaper(cid, reader(c), groupWallpaper(c, cid))
    fun defaultWallpaper(c: Context): ChatWallpaper = ChatLookRules.defaultWallpaper(reader(c))

    // Shared group wallpaper (iOS WallpaperStore "Shared group wallpaper"): a group's `wallpaper`
    // field is everyone's and beats this phone's own pick. Kept here as last read, so a chat opens
    // with it at once; a photo ("u:<link>") is fetched once into the photo folder (not the person's
    // own library list) and drawn from there. 1:1 chats never read any of this.

    private val groupPending = HashMap<String, ChatWallpaper>()
    private val groupLastShown = HashMap<String, ChatWallpaper>()
    private val groupFetching = HashSet<String>()

    /** The group's wallpaper as this phone can draw it now, or null for this phone's own pick. */
    fun groupWallpaper(c: Context, cid: String): ChatWallpaper? {
        synchronized(groupPending) { groupPending[cid] }?.let { return it }
        val raw = prefs(c).getString(ChatLookRules.groupKey(cid), null) ?: return null
        val last = synchronized(groupPending) { groupLastShown[cid] }
        val w = ChatLookRules.groupWallpaper(raw, { libraryFile(c, it).exists() }, last)
        if (w is ChatWallpaper.Photo) synchronized(groupPending) { groupLastShown[cid] = w }
        else ChatLookRules.photoLink(raw)?.let { fetchGroupPhoto(c, it) }
        return w
    }

    /** The group's raw stored value as last read ("none", "g:..", "u:<link>"), null when it has none. */
    fun groupStored(c: Context, cid: String): String? = prefs(c).getString(ChatLookRules.groupKey(cid), null)

    /** iOS syncGroupWallpapers: the group's `wallpaper` field as the server has it now (null = never set). */
    fun syncGroupWallpaper(c: Context, cid: String, raw: String?) {
        if (groupStored(c, cid) == raw) return
        val e = prefs(c).edit()
        if (raw != null) e.putString(ChatLookRules.groupKey(cid), raw) else e.remove(ChatLookRules.groupKey(cid))
        e.apply(); bump()
    }

    /** Apply in a group: shown at once here while it uploads and saves; null drops it again. */
    fun setGroupPending(cid: String, w: ChatWallpaper?) {
        synchronized(groupPending) { if (w != null) groupPending[cid] = w else groupPending.remove(cid) }
        bump()
    }

    /** The picture this phone sent for a group, kept under the link's id so it is not fetched back. */
    fun keepGroupPhoto(c: Context, link: String, fromId: String) {
        runCatching { libraryFile(c, fromId).copyTo(libraryFile(c, ChatLookRules.photoId(link)), overwrite = true) }
    }

    /** Fetch a group's photo once. Only Firebase Storage links (the local test backend's too). */
    private fun fetchGroupPhoto(c: Context, link: String) {
        val u = runCatching { java.net.URL(link) }.getOrNull() ?: return
        val ok = (u.protocol == "https" && u.host.equals("firebasestorage.googleapis.com", true)) ||
            com.fariin.app.FariinApp.usingLocalBackend
        if (!ok || !synchronized(groupPending) { groupFetching.add(link) }) return
        val app = c.applicationContext
        Thread({
            val file = libraryFile(app, ChatLookRules.photoId(link))
            val saved = runCatching {
                val bytes = (u.openConnection() as java.net.HttpURLConnection).run {
                    connectTimeout = 20_000; readTimeout = 60_000
                    try { inputStream.use { it.readBytes() } } finally { disconnect() }
                }
                if (bytes.size > 12 * 1024 * 1024 || BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) false
                else { file.writeBytes(bytes); true }
            }.getOrDefault(false)
            synchronized(groupPending) { groupFetching.remove(link) }
            if (saved) android.os.Handler(android.os.Looper.getMainLooper()).post { bump() }
        }, "group-wallpaper").start()
    }

    /** iOS applyToAllChats: the Settings wallpaper; a chat's own pick beats it. */
    fun setDefaultWallpaper(c: Context, w: ChatWallpaper) {
        prefs(c).edit().putString(ChatLookRules.WALL_DEFAULT, w.stored).apply(); bump()
    }

    /** iOS set(_:for:): this chat's own wallpaper (None = plain, which beats the Settings one). */
    fun setWallpaper(c: Context, cid: String, w: ChatWallpaper) {
        prefs(c).edit().putString(ChatLookRules.wallKey(cid), w.stored).apply(); bump()
    }

    /** Does this chat have its own wallpaper, or follow the Settings one (iOS hasOverride)? */
    fun hasOwnWallpaper(c: Context, cid: String): Boolean = prefs(c).contains(ChatLookRules.wallKey(cid))

    /** iOS applyToAllChats(alsoFor:): the Settings wallpaper becomes [w] and [cid] drops its own. */
    fun applyToAllChats(c: Context, w: ChatWallpaper, alsoFor: String) {
        prefs(c).edit().putString(ChatLookRules.WALL_DEFAULT, w.stored).remove(ChatLookRules.wallKey(alsoFor)).apply(); bump()
    }

    /** Every chat's own wallpaper back to the Settings one, and the Settings one to [w]. */
    fun resetAllWallpapers(c: Context, w: ChatWallpaper = ChatWallpaper.None) {
        val p = prefs(c); val e = p.edit()
        // The groups' shared copies are not this phone's picks (iOS keeps "wallpaper.group" too).
        p.all.keys.filter { it.startsWith("wallpaper.") && !it.startsWith("wallpaper.library") && !it.startsWith("wallpaper.group.") &&
            it != ChatLookRules.WALL_DEFAULT }
            .forEach { e.remove(it) }
        e.putString(ChatLookRules.WALL_DEFAULT, w.stored).apply(); bump()
    }

    // Chat colour

    fun color(c: Context, cid: String): ChatColorSpec? = ChatLookRules.color(cid, reader(c), groupWallpaper(c, cid))
    fun globalChosen(c: Context): ChatColorSpec? = ChatLookRules.globalChosen(reader(c))
    fun globalColor(c: Context): ChatColorSpec? = ChatLookRules.globalColor(reader(c))
    fun globalAuto(c: Context): ChatColorSpec? = ChatLookRules.globalAuto(reader(c))

    /** iOS ChatColorStore.applyToAllChats: the Settings colour, null = Auto. */
    fun setDefaultColor(c: Context, spec: ChatColorSpec?) {
        val e = prefs(c).edit()
        if (spec != null) e.putString(ChatLookRules.COLOR_DEFAULT, spec.stored) else e.remove(ChatLookRules.COLOR_DEFAULT)
        e.apply(); bump()
    }

    /** iOS ChatColorStore.applyToAllChats(nil): every chat back to Auto, and the Settings colour too. */
    fun resetAllColors(c: Context) {
        val p = prefs(c); val e = p.edit()
        p.all.keys.filter { it.startsWith("chatColor.") }.forEach { e.remove(it) }
        e.apply(); bump()
    }

    /** This chat's own colour pick; null = Auto (iOS ChatColorStore.chosen(for:)). */
    fun ownColor(c: Context, cid: String): ChatColorSpec? =
        prefs(c).getString(ChatLookRules.colorKey(cid), null).let { if (it == "__none__") null else ChatColorSpec.parse(it) }

    /** iOS ChatColorStore.set(_:for:): null clears the chat's own pick. */
    fun setColor(c: Context, cid: String, spec: ChatColorSpec?) {
        val e = prefs(c).edit()
        if (spec != null) e.putString(ChatLookRules.colorKey(cid), spec.stored) else e.remove(ChatLookRules.colorKey(cid))
        e.apply(); bump()
    }

    fun customColors(c: Context): List<ChatColorSpec> =
        prefs(c).getString(CUSTOM, null).orEmpty().split("\n").mapNotNull { ChatColorSpec.parse(it) }

    /** Saved permanently, newest first, no duplicates, presets never enter it. */
    fun addCustom(c: Context, spec: ChatColorSpec) {
        val list = customColors(c)
        if (spec in ChatColors.presets || spec in list) return
        prefs(c).edit().putString(CUSTOM, (listOf(spec) + list).joinToString("\n") { it.stored }).apply(); bump()
    }

    fun removeCustom(c: Context, spec: ChatColorSpec) {
        prefs(c).edit().putString(CUSTOM, customColors(c).filter { it != spec }.joinToString("\n") { it.stored }).apply(); bump()
    }

    // Photo library

    private fun dir(c: Context) = File(c.filesDir, "wallpapers/library").apply { mkdirs() }
    fun libraryFile(c: Context, id: String) = File(dir(c), "$id.jpg")

    fun libraryIds(c: Context): List<String> =
        prefs(c).getString(LIBRARY, null).orEmpty().split("\n").filter { it.isNotEmpty() }

    private val images = HashMap<String, Bitmap>()

    fun libraryImage(c: Context, id: String): Bitmap? = synchronized(images) {
        images[id] ?: libraryFile(c, id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }?.also { images[id] = it }
    }

    /** Adds a picture (longest side 2560, JPEG 0.9) and returns its id; the same picture again returns the old id. */
    fun addToLibrary(c: Context, bmp: Bitmap): String? {
        val scaled = fit(bmp, 2560)
        val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val p = prefs(c)
        val hashes = p.getString(HASHES, null).orEmpty().split("\n").filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val ids = libraryIds(c)
        hashes.entries.firstOrNull { it.value == hash && it.key in ids }?.let { return it.key }
        val id = java.util.UUID.randomUUID().toString()
        return try {
            libraryFile(c, id).writeBytes(bytes)
            synchronized(images) { images[id] = scaled }
            p.edit().putString(LIBRARY, (listOf(id) + ids).joinToString("\n"))
                .putString(HASHES, (hashes + (id to hash)).entries.joinToString("\n") { "${it.key}=${it.value}" }).apply()
            bump(); id
        } catch (_: Exception) { null }
    }

    /** Long-press Delete: the file goes; a chat that used it falls back to the plain background. */
    fun deleteFromLibrary(c: Context, id: String) {
        libraryFile(c, id).delete()
        synchronized(images) { images.remove(id) }
        val p = prefs(c)
        val hashes = p.getString(HASHES, null).orEmpty().split("\n").filter { '=' in it && it.substringBefore('=') != id }
        p.edit().putString(LIBRARY, libraryIds(c).filter { it != id }.joinToString("\n"))
            .putString(HASHES, hashes.joinToString("\n")).apply()
        bump()
    }

    private fun fit(b: Bitmap, max: Int): Bitmap {
        val long = maxOf(b.width, b.height)
        if (long <= max) return b
        val s = max.toFloat() / long
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    /** Sign-out (iOS WallpaperStore.reset / ChatColorStore.reset): nothing carries into the next account. */
    fun wipe(c: Context) {
        prefs(c).edit().clear().apply()
        File(c.filesDir, "wallpapers").deleteRecursively()
        synchronized(images) { images.clear() }
        synchronized(groupPending) { groupPending.clear(); groupLastShown.clear() }
        bump()
    }
}
