package com.fariin.app.data

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

/**
 * FARIIN PLUS, what the server says for this account (iOS PlusStore, contract v1).
 *
 * The callable `plusState` (me-central1) answers `{visible: false}` and nothing else for every
 * account that may not see Plus yet. Only while [visible] is true does anything of Plus exist in the
 * app: the Settings row, the Plus page, the badge beside names. Nothing here logs, and nothing is kept
 * on disk, so an account that is not allowed leaves no trace of it.
 *
 * Asks at sign-in, on return to the app, and every 10 minutes while the app is in front (skipped when
 * the last good answer is under 9 minutes old). Signing out or switching account drops everything.
 *
 * Badges: `plusBadges({uids})` answers which of up to 100 people have Plus. A row asks through
 * [showsBadge] while it draws; new uids are gathered for 300 ms and sent in one batch.
 */
object PlusStore {
    enum class State { OFF, LOCKED, ON }
    data class Subscription(val source: String, val productId: String?, val status: String?, val autoRenew: Boolean?, val untilMs: Long?)

    /** Feature ids in the Plus page's order. */
    val FEATURE_IDS = listOf("badge", "storyExpiry", "appIcons", "morePins", "profileVisitors", "animatedAvatar",
        "storyRewatch", "bubbleStyles", "sharedWallpaper", "sendEffects", "chatKey", "gifts", "chatFolders", "tagSearch")

    const val REGION = "me-central1"
    private val fns get() = FirebaseFunctions.getInstance(REGION)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var visible by mutableStateOf(false); private set
    /** The server said yes AND [untilMs] (when set) is still ahead; worked out here, so Plus that runs out offline stops on time. */
    var isPlus by mutableStateOf(false); private set
    var untilMs by mutableStateOf<Long?>(null); private set
    var features by mutableStateOf<Map<String, State>>(emptyMap()); private set
    var pinnedChats by mutableStateOf<Int?>(null); private set
    /** The plusConfig pin numbers without and with Plus (iOS pinnedChatsFree / pinnedChatsPlus; null
     *  from an older server). A locked account's [pinnedChats] is the free number, so the Plus number
     *  for the limit sheet comes from here. */
    var pinnedChatsFree by mutableStateOf<Int?>(null); private set
    var pinnedChatsPlus by mutableStateOf<Int?>(null); private set
    /** How many chats Plus lets this account pin, never below the free limit; null when not known
     *  (iOS plusPinLimit: an older server and the feature not on for this account). */
    val plusPinLimit: Int? get() {
        val n = pinnedChatsPlus ?: (if (state("morePins") == State.ON) pinnedChats else null)
        return n?.let { maxOf(it, AppLimits.pinnedChatsMax) }
    }
    var giftMonths by mutableStateOf<List<Int>>(emptyList()); private set
    var purchasesOn by mutableStateOf(false); private set
    var subscription by mutableStateOf<Subscription?>(null); private set
    /** Uids known to have Plus, from `plusBadges`. */
    var badges by mutableStateOf<Set<String>>(emptySet()); private set

    private var uid: String? = null
    private var accountSeq = 0
    private var requestSeq = 0
    private var appliedSeq = 0
    private var lastFetchAt = 0L
    private var lastAskAt = 0L
    private var serverPlus = false
    private var expiryJob: Job? = null
    private var ticker: Job? = null
    @Volatile var inFront = true

    private val asked = HashSet<String>()
    private val pending = ArrayList<String>()
    private var batchJob: Job? = null
    private var badgesAskedAt = 0L

    // ---- Account ----

    /** With the account's other listeners. A different account starts from nothing. */
    fun start(uid: String) {
        if (uid.isEmpty()) { stop(); return }
        val same = this.uid == uid
        if (!same) clear()
        this.uid = uid
        startTicker()
        if (same && System.currentTimeMillis() - lastAskAt < 60_000) return
        fetch()
    }

    /** Sign-out or account switch: nothing of the last account's Plus outlives it. */
    fun stop() {
        uid = null
        clear()
        ticker?.cancel(); ticker = null
    }

