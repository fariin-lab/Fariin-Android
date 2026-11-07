package com.fariin.app.media

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The numbers the iOS app sends media with, in one place so they can be tested without a phone.
 * Sources (Kulan-iOS): PhotoQuality.swift, ChatService.swift (sendJPEG, inlineThumb, sendImage,
 * sendAudio, voiceDurationLabel), Limits.swift, AudioRecorder.swift (waveform),
 * VoiceMessageView.swift (bar drawing), MessageRowLayout.swift (photoBox), storage.rules.
 */
object MediaRules {
    // ---- Limits.swift and storage.rules `chat/{cid}/{file}`: size < 64 MB, create only.
    const val STORAGE_CHAT_LIMIT = 64L * 1024 * 1024
    const val FILE_UPLOAD_BYTES = 25L * 1024 * 1024
    const val VIDEO_MESSAGE_BYTES = 64L * 1024 * 1024
    const val VOICE_NOTE_SECONDS = 30 * 60
    /** AudioRecorder.finish: a note under a second is not sent (it would read "0:00"). */
    const val VOICE_MIN_SECONDS = 1.0
    /** Largest download this app accepts for one chat item (the storage rule's ceiling). */
    const val MAX_DOWNLOAD = STORAGE_CHAT_LIMIT

    // ---- PhotoQuality.swift
    enum class PhotoQuality(val maxSide: Int, val maxBytes: Int) {
        STANDARD(1280, 1_572_864),   // 1.5 MB
        HIGH(2560, 3_145_728);       // 3 MB
        val jpegQuality: Int get() = 60   // UIKit 0.6
    }
    const val MIN_SIDE = 640

    /** PhotoQuality.nextSide: three quarters, rounded, while it stays at 640 or more. */
    fun nextSide(side: Int): Int? {
        val next = (side * 0.75).roundToInt()
        return if (next >= MIN_SIDE) next else null
    }

    /** PhotoQuality.fit: scale down (never up) so the long side is [longSide]; at least 1 x 1. */
    fun fit(w: Int, h: Int, longSide: Int): Pair<Int, Int> {
        val long = max(w, h)
        if (long <= 0) return 1 to 1
        val s = min(1.0, longSide.toDouble() / long)
        return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
    }

    /**
     * inSampleSize for showing a picture: halve while the short side stays at or over [targetShort],
     * then keep halving while the long side is over [maxLong] (0 = no cap), so a huge photo never
     * decodes at full size on a small phone.
     */
    fun sampleFor(w: Int, h: Int, targetShort: Int, maxLong: Int = 0): Int {
        if (w <= 0 || h <= 0) return 1
        var s = 1
        val short = min(w, h)
        val long = max(w, h)
        while (targetShort > 0 && short / (s * 2) >= targetShort) s *= 2
        while (maxLong > 0 && long / s > maxLong) s *= 2
        return s
    }

    /** sendJPEG's loop: the sides tried in order until the JPEG fits under maxBytes. */
    fun sideLadder(q: PhotoQuality): List<Int> = generateSequence(q.maxSide) { nextSide(it) }.toList()

    /** inlineThumb: 40 px on the long side, JPEG quality 0.45, base64, sealed like the caption. */
    const val THUMB_SIDE = 40
    const val THUMB_QUALITY = 45
    /** BlurHash.encode downsamples to 32 px first. */
    const val BLUR_SIDE = 32

    // ---- Storage paths (ChatService.sendImage / sendAudio / announceVideo)
    fun imagePath(cid: String, messageId: String) = "chat/$cid/$messageId.enc"
    /** iOS keeps ".m4a.enc" for every note, Opus CAF and AAC alike; the player sniffs the bytes. */
    fun audioPath(cid: String, messageId: String) = "chat/$cid/$messageId.m4a.enc"

    fun videoThumbPath(cid: String, messageId: String) = "chat/$cid/$messageId.thumb.enc"
    fun videoPath(cid: String, messageId: String) = "chat/$cid/$messageId.mp4.enc"
    fun filePath(cid: String, messageId: String) = "chat/$cid/$messageId.file.enc"
    fun fileThumbPath(cid: String, messageId: String) = "chat/$cid/$messageId.filethumb.enc"
    /** sendMixedAlbum tiles: "-i" (photo or poster), "-i-thumb" + "-i-video" for a clip. */
    fun albumTilePath(cid: String, messageId: String, suffix: String) = "chat/$cid/$messageId-$suffix.enc"

