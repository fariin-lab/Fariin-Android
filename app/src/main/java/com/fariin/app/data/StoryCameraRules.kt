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
}
