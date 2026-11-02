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