    // ---- Albums and forwarding (Limits.swift)
    /** Limits.albumMaxItems: one album message holds 10; a bigger pick is sent as several albums. */
    const val ALBUM_MAX_ITEMS = 10
    /** Limits.mediaPerMessage: the picker's ceiling. */
    const val MEDIA_PER_PICK = 32
    /** Limits.forwardChatsAtOnce. */
    const val FORWARD_CHATS_AT_ONCE = 5

    /** ThreadView: a pick of more than ten goes as albums of ten, in order; a lone one stays itself. */
    fun <T> albumChunks(items: List<T>): List<List<T>> = items.chunked(ALBUM_MAX_ITEMS)

    /**
     * Rows of the album grid: how many tiles each row holds, top to bottom. 1 = one, 2 = side by
     * side, 3 = one wide over two, 4 = 2 + 2, then rows of 3 with a row of 2 where the count needs
     * it (5 = 2 + 3, 6 = 3 + 3, 7 = 2 + 2 + 3, 8 = 2 + 3 + 3, 9 = 3 + 3 + 3, 10 = 2 + 2 + 3 + 3).
     */
    fun albumRows(n: Int): List<Int> = when {
        n <= 0 -> emptyList()
        n == 1 -> listOf(1)
        n == 2 -> listOf(2)
        n == 3 -> listOf(1, 2)
        n == 4 -> listOf(2, 2)
        else -> {
            val threes = when (n % 3) { 0 -> n / 3; 1 -> (n - 4) / 3; else -> (n - 2) / 3 }
            val twos = (n - threes * 3) / 2
            List(twos) { 2 } + List(threes) { 3 }
        }
    }

