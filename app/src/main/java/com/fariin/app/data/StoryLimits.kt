package com.fariin.app.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * The story ceilings iOS keeps (Limits.swift, AppLimits, StoriesService), kept pure where they can be:
 * 50 stories a day (100 verified) and 40 an hour, both enforced by the rules' counters in
 * `users/{me}/limits/{storiesDaily,stories}`; 90 seconds a story video, a longer pick split into
 * several stories up to 10 minutes; 700 characters of caption.
 */
object StoryLimits {
    /** iOS Limits.storyVideoSeconds: one story's video. */
    const val VIDEO_SECONDS = 90
    /** iOS Limits.storyVideoPickSeconds: a longer pick is refused (seven stories at 90 s). */
    const val PICK_SECONDS = 600
    /** iOS Limits.storyCaptionChars. */
    const val CAPTION_CHARS = 700

    const val TOO_LONG_TITLE = "That video is too long"
    val TOO_LONG_TEXT = "Stories can be up to ${PICK_SECONDS / 60} minutes. Longer videos are posted as several stories, but this one is past the limit."
    const val DAY_TITLE = "That's today's limit"

    /** A pick past [PICK_SECONDS] (iOS allows one second of slack). */
    fun pickTooLong(durationSec: Double): Boolean = durationSec > PICK_SECONDS + 1

    /**
     * iOS postVideo's split: a clip up to [VIDEO_SECONDS] (+0.5 s) is one story, as it is; a longer
     * one is cut into [VIDEO_SECONDS] pieces from the start, at most [PICK_SECONDS] of it. Ranges in
     * ms; empty = post the clip whole.
     */
    fun segments(durationSec: Double): List<LongRange> {
        val cap = VIDEO_SECONDS.toDouble()
        if (durationSec <= cap + 0.5) return emptyList()
        val total = minOf(durationSec, PICK_SECONDS.toDouble())
        val count = kotlin.math.ceil(total / cap).toInt()
        return (0 until count).mapNotNull { i ->
            val start = i * cap
            val len = minOf(cap, total - start)
            if (len <= 0.05) null else (start * 1000).toLong()..((start + len) * 1000).toLong()
        }
    }

    // ---- the daily counter (iOS StoriesService dailyStoriesUsed / dailyWindowStart) -------------

    @Volatile var dailyUsed = 0
        private set
    @Volatile var dailyWindowStartMs: Long? = null
        private set
    /** Has the badge (MyProfile.verified), set by the Stories tab; nil profile reads as not verified. */
    @Volatile var verified = false

    fun dailyLimit(): Int = AppLimits.storiesPerDay(verified)

    /** Fails open like the rule: an unknown or rolled-over window is not a full one. */
    fun dailyReached(nowMs: Long = System.currentTimeMillis()): Boolean =
        reached(dailyWindowStartMs, dailyUsed, dailyLimit(), nowMs)

    fun reached(windowStartMs: Long?, used: Int, limit: Int, nowMs: Long): Boolean =
        windowStartMs != null && nowMs - windowStartMs <= DAY_MS && used >= limit

    /** Whole hours until the window rolls, at least one (never "in 0 hours"). */
    fun hoursLeft(windowStartMs: Long?, nowMs: Long = System.currentTimeMillis()): Int {
        val start = windowStartMs ?: return 0
        val left = DAY_MS - (nowMs - start)
        return maxOf(1, kotlin.math.ceil(left / 3_600_000.0).toInt())
    }

    /** The alert at the Add Story tap (iOS StoriesTabView). */
    fun dayAlertText(): String =
        "You've posted ${dailyLimit()} stories today. You can post again in about ${hoursLeft(dailyWindowStartMs)} hours."

    /** iOS postErrorMessage for a rules refusal: the day if it is spent, else the hour may be. */
    fun refusedText(): String = if (dailyReached())
        "You've posted ${dailyLimit()} stories today, which is the daily limit. You can post again in about ${hoursLeft(dailyWindowStartMs)} hours."
    else "This story couldn't be posted. If you've posted a lot in the last hour (the limit is ${AppLimits.storiesPerHour(verified)}), wait a little and try again."

    /** One document, read when the Stories tab shows and after a post, so the tap's answer is in hand. */
    suspend fun refresh(me: String) {
        if (me.isEmpty()) return
        runCatching {
            val d = FirebaseFirestore.getInstance().collection("users").document(me)
                .collection("limits").document("storiesDaily").get().await()
            dailyWindowStartMs = d.getTimestamp("windowStart")?.toDate()?.time
            dailyUsed = (d.get("count") as? Number)?.toInt() ?: 0
        }
    }

    private const val DAY_MS = 86_400_000L
}
