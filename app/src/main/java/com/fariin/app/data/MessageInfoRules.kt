package com.fariin.app.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Message Info, read from Kulan-iOS MessageInfoView: for a group message I sent, a member has read
 * it when their last-read time (the chat's per-member lastRead millis) is at or after the message's
 * time; everyone else it has only been delivered to. Both lists are sorted by name.
 */
object MessageInfoRules {
    data class Split(val readBy: List<String>, val deliveredTo: List<String>)

    fun split(members: List<String>, me: String, lastRead: Map<String, Double>, msgMillis: Double,
              nameFor: (String) -> String): Split {
        val others = members.filter { it != me }
        val read = others.filter { (lastRead[it] ?: 0.0) >= msgMillis }.sortedBy(nameFor)
        val delivered = others.filter { (lastRead[it] ?: 0.0) < msgMillis }.sortedBy(nameFor)
        return Split(read, delivered)
    }

    /** The preview line (iOS snippet): media is a noun with its emoji, text never a raw marker. */
    fun snippet(m: Message): String = when {
        m.type == "image" && !m.imageUrl.isNullOrEmpty() -> "📷 Photo"
        m.type == "video" && !m.videoUrl.isNullOrEmpty() -> "🎥 Video"
        m.type == "audio" && !m.audioUrl.isNullOrEmpty() -> "🎤 Voice message"
        else -> CardRules.previewWord(m.text) ?: m.bodyText
    }

    /** iOS `.formatted(date: .abbreviated, time: .shortened)`: "Nov 3, 2026 at 2:05 PM", 12/24h per the phone. */
    fun dateLine(millis: Double, is24h: Boolean, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val t = Instant.ofEpochMilli(millis.toLong()).atZone(zone)
        val date = DateTimeFormatter.ofPattern("MMM d, yyyy", locale).format(t)
        return "$date at ${ThreadLogic.timeLabel(millis, is24h, zone, locale)}"
    }
}