    // ---- Files
    /** A name that is safe as one path part: no folders, no control characters, not empty. */
    fun safeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .filter { it >= ' ' && it != ':' && it != '*' && it != '?' && it != '"' && it != '<' && it != '>' && it != '|' }
            .trim().trimStart('.')
        return base.take(120).ifEmpty { "File" }
    }

    /** "245 KB", "3.4 MB", "980 bytes" (iOS ByteCountFormatter .file, decimal units). */
    fun fileSizeLabel(bytes: Long): String = when {
        bytes < 1000 -> "$bytes bytes"
        bytes < 1_000_000 -> "${(bytes / 1000.0).roundToInt()} KB"
        bytes < 1_000_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
        else -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    }

    /** The short type word in the file icon: "PDF", "DOCX", ... or "FILE". */
    fun fileBadge(name: String): String {
        val ext = name.substringAfterLast('.', "").uppercase()
        return if (ext.isEmpty() || ext.length > 4 || ext == name.uppercase()) "FILE" else ext
    }

    const val FILE_PREVIEW = "📄 File"
    const val VIEW_ONCE_PREVIEW = "View-once photo"
    fun videoPreview(seconds: Double) = "🎥 Video · " + durationLabel(seconds)

    // ---- Labels
    /** ChatService.voiceDurationLabel: rounded seconds as m:ss. */
    fun durationLabel(seconds: Double): String {
        val d = seconds.roundToInt().coerceAtLeast(0)
        return "%d:%02d".format(d / 60, d % 60)
    }

    const val PHOTO_PREVIEW = "📷 Photo"            // "📷 Photo"
    fun voicePreview(seconds: Double) = "🎤 Voice message · " + durationLabel(seconds)   // "🎤 Voice message · 0:53"

    // ---- Waveform
    /** AudioRecorder.waveform: RMS per bucket, 0...100, at most [count] bars. */
    fun bars(levels: List<Float>, count: Int = 40): List<Int> {
        if (levels.isEmpty()) return emptyList()
        val per = max(1, kotlin.math.ceil(levels.size.toDouble() / count).toInt())
        val out = ArrayList<Int>()
        var i = 0
        while (i < levels.size && out.size < count) {
            val slice = levels.subList(i, min(i + per, levels.size))
            val ms = slice.fold(0f) { a, v -> a + v * v } / slice.size
            out.add((sqrt(ms) * 100).roundToInt())
            i += per
        }
        return out
    }

    /**
     * One meter reading as a 0...1 level. iOS meters average power in dB; Android's recorder
     * gives the peak amplitude since the last call (0...32767). Both map a -50 dB floor to 0.
     */
    fun level(amplitude: Int): Float {
        if (amplitude <= 0) return 0f
        val db = 20 * kotlin.math.log10(amplitude / 32767.0)
        return ((db + 50) / 50).toFloat().coerceIn(0f, 1f)
    }

    /** VoiceMessageView: bars merged (loudest wins) so a slot is at least 4 px wide. */
    fun drawBars(bars: List<Int>, widthPx: Float, minSlotPx: Float): List<Int> {
        val maxBars = max(8, (widthPx / minSlotPx).toInt())
        val step = max(1, kotlin.math.ceil(bars.size.toDouble() / maxBars).toInt())
        return bars.chunked(step) { it.max() }
    }

    /** VoiceMessageView.display: a stored bar (0...100) as a fraction of the wave's height. */
    fun display(stored: Int): Float {
        val v = stored.coerceIn(0, 100) / 100.0
        if (v <= 0) return 0f
        var b = v.pow(1 / 0.85) * (50.0 / 30.0)
        val quiet = 0.30
        if (b < quiet) b *= (b / quiet)
        if (b <= 0.82) return b.toFloat()
        return (0.82 + (1 - exp(-(b - 0.82) * 2.2)) * 0.18).toFloat()
    }

    /** VoiceNotePlayer.cycleRate: 1 then 1.5, 2, 0.5, back to 1. */
    fun nextRate(r: Float): Float = when (r) { 1f -> 1.5f; 1.5f -> 2f; 2f -> 0.5f; else -> 1f }
    fun rateLabel(r: Float): String = when (r) { 1.5f -> "1.5×"; 2f -> "2×"; 0.5f -> "0.5×"; else -> "1×" }

    // ---- UploadRingView.paint: what the upload ring draws.
    enum class RingPhase { SPIN, FILL, FINISH, GONE }

    /** No byte yet (waiting its turn is not zero) = spin; bytes = fill; all bytes up but the
     *  server not done = the turning "finishing" arc; done, failed or X'd = gone. */
    fun ringPhase(fraction: Float?, settled: Boolean): RingPhase = when {
        settled -> RingPhase.GONE
        fraction == null || fraction <= 0f -> RingPhase.SPIN
        fraction >= 0.999f -> RingPhase.FINISH
        else -> RingPhase.FILL
    }

    // ---- MessageRowLayout.photoBox: the bubble size for a photo, in dp.
    fun photoBox(pixelW: Double?, pixelH: Double?, maxBubbleWidth: Float, captionWidth: Float = 0f): Pair<Float, Float> {
        val boxMax = min(maxBubbleWidth, 350f)
        val aspect = if (pixelW != null && pixelH != null && pixelW > 0 && pixelH > 0)
            (pixelW / pixelH).toFloat().coerceIn(0.35f, 2.857f) else 1f
        val minW = if (captionWidth > 0) min(boxMax, captionWidth + 24) else 0f
        val maxH = boxMax * 1.2f
        val comfortW = maxH * (9f / 16f)
        var h = boxMax
        var w = h * aspect
        if (w < comfortW) { h = min(maxH, comfortW / aspect); w = h * aspect }
        w = max(w, minW)
        if (w > boxMax) { w = boxMax; h = w / aspect }
        if (pixelW != null && pixelH != null && pixelW > 0 && pixelH > 0) {
            val srcShort = min(pixelW, pixelH).toFloat()
            val dispShort = min(w, h)
            if (dispShort > srcShort && dispShort > 150) {
                val f = max(150f, srcShort) / dispShort
                w *= f; h *= f
            }
        }
        return w.roundToInt().toFloat() to h.roundToInt().toFloat()
    }
}
