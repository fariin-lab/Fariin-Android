package com.fariin.app.data

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * THE APP'S SETTINGS FROM THE SERVER (owner 2026-11-13, flags step 3; iOS ConfigClient.swift).
 *
 * The callable `getAppConfig` resolves every flag for THIS account and replies `{values, hash, v}`,
 * `{notModified, v}` when our hash is still right, or `{notReady, v: 0}` before the server is seeded.
 *
 * Where a value comes from, first hit wins:
 *   1. live keys: the newest values for the signed-in account (fresh reply, or that account's cache);
 *      [nextLaunchKeys]: the launch snapshot, taken from the cache in [init];
 *   2. the compiled default in [K], the number this build shipped with.
 * [AppFeatures] puts `config/features` between the two for the old switches.
 *
 * When it asks: at sign-in and cold start, back in front when the last ask is over 2 h old, and when
 * `config/epoch.v` moves (one listener; 0 to 3 s random wait first). Nothing waits on it: an error or
 * notReady keeps the cache, and with no cache the compiled defaults stand.
 */
/** A ceiling from [AppConfig] was reached: [message] is the line to show the person. */
class LimitReached(message: String) : Exception(message)

object AppConfig {
    class IntKey(val name: String, val def: Int, val min: Int, val max: Int)
    class BoolKey(val name: String, val def: Boolean)

    /** The app limits (G-app-keys.md, mirror of flagRegistry.js). All apply live. */
    object K {
        // Chats. 4,096 as iOS (owner 2026-11-13 "make it same"; Android had 6,400). Only new input is capped.
        val messageMaxChars = IntKey("messageMaxChars", 4096, 1000, 6400)
        val messageBubbleChars = IntKey("messageBubbleChars", 2500, 500, 4096)
        val captionMaxChars = IntKey("captionMaxChars", 1024, 200, 4096)
        val pinnedMessagesPerChat = IntKey("pinnedMessagesPerChat", 3, 1, 10)
        val albumMaxItems = IntKey("albumMaxItems", 10, 1, 10)
        val mediaPerMessage = IntKey("mediaPerMessage", 32, 1, 32)
        val forwardChatsAtOnce = IntKey("forwardChatsAtOnce", 0, 0, 20)   // 0 = no limit (owner 2026-11-13)
        val forwardSelectionMax = IntKey("forwardSelectionMax", 32, 1, 100)
        val deleteForEveryoneHours = IntKey("deleteForEveryoneHours", 0, 0, 168)   // 0 = no limit
        val linkPreviews = BoolKey("linkPreviews", true)
        val linkPreviewImageSide = IntKey("linkPreviewImageSide", 800, 200, 1600)
        // Accounts
        val blockedUsersMax = IntKey("blockedUsersMax", 1000, 100, 10000)
        val bioChars = IntKey("bioChars", 140, 70, 500)
        val profilePhotoMB = IntKey("profilePhotoMB", 8, 1, 8)
        // Media (MB = 1024 x 1024 bytes)
        val fileUploadMB = IntKey("fileUploadMB", 25, 1, 100)
        val videoMessageMB = IntKey("videoMessageMB", 100, 1, 100)
        val videoExportTargetMB = IntKey("videoExportTargetMB", 62, 10, 95)
        val videoSdBytesPerSec = IntKey("videoSdBytesPerSec", 250_000, 100_000, 1_000_000)
        val videoHdBytesPerSec = IntKey("videoHdBytesPerSec", 500_000, 200_000, 2_000_000)
        val voiceNoteMinutes = IntKey("voiceNoteMinutes", 30, 1, 120)
        val photoMaxSideStandard = IntKey("photoMaxSideStandard", 1280, 640, 4096)
        val photoMaxSideHigh = IntKey("photoMaxSideHigh", 2560, 1280, 4096)
        val photoJpegQualityPct = IntKey("photoJpegQualityPct", 60, 30, 95)
        val photoMinSide = IntKey("photoMinSide", 640, 320, 1280)
        val autoDownloadMaxMB = IntKey("autoDownloadMaxMB", 200, 1, 1000)
        val autoDownloadPhotoMB = IntKey("autoDownloadPhotoMB", 8, 1, 50)
        val voiceAutoDownloadMB = IntKey("voiceAutoDownloadMB", 2, 1, 20)
        val voicePrefetchAhead = IntKey("voicePrefetchAhead", 3, 0, 10)
        // Stories. Captions 200 as iOS (owner 2026-11-13; Android had 700). Older captions stay.
        val storyExpiryHours = IntKey("storyExpiryHours", 24, 1, 48)
        val storyUploadMB = IntKey("storyUploadMB", 50, 5, 50)
        val storyVideoSeconds = IntKey("storyVideoSeconds", 90, 10, 180)
        val storyVideoPickSeconds = IntKey("storyVideoPickSeconds", 600, 60, 1800)
        val storyCaptionChars = IntKey("storyCaptionChars", 200, 50, 2048)
        // Groups and channels
        val groupAdminsMax = IntKey("groupAdminsMax", 0, 0, 50)   // 0 = no limit
        val groupNameChars = IntKey("groupNameChars", 100, 16, 255)
        val channelDescChars = IntKey("channelDescChars", 255, 50, 2000)
        // Calls
        val groupRingSeconds = IntKey("groupRingSeconds", 60, 15, 60)
    }

    /** The old switches that apply on the NEXT launch (all but groupCreate and groupStats). */
    val nextLaunchKeys: Set<String> = setOf(
        "glow", "insights", "profileLinks", "myProfileCard", "twoStep", "storyList", "pollRows",
        "stickerEdit", "chatBackup", "chatTransfer", "historyPull", "videoStream", "r2Media",
        "groups", "groupE2EE", "groupCalls", "callLinks",
    )

    // ---- Typed reads (any thread) ----

    fun int(k: IntKey): Int = clampInt(raw(k.name), k)
    fun bool(k: BoolKey): Boolean = (raw(k.name) as? Boolean) ?: k.def
    /** Megabytes (1024 x 1024) as bytes. */
    fun bytes(k: IntKey): Long = int(k).toLong() * 1024 * 1024
    /** An old switch as the server resolved it for this account; null = the server said nothing. */
    fun switchValue(key: String): Boolean? = raw(key) as? Boolean

    /** A number from the reply, rounded and clamped; a wrong type or no value gives the default. */
    internal fun clampInt(v: Any?, k: IntKey): Int {
        if (v is Boolean || v !is Number) return k.def
        val d = v.toDouble()
        if (!d.isFinite()) return k.def
        return Math.round(d).coerceIn(k.min.toLong(), k.max.toLong()).toInt()
    }

    // ---- State ----

    private val lock = Any()
    @Volatile private var launch: Map<String, Any> = emptyMap()
    @Volatile private var launchRead = false
    @Volatile private var live: Map<String, Any> = emptyMap()

    private var appCtx: Context? = null
    private var uid: String? = null
    private var hash: String? = null
    private var lastFetchAt = 0L
    private var knownEpoch = 0L
    private var seq = 0
    private var fetchJob: Job? = null
    private var epochReg: ListenerRegistration? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun raw(key: String): Any? {
        if (key in nextLaunchKeys) { launchRead = true; return launch[key] }
        return live[key]
    }

    // ---- Cache (per account) ----

    private const val FILE = "app_config"
    private fun prefs() = appCtx?.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private class Cached(val values: Map<String, Any>, val hash: String?)

    private fun readCache(uid: String): Cached? {
        val s = prefs()?.getString("v1.$uid", null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            Cached(clean(o.optJSONObject("values")?.let { j -> j.keys().asSequence().associateWith { j.get(it) } }.orEmpty()),
                o.optString("hash").takeIf { it.isNotEmpty() })
        }.getOrNull()
    }

    private fun writeCache(uid: String, values: Map<String, Any>, hash: String?) {
        val o = JSONObject().put("values", JSONObject(values)).put("hash", hash ?: "")
        prefs()?.edit()?.putString("v1.$uid", o.toString())?.apply()
    }

    /** Plain values only: bools, numbers, strings. */
    internal fun clean(raw: Map<*, *>): Map<String, Any> = raw.entries.mapNotNull { (k, v) ->
        if (k !is String) null else when (v) {
            is Boolean, is String -> k to v
            is Number -> k to v.toDouble()
            else -> null
        }
    }.toMap()

    // ---- Life cycle ----

    /** At app start, before any screen: the launch snapshot for the account signed in now. */
    fun init(c: Context) {
        appCtx = c.applicationContext
        val u = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        val cached = readCache(u) ?: return
        synchronized(lock) { launch = cached.values; live = cached.values }
    }

    /** With the account's other listeners: that account's cache goes live, one ask, the epoch listener. */
    fun start(me: String) {
        if (me.isEmpty()) return
        if (uid != me) {
            val c = readCache(me)
            synchronized(lock) {
                live = c?.values.orEmpty()
                if (launch.isEmpty() && !launchRead) launch = c?.values.orEmpty()
            }
            hash = c?.hash
            lastFetchAt = 0L
            knownEpoch = 0L
        }
        uid = me
        listen()
        fetch(null)
    }

    /** Sign-out: the next account never runs on this one's values. The cache stays, per account. */
    fun stop() {
        epochReg?.remove(); epochReg = null
        fetchJob?.cancel(); fetchJob = null
        seq++
        uid = null; hash = null; lastFetchAt = 0L; knownEpoch = 0L
        synchronized(lock) { live = emptyMap(); launch = emptyMap() }
    }

    /** Back in front: ask again when the last ask is over 2 hours old. */
    fun foreground() {
        if (uid == null) return
        if (System.currentTimeMillis() - lastFetchAt > 2 * 3600_000L) fetch(null)
    }

    private fun listen() {
        if (epochReg != null) return
        epochReg = FirebaseFirestore.getInstance().collection("config").document("epoch")
            .addSnapshotListener { snap, _ ->
                val v = (snap?.get("v") as? Number)?.toLong() ?: return@addSnapshotListener
                if (v <= knownEpoch) return@addSnapshotListener
                val first = knownEpoch == 0L
                knownEpoch = v
                // The first reading only learns the number; start() already asked.
                if (!first) fetch(v, waitFirst = true)
            }
    }

    /** This build's versionCode, as the server's per-build rules expect it. */
    private fun versionCode(): Int = runCatching {
        val c = appCtx ?: return 0
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(0)

    private fun fetch(epoch: Long?, waitFirst: Boolean = false, retried: Boolean = false) {
        val me = uid ?: return
        val mySeq = ++seq
        fetchJob?.cancel()
        fetchJob = scope.launch {
            if (waitFirst) delay((0..3000).random().toLong())
            val data = HashMap<String, Any>().apply {
                put("build", versionCode())
                put("platform", "android")
                hash?.let { put("have", it) }
                epoch?.let { put("epoch", it) }
            }
            val reply = runCatching {
                FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("getAppConfig")
                    .call(data).await().getData() as? Map<*, *>
            }.getOrNull() ?: return@launch
            if (mySeq != seq || uid != me) return@launch
            lastFetchAt = System.currentTimeMillis()
            val v = (reply["v"] as? Number)?.toLong() ?: 0L
            if (reply["notReady"] == true) return@launch
            if (epoch != null && v < epoch && !retried) {
                delay(1200); if (mySeq == seq) fetch(epoch, retried = true); return@launch
            }
            if (reply["notModified"] == true) return@launch
            val values = clean(reply["values"] as? Map<*, *> ?: return@launch)
            val h = reply["hash"] as? String
            hash = h
            synchronized(lock) { live = values }
            writeCache(me, values, h)
        }
    }
}
