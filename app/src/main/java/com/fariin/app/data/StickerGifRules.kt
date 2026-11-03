package com.fariin.app.data

import java.net.URI

/**
 * The pure parts of stickers, GIFs, call bubbles and link cards, each after its iOS source so the
 * two apps draw and word them the same. Unit tested (StickerGifRulesTest).
 */
object StickerGifRules {

    // ------------------------------------------------------------------ stickers (iOS StickerSend)

    /** "😂 Sticker", or "Sticker": chat list, reply quote, forward label, push text. */
    fun stickerLabel(emoji: String?): String {
        val e = emoji?.trim()
        return if (!e.isNullOrEmpty()) "$e Sticker" else "Sticker"
    }

    /** The public copy of a built-in sticker on fariin.com (StickerSend.publicURL). */
    fun stickerPublicUrl(packId: String, stickerId: String): String =
        "https://fariin.com/stickers/$packId/$stickerId.webp"

    /** What an older build reads instead of the sticker (StickerSend.oldBuildMarker). */
    const val OLD_BUILD_MARKER = "fariin-sticker:1"

    /** MessageRowLayout.stickerSide: the art aspect-fit inside 160 x 160, never past it. */
    const val STICKER_SIDE = 160f

    /** MessageRowLayout.stickerBox, in dp. */
    fun stickerBox(width: Double?, height: Double?): Pair<Float, Float> {
        val side = STICKER_SIDE
        if (width == null || height == null || width <= 0 || height <= 0) return side to side
        val aspect = (width / height).toFloat()
        return if (aspect >= 1) side to maxOf(40f, Math.round(side / aspect).toFloat())
        else maxOf(40f, Math.round(side * aspect).toFloat()) to side
    }

    /** Recently used, as iOS StickerStore.noteUsed: newest first, no duplicates, at most 20. */
    const val RECENTS_CAP = 20
    fun noteUsed(recents: List<String>, key: String): List<String> =
        (listOf(key) + recents.filter { it != key }).take(RECENTS_CAP)

    // ------------------------------------------------------------------ GIFs (iOS displayBox)

    /** MessageRowLayout.displayBox: at most 240 wide and 340 tall, its own aspect; 220 square unknown. */
    fun gifBox(width: Double?, height: Double?): Pair<Float, Float> {
        val maxW = 240f
        val maxH = 340f
        if (width == null || height == null || width <= 0 || height <= 0) return 220f to 220f
        val aspect = (width / height).toFloat()
        var dw = maxW
        var dh = dw / aspect
        if (dh > maxH) { dh = maxH; dw = dh * aspect }
        return dw to dh
    }

    /** One GIPHY result (GiphyService.Gif): the fixed_width animated link and its size. */
    data class Gif(val id: String, val url: String, val width: Double, val height: Double)

