package com.fariin.app.data

/**
 * Bubble styles, stage one (iOS BubbleStyles.swift, owner 2026-11-06). The sender picks a style and
 * every text message they send carries its plain id as `bs`, so both sides draw that bubble in the
 * style's colours with the style's own text colour. An unknown id, or none, draws the normal bubble.
 *
 * Colours are copied from iOS exactly. One colour is a solid fill, two a top-to-bottom gradient.
 * Soft Glass is the system blur on iOS; here it is its colour, partly see-through (see [Look.glass]).
 */
object BubbleStyles {
    /** Shown (owner 2026-11-07, lifting the 2026-11-06 hide). Off: every message, mine or others',
     *  draws as a normal bubble, nothing is sent with a style and the Settings row is gone. */
    const val SHOWN = true

    data class Look(
        /** 0xRRGGBB, one = solid, two = gradient from top to bottom. */
        val fill: List<Long>,
        /** 0xRRGGBB, the words, links, time and ticks. */
        val text: Long,
        /** Soft Glass: drawn as its colour with some of the background showing through. */
        val glass: Boolean = false,
    )

    data class Style(
        val id: String,
        val name: String,
        /** A Fariin Plus style: only the Fariin team may pick it until Plus is sold. */
        val plus: Boolean,
        val light: Look,
        val dark: Look,
    ) {
        fun look(dark: Boolean): Look = if (dark) this.dark else light
    }

    val all: List<Style> = listOf(
        // "pink" WAS "strawberry" until 2026-11-07 (iOS the same day): the picture set owns that id.
        Style("pink", "Strawberry Pink", false,
            Look(listOf(0xFFC9D6), 0x4A1020), Look(listOf(0x7E2F49), 0xFFE3EA)),
        Style("lavender", "Dreamy Lavender", false,
            Look(listOf(0xE6D8FF, 0xCDB6FF), 0x2E1A55), Look(listOf(0x4B3577, 0x6A4BA3), 0xF1E9FF)),
        Style("peach", "Peach Blossom", false,
            Look(listOf(0xFFDCC8), 0x4A2414), Look(listOf(0x7A4632), 0xFFE8DC)),
        Style("mint", "Mint Cutie", false,
            Look(listOf(0xCBF2DF), 0x123D2C), Look(listOf(0x2F6B54), 0xE2FFF3)),
        Style("cotton", "Cotton Candy", true,
            Look(listOf(0xFFC6E3, 0xBFE3FF), 0x2A2440), Look(listOf(0x7A3B66, 0x2F5D86), 0xF5EEFF)),
        Style("heart", "Heart Love", true,
            Look(listOf(0xFF9DBB), 0x4A0A22), Look(listOf(0xA8325C), 0xFFE6EF)),
        Style("kawaii", "Kawaii", true,
            Look(listOf(0xFFEBA8), 0x4A3A05), Look(listOf(0x76611E), 0xFFF6D6)),
        Style("glass", "Soft Glass", true,
            Look(listOf(0xFFFFFF), 0x1C1C1E, glass = true), Look(listOf(0x2C2C2E), 0xF2F2F7, glass = true)),
    )

    fun find(id: String?): Style? = if (id.isNullOrEmpty()) null else all.firstOrNull { it.id == id }

    /**
     * The colour styles the picker lists. A picture style wins a shared id everywhere (picture ids
     * are the file names, fixed forever, SPEC 8); the pink colour moved to its own id for that.
     */
    val colours: List<Style> get() = all.filter { BubbleArt.find(it.id) == null }

    /** Any style this build can draw, picture or colour. */
    fun known(id: String?): Boolean = BubbleArt.find(id) != null || find(id) != null

    /** The name a picker row shows; "Default" for none. */
    fun name(id: String?): String = BubbleArt.find(id)?.name ?: find(id)?.name ?: "Default"

    // ---- My choice (iOS bubbleStyle.v1 / bubbleStyle.decor.v1, one for all chats) ----

    private const val PREFS = "fariin_bubble_style"
    const val KEY = "bubbleStyle.v1"
    const val DECOR_KEY = "bubbleStyle.decor.v1"
    @Volatile private var app: android.content.Context? = null
    fun init(context: android.content.Context) { if (app == null) app = context.applicationContext }

    /** Bumped on Save, so a Settings row naming the style reads it again. */
    val version = androidx.compose.runtime.mutableIntStateOf(0)

    /** The style I picked, or null for the normal bubble. */
    fun current(): String? = app?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        ?.getString(KEY, null)?.takeIf { it.isNotEmpty() }

    /** The colour styles' corner decorations, on by default (iOS). Picture styles have their own art. */
    fun decorations(): Boolean = app?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        ?.getBoolean(DECOR_KEY, true) ?: true

