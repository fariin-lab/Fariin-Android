package com.fariin.app.call

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The group call stage's pure parts, ported from iOS Views/GroupCall: the grid maths
 * (GroupCallLayoutEngine), the settled speaker (GroupCallSpeakerTracker), the shared numbers
 * (GroupCallMetrics) and the words the screens say (GroupCallWords, the lobby's whoIsHere, the ring
 * screen's names). No Android types, so the unit tests run them as they are.
 */
object GroupCallMetrics {
    const val INSET = 6f
    const val SPACING = 6f
    const val TILE_CORNER = 14f
    const val STRIP_TILE = 96f
    const val STRIP_SPACING = 4f
    const val STRIP_LEADING = 16f
    const val STRIP_INSET = 12f
    const val SPEAKING_BORDER = 2f
    const val SPEAKER_HOLD_MS = 1500L
    /** The self card: 90 x 160 (9:16), 16 from the trailing edge. */
    const val SELF_W = 90f
    const val SELF_H = 160f
    const val SELF_TRAILING = 16f
    /** The strip's own height: its tiles, its 6 top inset and 12 under it. */
    val STRIP_HEIGHT get() = STRIP_TILE + INSET + STRIP_INSET

    fun maxColumns(width: Float) = if (width > 1080) 4 else if (width > 768) 3 else 2
    fun maxRows(height: Float) = if (height > 1024) 4 else 3
}

/** A tile's frame in the stage, in dp, from the stage's top left. */
data class TileFrame(val x: Float, val y: Float, val w: Float, val h: Float)

data class CallGridLayout(val columns: Int, val rows: Int, val frames: Map<String, TileFrame>, val overflow: List<String>)

/**
 * iOS GroupCallLayoutEngine: try every column count up to the cap, keep the ones whose rows fit,
 * pick the most square tiles (log scale), near-ties to more rows; a short last row keeps the full
 * rows' tile width and sits centred. Past the cap the rest are `overflow` (the strip).
 */
object GroupCallLayout {
    private const val ORPHAN_PENALTY = 0.5f

    fun grid(ids: List<String>, width: Float, height: Float): CallGridLayout {
        val inset = GroupCallMetrics.INSET
        val spacing = GroupCallMetrics.SPACING
        val unique = ids.distinct()
        val caps = limits(width, height)
        val shown = unique.take(caps.first * caps.second)
        val overflow = unique.drop(shown.size)
        val w = width - 2 * inset
        val h = height - 2 * inset
        if (shown.isEmpty() || w <= 0 || h <= 0) return CallGridLayout(0, 0, emptyMap(), overflow)
        val (columns, rows) = bestShape(shown.size, w, h, caps)
        val rowHeight = (h - spacing * (rows - 1)) / rows
        val tileWidth = (w - spacing * (columns - 1)) / columns
        val frames = LinkedHashMap<String, TileFrame>()
        for (row in 0 until rows) {
            val start = row * columns
            val inRow = min(columns, shown.size - start)
            val rowWidth = tileWidth * inRow + spacing * (inRow - 1)
            val lead = (w - rowWidth) / 2
            val y = inset + row * (rowHeight + spacing)
            for (col in 0 until inRow) {
                frames[shown[start + col]] = TileFrame(inset + lead + col * (tileWidth + spacing), y, tileWidth, rowHeight)
            }
        }
        return CallGridLayout(columns, rows, frames, overflow)
    }

    fun capacity(width: Float, height: Float): Int = limits(width, height).let { it.first * it.second }

    private fun limits(width: Float, height: Float): Pair<Int, Int> {
        var columns = GroupCallMetrics.maxColumns(width)
        if (width > height) columns = max(columns, 3)
        var rows = GroupCallMetrics.maxRows(height)
        if (height < 300) rows = min(rows, 2)
        return max(columns, 1) to max(rows, 1)
    }

    private fun bestShape(count: Int, width: Float, height: Float, caps: Pair<Int, Int>): Pair<Int, Int> {
        val spacing = GroupCallMetrics.SPACING
        var best: Triple<Int, Int, Float>? = null
        for (columns in 1..max(1, min(caps.first, count))) {
            val rows = (count + columns - 1) / columns
            if (rows > caps.second) continue
            val rowHeight = (height - spacing * (rows - 1)) / rows
            val fullWidth = (width - spacing * (columns - 1)) / columns
            val lastCount = count - columns * (rows - 1)
            val lastWidth = (width - spacing * (lastCount - 1)) / lastCount
            if (rowHeight <= 0 || fullWidth <= 0) continue
            val fullCost = abs(ln(fullWidth / rowHeight)) * (count - lastCount)
            val lastCost = abs(ln(lastWidth / rowHeight)) * lastCount
            val orphan = if (columns >= 3 && rows > 1 && lastCount == 1) ORPHAN_PENALTY else 0f
            val score = (fullCost + lastCost) / count + orphan
            val cur = best
            if (cur == null) best = Triple(columns, rows, score)
            else {
                val nearTie = abs(score - cur.third) < 0.01f
                if (if (nearTie) rows > cur.second else score < cur.third) best = Triple(columns, rows, score)
            }
        }
        val chosen = best ?: return max(1, min(caps.first, count)).let { c -> c to (count + c - 1) / c }
        return chosen.first to chosen.second
    }
}

/**
 * iOS GroupCallSpeakerTracker: the ONE active speaker, held so the ring does not flicker. A voice
 * counts after 0.3 s of unbroken speech; the holder keeps it while talking and through gaps under
 * 0.6 s; a silent holder lets go after 1.5 s; ties go to the earliest start, then the smaller id.
 * Fed on every room update and on a 250 ms tick.
 */
class SpeakerTracker {
    companion object {
        const val QUALIFY_MS = 300L
        const val TAKEOVER_SILENCE_MS = 600L
    }

    var activeSpeakerId: String? = null
        private set
    private var speakingSince = mutableMapOf<String, Long>()
    private var activeLastHeard: Long? = null

    /** Returns true when [activeSpeakerId] changed. */
    fun update(speaking: Set<String>, now: Long): Boolean {
        val before = activeSpeakerId
        speakingSince = speakingSince.filterKeys { it in speaking }.toMutableMap()
        for (id in speaking) if (id !in speakingSince) speakingSince[id] = now
        val current = activeSpeakerId
        if (current != null && current in speaking) { activeLastHeard = now; return false }
        val heard = activeLastHeard
        if (current != null && heard != null && now - heard < TAKEOVER_SILENCE_MS) return false
        val candidate = speakingSince.filter { now - it.value >= QUALIFY_MS }
            .minWithOrNull(compareBy<Map.Entry<String, Long>> { it.value }.thenBy { it.key })?.key
        if (candidate != null) {
            activeSpeakerId = candidate; activeLastHeard = now
        } else if (current != null) {
            if (now - (heard ?: now) >= GroupCallMetrics.SPEAKER_HOLD_MS) { activeSpeakerId = null; activeLastHeard = null }
        }
        return activeSpeakerId != before
    }
}

/** iOS CallRole: the server's `role` attribute on a participant. */
enum class CallRole {
    OWNER, MODERATOR, PARTICIPANT;

    /** The people list's badge. */
    val badge: String? get() = when (this) { OWNER -> "Host"; MODERATOR -> "Admin"; PARTICIPANT -> null }

    companion object {
        fun of(attribute: String?): CallRole = when (attribute) { "owner" -> OWNER; "moderator" -> MODERATOR; else -> PARTICIPANT }
    }
}

/** The words of the group call screens, as iOS says them. */
object GroupCallWords {
    /** iOS GroupCallWords.status: the line that outranks the rest. */
    fun status(waiting: Boolean, connecting: Boolean, reconnecting: Boolean): String? = when {
        reconnecting -> "Reconnecting…"
        waiting -> "Waiting to be let in"
        connecting -> "Connecting…"
        else -> null
    }

    /**
     * iOS GroupCallView: "Ringing everyone…" while a call I started rings, from the tap (iOS
     * `dialing`: no "Connecting…" first), else the count.
     */
    fun subtitle(waiting: Boolean, connecting: Boolean, reconnecting: Boolean, joined: Boolean, callingNow: Boolean, inCall: Int): String =
        (if (callingNow && !reconnecting && !waiting) "Ringing everyone…" else null)
            ?: status(waiting, connecting, reconnecting)
            ?: if (callingNow) "Ringing everyone…"
            else if (joined && inCall <= 1) "No one else is here"
            else if (joined) "$inCall in call" else " "

    /** iOS GroupCallRingingView.names: "Ana", "Ana and Bilan", "Ana, Bilan and 3 others". */
    fun names(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> { val rest = names.size - 2; "${names[0]}, ${names[1]} and $rest ${if (rest == 1) "other" else "others"}" }
    }

    /** iOS CallLobbyView.whoIsHere: who is in the link's call, from the server's peek. */
    fun whoIsHere(names: List<String>, count: Int): String {
        val named = names.filter { it.isNotEmpty() }.take(2)
        val others = max(count, named.size) - named.size
        val othersText = if (others == 1) "1 other" else "$others others"
        return when {
            named.isEmpty() && others == 0 -> "No one else is here"
            named.isEmpty() && others == 1 -> "1 person is here"
            named.isEmpty() -> "$others people are here"
            named.size == 1 && others == 0 -> "${named[0]} is in this call"
            named.size == 1 -> "${named[0]} and $othersText are in this call"
            others == 0 -> "${named[0]} and ${named[1]} are in this call"
            else -> "${named[0]}, ${named[1]} and $othersText are in this call"
        }
    }

    /** iOS GroupCallParticipantsSheet.sheetTitle. */
    fun sheetTitle(alone: Boolean) = if (alone) "Waiting for others" else "Participants"

    /** iOS waitingTitle: the link creator's row to everyone at the door. */
    fun waitingTitle(n: Int) = "$n waiting to join"

    /** iOS LiveGroupCallRow / CallBarOrPin: "Video Chat" or "Voice Chat". */
    fun liveKind(video: Boolean) = if (video) "Video Chat" else "Voice Chat"

    /** The in-chat bar's second line. */
    fun barSubtitle(people: Int) = when (people) { 0 -> "Live now"; 1 -> "1 participant"; else -> "$people participants" }

    /** The Calls tab row's second line. */
    fun rowSubtitle(video: Boolean, people: Int) = liveKind(video) + when (people) { 0 -> ""; 1 -> " · 1 participant"; else -> " · $people participants" }
}

/** iOS FaceCluster.spots: centre offsets and sizes (dp) inside the 270 plate of the ring screen. */
object FaceCluster {
    const val PLATE = 270f
    data class Spot(val x: Float, val y: Float, val size: Float)

    fun spots(n: Int): List<Spot> = when (n) {
        1 -> listOf(Spot(0f, 0f, 150f))
        2 -> listOf(Spot(-38f, -20f, 124f), Spot(62f, 52f, 92f))
        3 -> listOf(Spot(-38f, -38f, 124f), Spot(74f, 0f, 84f), Spot(-6f, 82f, 76f))
        else -> listOf(Spot(-40f, -40f, 120f), Spot(66f, -66f, 52f), Spot(76f, 22f, 84f), Spot(-8f, 86f, 72f))
    }.take(max(0, min(n, 4)))
}

/** Who goes where on the stage (iOS GroupCallGridView.plan with its sticky cells, kept simple). */
object GroupStagePlan {
    /** iOS GroupCallPriority.newestFirst: the latest to join first; ties by id. */
    fun newestFirst(people: List<GroupMember>): List<GroupMember> =
        people.sortedWith(compareByDescending<GroupMember> { it.joinedAtMs }.thenBy { it.id })

    /**
     * The grid's order: with 2+ others, my tile first (iOS 2026-10-27), then the others in the order
     * they came. When they do not all fit ([capacity] cells, mine included), an active speaker who
     * is off the grid takes the last placed person's cell; everyone past the grid goes to the strip.
     */
    fun gridIds(meId: String?, remotes: List<String>, speaker: String?, capacity: Int): List<String> {
        val mine = if (meId != null && remotes.size >= 2) listOf(meId) else emptyList()
        val room = maxOf(0, capacity - mine.size)
        val placed = remotes.take(room).toMutableList()
        val rest = remotes.drop(room).toMutableList()
        if (speaker != null && speaker in rest && placed.isNotEmpty()) {
            val out = placed.removeAt(placed.lastIndex)
            placed += speaker
            rest.remove(speaker); rest.add(0, out)
        }
        return mine + placed + rest
    }

    /** iOS GroupCallSelfView.size: 90 x 160; enlarged 170 x 300 capped at 45% of the width and the height less 24. */
    fun selfSize(expanded: Boolean, stageW: Float, stageH: Float): Pair<Float, Float> {
        if (!expanded) return GroupCallMetrics.SELF_W to GroupCallMetrics.SELF_H
        val aspect = 170f / 300f
        var w = 170f
        if (stageW > 0) w = minOf(w, stageW * 0.45f)
        if (stageH > 0) w = minOf(w, (stageH - 24) * aspect)
        w = maxOf(w, 90f)
        return w to w / aspect
    }

    /** iOS GroupCallTileView.avatarSize: by the tile's width, capped by its height. */
    fun avatarSize(width: Float, height: Float): Float {
        val byWidth = when { width > 180 -> 112f; width > 102 -> 96f; width > 48 -> width - 36; else -> 16f }
        val byHeight = if (width > height * 1.4f) height * 0.56f else height - 16
        return minOf(byWidth, maxOf(16f, byHeight))
    }
}
