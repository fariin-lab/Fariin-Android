package com.fariin.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import com.fariin.app.R

/**
 * A chat's own message sound (iOS SoundStore .message, the same list as iOS NotificationSounds
 * messageTones): Note (the phone's default), our five bundled tones, or None. Kept on this phone
 * per chat. A channel's sound cannot change once made, so each tone has its own channel and a
 * chat's notifications are posted on its tone's channel; Note keeps the normal Messages channel.
 */
object MessageTones {
    class Tone(val id: String, val name: String, val raw: Int?)

    const val NOTE = "tritone"
    const val NONE = "none"
    val all = listOf(
        Tone(NONE, "None", null),
        Tone(NOTE, "Note (Default)", null),
        Tone("rebound", "Rebound", R.raw.tone_rebound),
        Tone("chime", "Chime", R.raw.tone_chime),
        Tone("pop", "Pop", R.raw.tone_pop),
        Tone("pulse", "Pulse", R.raw.tone_pulse),
        Tone("marimba", "Marimba", R.raw.tone_marimba),
    )

    private const val PREFS = "fariin_sounds"
    private fun key(cid: String) = "sound_message_$cid"

    fun of(ctx: Context, cid: String): Tone {
        val id = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(cid), null)
        return all.firstOrNull { it.id == id } ?: all.first { it.id == NOTE }
    }

    fun set(ctx: Context, cid: String, id: String) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (id == NOTE) e.remove(key(cid)) else e.putString(key(cid), id)
        e.apply()
    }

    fun uri(ctx: Context, t: Tone): Uri? = t.raw?.let { Uri.parse("android.resource://${ctx.packageName}/$it") }

    /** The channel a message in [cid] posts on: its tone's own, or the Messages channel for Note. */
    fun channelId(ctx: Context, cid: String, fallback: String): String {
        val t = of(ctx, cid)
        if (t.id == NOTE) return fallback
        val id = "msg_tone_${t.id}"
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return fallback
        if (nm.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(id, "Messages: ${t.name.removeSuffix(" (Default)")} sound", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Chats that use the ${t.name} sound"
            val u = uri(ctx, t)
            if (u == null) ch.setSound(null, null)
            else ch.setSound(u, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            nm.createNotificationChannel(ch)
        }
        return id
    }

    /** Plays the tone once (the picker's preview); Note plays the phone's own notification sound. */
    fun preview(ctx: Context, t: Tone) {
        val u = uri(ctx, t) ?: if (t.id == NOTE) android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION) else null
        u ?: return
        runCatching { android.media.RingtoneManager.getRingtone(ctx, u)?.play() }
    }
}
