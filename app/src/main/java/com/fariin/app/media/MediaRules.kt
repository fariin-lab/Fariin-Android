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
    // ---- Limits.swift and storage.rules `chat/{cid}/{file}`: size < 102 MB, create only
    // (owner 2026-11-13: chat videos up to 100 MB, plus 2 MB for the encryption bytes).
    const val STORAGE_CHAT_LIMIT = 102L * 1024 * 1024
    // The admin panel may lower these (getAppConfig, owner 2026-11-13); storage.rules keeps 102 MB.
    val FILE_UPLOAD_BYTES: Long get() = com.fariin.app.data.AppConfig.bytes(com.fariin.app.data.AppConfig.K.fileUploadMB)
    val VIDEO_MESSAGE_BYTES: Long get() = com.fariin.app.data.AppConfig.bytes(com.fariin.app.data.AppConfig.K.videoMessageMB)
    /** What the video export aims at, unchanged (iOS Limits.videoExportTargetBytes): only the cap moved. */
    val VIDEO_EXPORT_TARGET_BYTES: Long get() = com.fariin.app.data.AppConfig.bytes(com.fariin.app.data.AppConfig.K.videoExportTargetMB)
    val VOICE_NOTE_SECONDS: Int get() = com.fariin.app.data.AppConfig.int(com.fariin.app.data.AppConfig.K.voiceNoteMinutes) * 60
    /** AudioRecorder.finish: a note under a second is not sent (it would read "0:00"). */
    const val VOICE_MIN_SECONDS = 1.0
    /** Largest download this app accepts for one chat item (the storage rule's ceiling). */
    const val MAX_DOWNLOAD = STORAGE_CHAT_LIMIT

    // ---- PhotoQuality.swift
    enum class PhotoQuality(val maxBytes: Int) {
        STANDARD(1_572_864),   // 1.5 MB
        HIGH(3_145_728);       // 3 MB
        /** 1280 / 2560 shipped; the admin panel may change them (getAppConfig). */
        val maxSide: Int get() = com.fariin.app.data.AppConfig.int(if (this == STANDARD) com.fariin.app.data.AppConfig.K.photoMaxSideStandard else com.fariin.app.data.AppConfig.K.photoMaxSideHigh)
        val jpegQuality: Int get() = com.fariin.app.data.AppConfig.int(com.fariin.app.data.AppConfig.K.photoJpegQualityPct)   // UIKit 0.6
    }
    val MIN_SIDE: Int get() = com.fariin.app.data.AppConfig.int(com.fariin.app.data.AppConfig.K.photoMinSide)

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
    /** Limits.captionMaxChars: a photo, video or album caption (1,024 shipped; was 1,000 here). */
    val CAPTION_CHARS: Int get() = com.fariin.app.data.AppConfig.int(com.fariin.app.data.AppConfig.K.captionMaxChars)
    val ALBUM_MAX_ITEMS: Int get() = com.fariin.app.data.AppConfig.int(com.fariin.app.data.AppConfig.K.albumMaxItems)
    /** Limits.mediaPerMessage: the picker's ceiling. */
    val MEDIA_PER_PICK: Int get() = com.fariin.app.data.AppConfig.int(com.fariin.app.data.AppConfig.K.mediaPerMessage)
    /** Limits.forwardChatsAtOnce. */
    /** 0 from the server means no limit (owner 2026-11-13). */
    val FORWARD_CHATS_AT_ONCE: Int get() = com.fariin.app.data.AppConfig.int(com.fariin.app.data.AppConfig.K.forwardChatsAtOnce).let { if (it > 0) it else Int.MAX_VALUE }

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

    /**
     * iOS ByteCountFormatter (.file): decimal units, "Zero KB", "1 byte", "980 bytes", "245 KB",
     * "3.4 MB", "1.23 GB". KB has no decimals, MB one, GB two; a trailing ".0" is dropped ("2 MB"),
     * and a size that rounds up to the next unit is said in that unit (999,999 bytes is "1 MB").
     * Used where iOS uses ByteCountFormatter: storage pages, backups, the phone-full sheet.
     */
    fun fileSizeLabel(bytes: Long): String {
        if (bytes == 0L) return "Zero KB"
        if (bytes < 0) return "-" + fileSizeLabel(-bytes)
        if (bytes == 1L) return "1 byte"
        if (bytes < 1000) return "$bytes bytes"
        val units = listOf("KB" to 0, "MB" to 1, "GB" to 2, "TB" to 2)
        var value = bytes / 1000.0
        for ((i, u) in units.withIndex()) {
            val scale = 10.0.pow(u.second)
            val rounded = Math.round(value * scale) / scale
            if (rounded < 1000 || i == units.size - 1) {
                val text = String.format(java.util.Locale.US, "%." + u.second + "f", rounded)
                    .let { if (it.contains('.')) it.trimEnd('0').trimEnd('.') else it }
                return "$text ${u.first}"
            }
            value /= 1000.0
        }
        return "$bytes bytes"
    }

    /**
     * The file bubble's size (iOS MessageRowModelBuilder fileBody): binary units, "512 B", "245 KB",
     * "3.4 MB", always one decimal for MB; "Document" when the size is not known.
     */
    fun bubbleSizeLabel(bytes: Long?): String = when {
        bytes == null -> "Document"
        bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** The short type word in the file icon: "PDF", "DOCX", ... or "FILE". */
    fun fileBadge(name: String): String {
        val ext = name.substringAfterLast('.', "").uppercase()
        return if (ext.isEmpty() || ext.length > 4 || ext == name.uppercase()) "FILE" else ext
    }

    const val FILE_PREVIEW = "📄 File"
    const val SONG_PREVIEW = "🎵 Song"

    /** iOS MusicFiles.extensions: a file with one of these names is a song. */
    private val SONG_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "aif", "aiff", "caf", "m4b")

    fun isSong(name: String?): Boolean =
        name?.substringAfterLast('.', "")?.lowercase()?.let { it.isNotEmpty() && it in SONG_EXTENSIONS } == true

    /** What a document send writes to the chat list (iOS sendFile): "🎵 Song" for a song, else "📄 File". */
    fun filePreview(name: String?): String = if (isSong(name)) SONG_PREVIEW else FILE_PREVIEW
    const val VIEW_ONCE_PREVIEW = "View-once photo"
    fun videoPreview(seconds: Double) = "🎥 Video · " + durationLabel(seconds)

    // ---- Labels
    /** ChatService.voiceDurationLabel: rounded seconds as m:ss. */
    fun durationLabel(seconds: Double): String {
        val d = seconds.roundToInt().coerceAtLeast(0)
        return "%d:%02d".format(d / 60, d % 60)
    }

    /** A length drawn in a bubble (iOS MediaDuration.whole): whole seconds cut, not rounded, as m:ss.
     *  A 7.6 s note reads "0:07" there; only the chat list's preview rounds. */
    fun bubbleClock(seconds: Double): String {
        val d = if (seconds.isFinite() && seconds > 0) minOf(seconds, 359_999.0).toInt() else 0
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
     * gives the peak amplitude since the last call (0...32767). A -50 dB floor maps to 0.
     * Then the 0.85 power iOS stores (SampledWaveform.storedBars, perceptualLevel): the bubble's
     * [display] undoes it, so a bar stored without it drew shorter on both phones.
     */
    fun level(amplitude: Int): Float {
        if (amplitude <= 0) return 0f
        val db = 20 * kotlin.math.log10(amplitude / 32767.0)
        return ((db + 50) / 50).coerceIn(0.0, 1.0).pow(0.85).toFloat()
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

    /** iOS albumBody: how many items ride the "+N" on the last tile (an album holds ten). */
    fun albumExtra(count: Int): Int = maxOf(0, count - ALBUM_MAX_ITEMS)

    /** MessageRowLayout play badge: at most 52 (owner 2026-10-28: a big video wore a huge disc), else 28% of the short side. */
    fun playBadgeSide(w: Float, h: Float): Float = min(52f, min(w, h) * 0.28f)

    /** MessageRowLayout album ring: 52 like a single photo, half the tile's short side on a small tile. */
    fun tileRingSide(w: Float, h: Float): Float = min(52f, min(w, h) * 0.5f)

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

    // ---- MessageRowLayout `wide` (a channel post): wider than a chat bubble, left-aligned, with a
    // 44 gap on the right for the share arrow. The row keeps its 12 margins and the failed badge's
    // 24 + 8 when it shows. Groups and 1:1 keep 72 % of the row.
    fun channelPostWidth(rowWidth: Float, failed: Boolean = false): Float =
        max(1f, rowWidth - 12f * 2 - (if (failed) 32f else 0f) - 44f)

    // ---- MessageRowLayout.wideMediaBox: a channel post's photo or video takes the post's full
    // width at its own shape, a tall one at most 1.25 x the width (aspect 0.8), a wide one 2.5 : 1.
    fun wideMediaBox(pixelW: Double?, pixelH: Double?, width: Float): Pair<Float, Float> {
        val aspect = if (pixelW != null && pixelH != null && pixelW > 0 && pixelH > 0)
            (pixelW / pixelH).toFloat().coerceIn(0.8f, 2.5f) else 1f
        // Capped for a wide screen (iOS 2026-11-17): at most 600 across and 560 down, same shape.
        // A phone held upright is under both; a phone on its side or a tablet no longer gets a
        // portrait post taller than the screen.
        var w = min(width, WIDE_MEDIA_MAX_W)
        var h = w / aspect
        if (h > WIDE_MEDIA_MAX_H) { h = WIDE_MEDIA_MAX_H; w = h * aspect }
        return w.roundToInt().toFloat() to h.roundToInt().toFloat()
    }
    const val WIDE_MEDIA_MAX_W = 600f
    const val WIDE_MEDIA_MAX_H = 560f
}
