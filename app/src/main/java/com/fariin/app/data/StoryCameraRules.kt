package com.fariin.app.data

/**
 * The story camera's numbers (iOS StoryCameraView / StoryVideoEncoder), kept pure so they can be
 * tested without a camera.
 *
 * - A tap on the shutter takes a photo; a hold of [HOLD_MS] starts a video (iOS LongPressGesture 0.22 s).
 * - A video stops by itself at [MAX_VIDEO_SECONDS] (iOS movieOutput.maxRecordedDuration 60 s).
 * - The encoder gets a capped bitrate so a clip stays small on a cheap phone and a slow network.
 */
object StoryCameraRules {
    const val HOLD_MS = 220L
    const val MAX_VIDEO_SECONDS = 60
    const val AUDIO_BITS = 128_000

    /** Shortest clip worth posting; a hold released sooner than this is dropped, not posted. */
    const val MIN_VIDEO_MS = 500L

    /** The preview only has to fill a phone screen, so it never asks for more than this. */
    fun previewLongSide(lowEnd: Boolean): Int = if (lowEnd) 960 else 1280

    /** Photos: the story is 1080 wide at most (StoryMedia.preparePhoto), so 1920 on the long side is plenty. */
    fun photoLongSide(lowEnd: Boolean): Int = if (lowEnd) 1440 else 1920

    /**
     * Video bitrate in bits per second, by the long side of the recording. iOS writes 1280-long H.264
     * at about 4.5 Mbit/s; Android aims lower because stories are watched on phones and cheap data:
     * 1080p 4 Mbit/s, 720p 2.5, 480p and below 1.2; a LOW phone (often one whose camera offers only
     * 720p) 1.5 for 720p and up, 1.0 below. Never more than fits [StoryRules.VIDEO_MAX_BYTES] over a
     * full-length clip with the sound. (Left to itself CameraX picked 12 Mbit/s for 720p on API 27.)
     */
    fun videoBitrate(longSide: Int, seconds: Int = MAX_VIDEO_SECONDS, lowEnd: Boolean = false): Int {
        val target = when {
            lowEnd && longSide >= 1100 -> 1_500_000
            lowEnd -> 1_000_000
            longSide >= 1700 -> 4_000_000
            longSide >= 1100 -> 2_500_000
            else -> 1_200_000
        }
        val room = (StoryRules.VIDEO_MAX_BYTES * 8 / seconds.coerceAtLeast(1)) - AUDIO_BITS - 64_000
        return maxOf(600_000, minOf(target.toLong(), room).toInt())
    }

    /** "00:05" on the red recording clock (iOS recordingClock). */
    fun clock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    /** What a press on the shutter turned out to be, given how long the finger stayed down. */
    enum class Press { PHOTO, VIDEO }

    fun pressKind(heldMs: Long): Press = if (heldMs >= HOLD_MS) Press.VIDEO else Press.PHOTO

    /** A finished recording is posted only when it is long enough to be a clip. */
    fun keepClip(recordedMs: Long): Boolean = recordedMs >= MIN_VIDEO_MS

    // Slide to lock (iOS shutter drag): sideways is the lock, upward is the zoom.

    /** How far right (dp) the finger slides from the shutter before the recording runs hands free. */
    const val LOCK_SLIDE_DP = 60f

    /** 0 to 1, how close the finger is to the lock; drives the lock's growth while sliding. */
    fun lockProgress(dxDp: Float): Float = (dxDp / LOCK_SLIDE_DP).coerceIn(0f, 1f)

    fun reachesLock(dxDp: Float): Boolean = dxDp > LOCK_SLIDE_DP

    /**
     * Does lifting the finger stop the recording? Not locked: yes, an ordinary hold. Locked by this
     * same touch: no, that is the tail of the slide onto the lock. Locked earlier: yes, this touch
     * was a press on the stop button.
     */
    fun stopsOnRelease(locked: Boolean, lockedByThisTouch: Boolean): Boolean = !locked || !lockedByThisTouch

    /** Zoom in the pill's units goes no further than this (past 8x a hand-held phone films its shake). */
    fun maxZoom(lensMaxRatio: Float): Float = lensMaxRatio.coerceAtLeast(1f).coerceAtMost(8f)

    /**
     * Drag up from the held shutter to zoom: past 10 dp up, one step (1x) per 60 dp, from the zoom
     * the recording started at, up to [maxZoom]. Null while the finger is not going up, or is
     * going sideways (40 dp or more) towards the lock.
     */
    fun dragZoom(startZoom: Float, upDp: Float, dxDp: Float, lensMaxRatio: Float): Float? {
        if (upDp <= 10f || kotlin.math.abs(dxDp) >= 40f) return null
        return minOf(maxZoom(lensMaxRatio), startZoom + (upDp - 10f) / 60f)
    }

    /**
     * The zoom pill's stops (iOS .5 / 1 / 3), only those this lens can reach: .5 needs a wide lens
     * behind the camera (a zoom ratio range starting at about 0.5), 3 needs a range up to 3. A pill
     * with only "1" in it does nothing, so then there is no pill (empty list).
     */
    fun zoomStops(lensMinRatio: Float, lensMaxRatio: Float): List<Float> {
        val stops = buildList {
            if (lensMinRatio <= 0.55f) add(0.5f)
            add(1f)
            if (lensMaxRatio >= 3f) add(3f)
        }
        return if (stops.size < 2) emptyList() else stops
    }

    /** ".5", "1", "3"; the stop in use gets the "×" (iOS zoomButton). */
    fun zoomLabel(stop: Float, selected: Boolean): String {
        val n = if (stop < 1f) ".5" else stop.toInt().toString()
        return if (selected) "$n×" else n
    }

    /** The front lens with the flash on and no flash unit: the screen lights the photo (white, full brightness). */
    fun needsScreenFlash(flashOn: Boolean, front: Boolean, lensHasFlash: Boolean): Boolean = flashOn && front && !lensHasFlash

    /** The flash button is drawn for a lens with a flash unit, and for the front lens (the screen is its flash). */
    fun showsFlashButton(front: Boolean, lensHasFlash: Boolean): Boolean = lensHasFlash || front
}