    /** The app came back to the front. */
    fun onForeground() {
        inFront = true
        if (uid == null) return
        refreshPlus()
        fetch()
    }

    fun onBackground() { inFront = false }

    /** Ask the server again now (after a purchase or a restore). */
    fun refresh() { if (uid != null) fetch() }

    private fun clear() {
        PlusBubbles.clear()
        accountSeq++
        lastFetchAt = 0L
        lastAskAt = 0L
        hide()
    }

    private fun hide() {
        expiryJob?.cancel(); expiryJob = null
        serverPlus = false
        if (visible) visible = false
        if (isPlus) isPlus = false
        if (untilMs != null) untilMs = null
        if (features.isNotEmpty()) features = emptyMap()
        if (pinnedChats != null) pinnedChats = null
        if (pinnedChatsFree != null) pinnedChatsFree = null
        if (pinnedChatsPlus != null) pinnedChatsPlus = null
        if (giftMonths.isNotEmpty()) giftMonths = emptyList()
        if (purchasesOn) purchasesOn = false
        if (subscription != null) subscription = null
        clearBadges()
    }

    private fun clearBadges() {
        batchJob?.cancel(); batchJob = null
        pending.clear(); asked.clear()
        if (badges.isNotEmpty()) badges = emptySet()
        badgesAskedAt = 0L
    }

    // ---- Reading ----

    fun state(feature: String): State = if (!visible) State.OFF else features[feature] ?: State.OFF

    /** True when the Plus badge belongs beside [uid]'s name. Safe while drawing: an unknown uid is queued. */
    fun showsBadge(uid: String): Boolean {
        if (!visible || uid.isEmpty()) return false
        val b = features["badge"]
        if (b == null || b == State.OFF) return false
        // The server's batch never holds my own uid, so use my own Plus state.
        if (uid == com.fariin.app.Fb.auth.currentUser?.uid) return isPlus
        if (uid !in asked) want(uid)
        return uid in badges
    }

    val giftsShown: Boolean get() = visible && state("gifts") != State.OFF && giftMonths.isNotEmpty()

    /** The Firebase error's `details.code`, when the server gave one. */
    fun detailsCode(e: Throwable): String? = ((e as? FirebaseFunctionsException)?.details as? Map<*, *>)?.get("code") as? String

    /** Forget [uid]'s badge answer so the next draw asks again (after a gift). */
    fun forgetBadge(uid: String) { asked.remove(uid) }

    // ---- plusState ----

    private val hidingCodes = setOf(
        FirebaseFunctionsException.Code.PERMISSION_DENIED, FirebaseFunctionsException.Code.UNAUTHENTICATED,
        FirebaseFunctionsException.Code.NOT_FOUND, FirebaseFunctionsException.Code.UNIMPLEMENTED,
    )
    private const val STALE_AFTER_MS = 30 * 60_000L

    private fun startTicker() {
        if (ticker != null) return
        ticker = scope.launch {
            while (true) {
                delay(600_000)
                if (uid == null || !inFront) continue
                if (System.currentTimeMillis() - lastFetchAt < 540_000) continue
                fetch()
            }
        }
    }

    private fun fetch() {
        val uid = uid ?: return
        val mine = ++requestSeq
        val account = accountSeq
        lastAskAt = System.currentTimeMillis()
        scope.launch {
            var reply: Map<*, *>? = null
            var notForUs = false
            try {
                reply = fns.getHttpsCallable("plusState").call(emptyMap<String, Any>()).await().getData() as? Map<*, *>
            } catch (e: FirebaseFunctionsException) {
                if (e.code in hidingCodes) notForUs = true
            } catch (_: Exception) {}
            if (account != accountSeq || this@PlusStore.uid != uid) return@launch
            if (mine <= appliedSeq) return@launch
            when {
                reply != null -> { appliedSeq = mine; lastFetchAt = System.currentTimeMillis(); apply(reply) }
                notForUs -> { appliedSeq = mine; lastFetchAt = System.currentTimeMillis(); hide() }
                lastFetchAt > 0 && System.currentTimeMillis() - lastFetchAt < STALE_AFTER_MS -> {}
                else -> hide()
            }
            if (reply != null || notForUs) onRealAnswer?.invoke()
        }
    }