    fun save(id: String?, decor: Boolean) {
        val p = app?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) ?: return
        p.edit().apply { if (id == null) remove(KEY) else putString(KEY, id) }.putBoolean(DECOR_KEY, decor).apply()
        version.intValue++
    }

    /** May I pick it: every picture style; a colour style unless it is Plus (not on sale yet). */
    fun mayUse(id: String?): Boolean = id == null || BubbleArt.find(id) != null || find(id)?.plus == false

    // ---- One chat's own style (owner 2026-11-07), as the per-chat wallpaper ----
    // `bubbleStyle.chat.<cid>`: a style id, or [NONE] for "this chat has no style" (opts out of the
    // Settings one). No key = the chat follows the Settings style.

    const val NONE = "__none__"
    fun chatKey(cid: String) = "bubbleStyle.chat.$cid"

    /** Sign-out: every chat's own style goes (they name the old account's chats); my pick stays. */
    fun wipeChats(c: android.content.Context) {
        val p = c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val keys = p.all.keys.filter { it.startsWith("bubbleStyle.chat.") }
        if (keys.isNotEmpty()) p.edit().apply { keys.forEach { remove(it) } }.apply()
        version.intValue++
    }

    /** Pure: this chat's stored value (null = no key) over the Settings style. */
    fun resolve(chatRaw: String?, global: String?): String? = when {
        chatRaw == null -> global?.takeIf { it.isNotEmpty() }
        chatRaw == NONE || chatRaw.isEmpty() -> null
        else -> chatRaw
    }

    /** The value to store for a pick in one chat: a style, or [NONE] for the plain bubble. */
    fun chatValue(id: String?): String = id?.takeIf { it.isNotEmpty() } ?: NONE

    private fun chatRaw(cid: String): String? =
        app?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)?.getString(chatKey(cid), null)

    /** The style this chat's bubbles of mine use: its own, else the Settings one, else none. */
    fun forChat(cid: String?): String? = if (cid.isNullOrEmpty()) current() else resolve(chatRaw(cid), current())

    /** This chat's own pick; null = plain bubble here (an explicit none, never the Settings one). */
    fun saveForChat(cid: String, id: String?) {
        val p = app?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) ?: return
        p.edit().putString(chatKey(cid), chatValue(id)).apply()
        version.intValue++
    }

    /** What a text I send now in [cid] carries as `bs` (null = nothing). */
    fun forSending(cid: String?): String? = if (!SHOWN) null else forChat(cid)?.takeIf { known(it) && mayUse(it) }

    /** `bsd` beside it: a colour style's decorations chosen (iOS sends it with any style). */
    fun decorForSending(id: String?): Boolean = id != null && decorations()

    /** The `bs` field as it arrives: a short plain string, else nothing (iOS: 1 to 32 characters). */
    fun parseId(raw: Any?): String? = (raw as? String)?.takeIf { it.isNotEmpty() && it.length <= 32 }

    /** The `bsd` field: the sender chose the style's decorations too. Only a real true counts. */
    fun parseDecor(raw: Any?): Boolean = raw == true

    // ---- Decorations, stage two (iOS BubbleDecor.swift, owner 2026-11-06) ----
    // Small art at a styled bubble's corners. Only the LAST bubble of a run carries them, mostly on
    // the bottom corners so they hang into the gap below; a top corner only on the run's first bubble.

    enum class Corner { TOP_LEADING, TOP_TRAILING, BOTTOM_LEADING, BOTTOM_TRAILING }

    /** What is drawn. Every one is drawn in code (ui/thread/BubbleStyles.kt), no artwork files. */
    enum class Glyph { HEART, SPARKLE, SPARKLES, BLOSSOM, LEAF, CLOUD, STAR, BOW, STRAWBERRY }

    data class Decor(
        val corner: Corner,
        val glyph: Glyph,
        /** Size in dp (iOS points). */
        val size: Float,
        val light: Long,
        val dark: Long,
        /** How far the art's centre sits out from the corner point, in dp. */
        val out: Float = 4f,
        /** Turn in radians, positive = clockwise. */
        val rotation: Float = 0f,
    )

    /** One decoration resolved for one bubble: the real corner (mirrored for theirs) and colour. */
    data class Placed(val corner: Corner, val glyph: Glyph, val size: Float, val color: Long, val out: Float, val rotation: Float)

    /** The style's set, copied from iOS (corners for MY bubble). */
    fun decor(styleId: String): List<Decor> = when (styleId) {
        "pink" -> listOf(
            Decor(Corner.BOTTOM_TRAILING, Glyph.STRAWBERRY, 22f, 0xE8364F, 0xFF5A72, rotation = 0.25f),
            Decor(Corner.TOP_LEADING, Glyph.HEART, 13f, 0xFF6F91, 0xFF8FAB, rotation = -0.3f))
        "lavender" -> listOf(
            Decor(Corner.BOTTOM_TRAILING, Glyph.SPARKLES, 17f, 0x8E6CF0, 0xD6C6FF),
            Decor(Corner.TOP_LEADING, Glyph.SPARKLE, 11f, 0xA98BFF, 0xE6DBFF))
        "peach" -> listOf(
            Decor(Corner.BOTTOM_TRAILING, Glyph.BLOSSOM, 19f, 0xFF8A65, 0xFFB59A, rotation = 0.2f),
            Decor(Corner.BOTTOM_LEADING, Glyph.LEAF, 13f, 0x7CB86F, 0x9AD68C, rotation = -0.5f))
        "mint" -> listOf(
            Decor(Corner.BOTTOM_TRAILING, Glyph.LEAF, 17f, 0x2FA77A, 0x7FE0B8, rotation = 0.4f),
            Decor(Corner.TOP_LEADING, Glyph.SPARKLE, 11f, 0x45C493, 0xB6F5DA))
        "cotton" -> listOf(
            Decor(Corner.BOTTOM_TRAILING, Glyph.CLOUD, 20f, 0xA9D4FF, 0xC9E4FF),
            Decor(Corner.TOP_LEADING, Glyph.STAR, 12f, 0xFF9ECF, 0xFFC2E2, rotation = 0.3f))
        "heart" -> listOf(
            Decor(Corner.BOTTOM_TRAILING, Glyph.HEART, 18f, 0xFF3B6E, 0xFF6F91, rotation = 0.3f),
            Decor(Corner.TOP_LEADING, Glyph.HEART, 11f, 0xFF6F91, 0xFF9DB6, rotation = -0.35f))
        "kawaii" -> listOf(
            Decor(Corner.TOP_TRAILING, Glyph.BOW, 24f, 0xFF6FA3, 0xFF8FB8, out = 3f, rotation = 0.15f),
            Decor(Corner.BOTTOM_LEADING, Glyph.STAR, 13f, 0xFFC83D, 0xFFD866, rotation = -0.3f),
            Decor(Corner.BOTTOM_TRAILING, Glyph.SPARKLES, 14f, 0xFFB020, 0xFFD866))
        "glass" -> listOf(Decor(Corner.BOTTOM_TRAILING, Glyph.SPARKLE, 13f, 0x8E8E93, 0xD1D1D6))
        else -> emptyList()
    }

    /**
     * The decorations one bubble draws, or none. They show only when the sender chose them (`bsd`),
     * on a styled text bubble, on the LAST bubble of its run, and not on a failed send (the red
     * retry mark sits there). Theirs mirrors mine so the art stays on the outer side. A top corner
     * only on the run's first bubble, so it never lands on the bubble above. [besideAvatar]: their
     * bubble in a group, whose bottom-leading corner is next to the sender's face; nothing there.
     */
    fun placed(styleId: String?, decorOn: Boolean, isMe: Boolean, dark: Boolean, first: Boolean, last: Boolean,
               failed: Boolean, besideAvatar: Boolean): List<Placed> {
        if (styleId == null || !decorOn || !last || failed) return emptyList()
        return decor(styleId).mapNotNull { d ->
            val c = if (isMe) d.corner else when (d.corner) {
                Corner.TOP_LEADING -> Corner.TOP_TRAILING
                Corner.TOP_TRAILING -> Corner.TOP_LEADING
                Corner.BOTTOM_LEADING -> Corner.BOTTOM_TRAILING
                Corner.BOTTOM_TRAILING -> Corner.BOTTOM_LEADING
            }
            if (!first && (c == Corner.TOP_LEADING || c == Corner.TOP_TRAILING)) return@mapNotNull null
            if (besideAvatar && c == Corner.BOTTOM_LEADING) return@mapNotNull null
            Placed(c, d.glyph, d.size, if (dark) d.dark else d.light, d.out, if (isMe) d.rotation else -d.rotation)
        }
    }

    /** Where the art's centre goes, in dp, inside a bubble [w] by [h] dp (iOS applyDecor). */
    fun centre(p: Placed, w: Float, h: Float): Pair<Float, Float> = when (p.corner) {
        Corner.TOP_LEADING -> (4f - p.out) to (2f - p.out)
        Corner.TOP_TRAILING -> (w - 4f + p.out) to (2f - p.out)
        Corner.BOTTOM_LEADING -> (4f - p.out) to (h - 2f + p.out)
        Corner.BOTTOM_TRAILING -> (w - 4f + p.out) to (h - 2f + p.out)
    }
}