    /** GiphyService.fetch: `data[].images.fixed_width` {url, width, height}; a bad entry is skipped. */
    fun parseGiphy(json: String): List<Gif> = try {
        val arr = org.json.JSONObject(json).optJSONArray("data") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val g = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = g.optString("id").ifEmpty { return@mapNotNull null }
            val fixed = g.optJSONObject("images")?.optJSONObject("fixed_width") ?: return@mapNotNull null
            val url = fixed.optString("url").ifEmpty { return@mapNotNull null }
            Gif(id, url, fixed.optString("width").toDoubleOrNull() ?: 200.0, fixed.optString("height").toDoubleOrNull() ?: 200.0)
        }
    } catch (_: Exception) { emptyList() }

    /** GifPickerView: two columns, each GIF to whichever column is shorter so far. */
    fun masonry(gifs: List<Gif>): Pair<List<Gif>, List<Gif>> {
        val left = mutableListOf<Gif>()
        val right = mutableListOf<Gif>()
        var lh = 0.0
        var rh = 0.0
        for (g in gifs) {
            val h = if (g.width > 0) g.height / g.width else 1.0
            if (lh <= rh) { left += g; lh += h } else { right += g; rh += h }
        }
        return left to right
    }

    // ------------------------------------------------------------------ call bubbles (iOS callRow)

    enum class CallIcon { VIDEO, VIDEO_MISSED, PHONE_IN, PHONE_OUT }

    data class CallLook(
        val mine: Boolean,
        val status: String,
        val detail: String,
        val icon: CallIcon,
        val missedIncoming: Boolean,
        /** Ringing or ongoing: a tap offers no call back. */
        val live: Boolean,
        val video: Boolean,
    )

    /** GroupCallOutcome.listCap / notJoined. */
    private const val LIST_CAP = 64
    const val NOT_JOINED = "notJoined"

    /** GroupCallOutcome.mine: a group call as THIS viewer saw it; a 1:1 record keeps its own outcome. */
    fun outcomeMine(stored: String?, callerUid: String?, joined: List<String>?, rung: List<String>?, me: String): String? {
        if (joined == null || me.isEmpty() || stored == "ongoing" || stored == "ringing") return stored
        if (callerUid == me) return if (joined.any { it != me }) "answered" else "missed"
        if (me in joined) return "answered"
        if (joined.size >= LIST_CAP) return stored
        if (rung != null && rung.size < LIST_CAP && me !in rung) return NOT_JOINED
        return "missed"
    }

    /** MessageRowModelBuilder.durationLabel: "45 sec", "3:07", "1:02:03". */
    fun callDuration(s: Int): String = when {
        s < 60 -> "$s sec"
        s < 3600 -> "%d:%02d".format(s / 60, s % 60)
        else -> "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    /**
     * MessageRowModelBuilder.callRow. [ageSeconds] is now minus the record's time; [time] the
     * shortened clock. [calleeRinging]: my own call reads Ringing only once the other phone rings.
     */
    fun callLook(m: Message, me: String, ageSeconds: Double, time: String, calleeRinging: Boolean = false): CallLook {
        val mine = m.callerUid == me
        val outcome = outcomeMine(m.callOutcome, m.callerUid, m.callJoinedUids, m.callRungUids, me)
        val ringing = outcome == "ringing" && ageSeconds < 120
        val ongoing = outcome == "ongoing" && ageSeconds < 4 * 3600
        // Legacy "declined" renders exactly as missed; an orphan ringing as unanswered.
        val missed = outcome == "missed" || outcome == "declined" || (outcome == "ringing" && ageSeconds >= 120)
        val video = m.callVideo
        // Red "Missed" only for a call I RECEIVED and did not answer; mine unanswered is "No answer".
        val incomingMissed = missed && !mine
        val status = if (video) (if (incomingMissed) "Missed video call" else "Video call")
            else (if (incomingMissed) "Missed voice call" else "Voice call")
        val d = m.callDuration
        val detail = when {
            ringing -> (if (mine && !calleeRinging) "Calling" else "Ringing") + " · $time"
            ongoing -> "Ongoing · $time"
            incomingMissed -> "Call back · $time"
            missed -> "No answer · $time"
            outcome == NOT_JOINED -> time
            d != null && d > 0 -> "${callDuration(d)} · $time"
            else -> "${if (mine) "Outgoing" else "Incoming"} · $time"
        }
        val icon = when {
            video -> if (incomingMissed) CallIcon.VIDEO_MISSED else CallIcon.VIDEO
            incomingMissed -> CallIcon.PHONE_IN
            mine -> CallIcon.PHONE_OUT
            else -> CallIcon.PHONE_IN
        }
        return CallLook(mine, status, detail, icon, incomingMissed, ringing || ongoing, video)
    }

    /** ThreadView call-back alert: the title and its question. */
    fun callBackTitle(video: Boolean) = if (video) "Video call" else "Voice call"
    fun callBackQuestion(video: Boolean, name: String) = "${if (video) "Video call" else "Call"} $name?"

    // ------------------------------------------------------------------ link cards (iOS LinkPreviewData)

    /** LinkPreviewData.host: the link's host without "www."; the whole link when it has none. */
    fun host(url: String): String = try {
        URI(url).host?.replace("www.", "") ?: url
    } catch (_: Exception) { url }

    /** MessageRowModelBuilder: a YouTube card says "YouTube", whatever spelling the link used. */
    fun cardHost(url: String): String {
        val h = host(url).lowercase()
        return if (h == "youtu.be" || h == "youtube.com" || h.endsWith(".youtube.com")) "YouTube" else host(url)
    }
}