    /** Called after each real answer (yes, or "not for you"): the app icon check hangs here. */
    var onRealAnswer: (() -> Unit)? = null

    private fun num(v: Any?): Long? = (v as? Number)?.toLong()

    private fun apply(reply: Map<*, *>) {
        if (reply["visible"] != true) { hide(); return }
        val feats = HashMap<String, State>()
        (reply["features"] as? Map<*, *>)?.forEach { (k, v) ->
            val st = when (v) { "off" -> State.OFF; "locked" -> State.LOCKED; "on" -> State.ON; else -> null }
            if (k is String && st != null) feats[k] = st
        }
        val limits = reply["limits"] as? Map<*, *>
        val pins = num(limits?.get("pinnedChats"))?.toInt()
        val pinsFree = num(limits?.get("pinnedChatsFree"))?.toInt()
        val pinsPlus = num(limits?.get("pinnedChatsPlus"))?.toInt()
        val months = ((reply["giftMonths"] as? List<*>) ?: emptyList<Any>()).mapNotNull { num(it)?.toInt() }
            .filter { it in 1..36 }.distinct().sorted()
        val sub = (reply["sub"] as? Map<*, *>)?.let { s ->
            (s["source"] as? String)?.let { Subscription(it, s["productId"] as? String, s["status"] as? String, s["autoRenew"] as? Boolean, num(s["until"])) }
        }
        if (subscription != sub) subscription = sub
        val buy = reply["purchases"] == "on"
        if (purchasesOn != buy) purchasesOn = buy
        serverPlus = reply["isPlus"] == true
        if (giftMonths != months) giftMonths = months
        val u = num(reply["until"])
        if (untilMs != u) untilMs = u
        refreshPlus()
        if (features != feats) features = feats
        if (pinnedChats != pins) pinnedChats = pins
        if (pinnedChatsFree != pinsFree) pinnedChatsFree = pinsFree
        if (pinnedChatsPlus != pinsPlus) pinnedChatsPlus = pinsPlus
        if (!visible) visible = true
        val b = feats["badge"]
        if (b == null || b == State.OFF) clearBadges()
        else if (System.currentTimeMillis() - badgesAskedAt > STALE_AFTER_MS) {
            batchJob?.cancel(); batchJob = null
            pending.clear(); asked.clear()
            badgesAskedAt = System.currentTimeMillis()
        }
    }

    private fun refreshPlus() {
        expiryJob?.cancel(); expiryJob = null
        var now = serverPlus
        val u = untilMs
        if (now && u != null) {
            val left = u - System.currentTimeMillis()
            if (left <= 0) now = false
            else expiryJob = scope.launch { delay(minOf(left, 30L * 86_400_000) + 500); refreshPlus() }
        }
        if (isPlus != now) isPlus = now
    }

    // ---- plusBadges ----

    private fun want(u: String) {
        asked.add(u)
        pending.add(u)
        if (batchJob != null) return
        batchJob = scope.launch { delay(300); batchJob = null; sendPending() }
    }

    private fun sendPending() {
        val me = uid
        if (!visible || me == null || pending.isEmpty()) { pending.clear(); return }
        val all = pending.toList()
        pending.clear()
        for (chunk in all.chunked(100)) scope.launch {
            val list = try {
                (fns.getHttpsCallable("plusBadges").call(mapOf("uids" to chunk)).await().getData() as? Map<*, *>)?.get("plus") as? List<*>
            } catch (_: Exception) { null }
            if (uid != me || !visible) return@launch
            if (list == null) { chunk.forEach { asked.remove(it) }; return@launch }
            val yes = list.filterIsInstance<String>().toSet()
            val next = badges.toMutableSet()
            for (x in chunk) if (x in yes) next.add(x) else next.remove(x)
            if (next != badges) badges = next
        }
    }
}
