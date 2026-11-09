package com.fariin.app.data

/*
 * Chat wallpaper and chat colour, as iOS WallpaperStore.swift + ChatColorStore.swift: the models, the
 * built-in themes and colours with their exact values, and the resolution rules (a chat's own pick,
 * else the Settings pick, else Auto). Pure, so the rules are unit tested; the phone storage is
 * ChatLookStore. Everything is kept on this phone only, never synced (iOS: "local, never synced").
 */

/** iOS ChatWallpaper, with the same stored strings ("none", "g:", "p:", "c:", "b:", "a:"). */
sealed class ChatWallpaper {
    object None : ChatWallpaper()
    data class Gradient(val id: String) : ChatWallpaper()
    data class Photo(val id: String) : ChatWallpaper()
    data class Solid(val hex: Long) : ChatWallpaper()
    /** A legacy built-in id; resolves to a theme (WallpaperPreset.theme). */
    data class Preset(val id: String) : ChatWallpaper()
    /** A moving wallpaper drawn by a shader, an id in AnimatedWallpapers.all. Older builds read "a:" as none. */
    data class Animated(val id: String) : ChatWallpaper() {
        val wallpaper: AnimatedWallpaper? get() = AnimatedWallpapers.find(id)
    }

    val stored: String get() = when (this) {
        None -> "none"
        is Gradient -> "g:$id"
        is Photo -> "p:$id"
        is Solid -> "c:" + "%06X".format(hex)
        is Preset -> "b:$id"
        is Animated -> "a:$id"
    }

    /** The theme this draws, for a gradient or a legacy preset. */
    val theme: WallpaperGradient? get() = when (this) {
        is Gradient -> ChatWallpapers.gradient(id)
        is Preset -> PRESET_THEMES[id]?.let { ChatWallpapers.gradient(it) }
        else -> null
    }

    /** The bubble colour Auto uses on this wallpaper; a photo, a plain colour and None have none. */
    val pairedColor: ChatColorSpec? get() = theme?.autoColor

    companion object {
        fun parse(s: String?): ChatWallpaper = when {
            s == null -> None
            s.startsWith("g:") -> Gradient(s.drop(2))
            s.startsWith("p:") -> Photo(s.drop(2))
            s.startsWith("c:") -> Solid(s.drop(2).toLongOrNull(16) ?: 0)
            s.startsWith("b:") -> Preset(s.drop(2))
            // An id this build does not know (a newer phone's) reads as none, as iOS.
            s.startsWith("a:") -> s.drop(2).let { if (AnimatedWallpapers.find(it) != null) Animated(it) else None }
            else -> None
        }

        private val PRESET_THEMES = mapOf(
            "wp-paper" to "sunset", "wp-ocean" to "ocean", "wp-forest" to "forest",
            "wp-dusk" to "dusk", "wp-rose" to "rose", "wp-graphite" to "mono",
        )
    }
}

/** iOS WallpaperGradient: four corners per appearance (top-left, top-right, bottom-left, bottom-right). */
data class WallpaperGradient(
    val id: String,
    val name: String,
    val lightCorners: List<Long>,
    val darkCorners: List<Long>,
    val tint: Long,
    val bubbleHex: Long,
    /** The doodle sheet over the colour (the nine gradients are plain). */
    val pattern: Boolean = true,
    val paired: ChatColorSpec? = null,
) {
    val autoColor: ChatColorSpec get() = paired ?: ChatColorSpec(listOf(bubbleHex))

    /** The nine mesh colours: corners, edges as the blend of two corners, centre of all four. */
    fun meshColors(dark: Boolean): List<Long> {
        val c = if (dark) darkCorners else lightCorners
        if (c.size != 4) return c
        val (tl, tr, bl, br) = c
        return listOf(
            tl, mix(tl, tr), tr,
            mix(tl, bl), mix(mix(tl, br), mix(tr, bl)), mix(tr, br),
            bl, mix(bl, br), br,
        )
    }

    /** One flat colour for the theme: the blend of its four corners. */
    fun flat(dark: Boolean): Long {
        val c = if (dark) darkCorners else lightCorners
        if (c.size != 4) return c.firstOrNull() ?: 0
        return mix(mix(c[0], c[1]), mix(c[2], c[3]))
    }

    companion object {
        /** The mesh's interior point, a little up and right of centre (iOS meshPoints). */
        const val CENTRE_X = 0.58f
        const val CENTRE_Y = 0.42f
    }
}

/** Integer RGB average of two colours, as iOS WallpaperGradient.mix. */
fun mix(a: Long, b: Long): Long {
    val r = (((a shr 16) and 0xFF) + ((b shr 16) and 0xFF)) / 2
    val g = (((a shr 8) and 0xFF) + ((b shr 8) and 0xFF)) / 2
    val bl = ((a and 0xFF) + (b and 0xFF)) / 2
    return (r shl 16) or (g shl 8) or bl
}

object ChatWallpapers {
    /** iOS ChatWallpapers.all, same order and values: the nine gradients, then the six patterned. */
    val all: List<WallpaperGradient> = listOf(
        WallpaperGradient("g-blaze", "Blaze", listOf(0xF3DC47, 0xF2CD46, 0xE54F41, 0xE44040),
            listOf(0xE6CA0F, 0xDEB80F, 0x9F2311, 0x971111), 0xEC8E44, 0xEC8E44, false, ChatColorSpec(listOf(0xE57C00, 0x5E0000))),
        WallpaperGradient("g-noir", "Noir", listOf(0x434356, 0x434356, 0xA5A5B6, 0xA5A5B6),
            listOf(0x121217, 0x121217, 0x535365, 0x535365), 0x747486, 0x747486, false, ChatColorSpec(listOf(0x2C2C3A, 0x787891))),
        WallpaperGradient("g-heatmap", "Heatmap", listOf(0xE4384B, 0xF53844, 0x42378F, 0x533788),
            listOf(0xAA2131, 0xB6202A, 0x362D76, 0x422C6F), 0x9C386A, 0x9C386A, false, ChatColorSpec(listOf(0xF65560, 0x442CED))),
        WallpaperGradient("g-aqua", "Aqua", listOf(0x0093E9, 0x0093E9, 0x80D0C7, 0x80D0C7),
            listOf(0x006199, 0x006199, 0x3FAB9F, 0x3FAB9F), 0x40B2D8, 0x40B2D8, false, ChatColorSpec(listOf(0x004066, 0x32867D))),
        WallpaperGradient("g-iridescent", "Iridescent", listOf(0xDC7DE6, 0xED82E6, 0x3753E6, 0x4858E6),
            listOf(0xA010A2, 0xAF0EA4, 0x142790, 0x232592), 0x926AE6, 0x926AE6, false, ChatColorSpec(listOf(0xEC13DD, 0x1B36C6))),
        WallpaperGradient("g-monstera", "Monstera", listOf(0x65CDAC, 0x65CDAC, 0x0A995A, 0x0A995A),
            listOf(0x236C54, 0x236C54, 0x065632, 0x065632), 0x38B383, 0x38B383, false, ChatColorSpec(listOf(0x2F9373, 0x077343))),
        WallpaperGradient("g-bliss", "Bliss", listOf(0xD8E1FA, 0xD8E1FA, 0xD6A4B5, 0xD6A4B5),
            listOf(0x8AA1E0, 0x8AA1E0, 0xB65D7B, 0xB65D7B), 0xD7C2D8, 0xD7C2D8, false, ChatColorSpec(listOf(0x6281D5, 0x974460))),
        WallpaperGradient("g-sky", "Sky", listOf(0xD8EBFD, 0xD8EBFD, 0x9DCCFB, 0x9DCCFB),
            listOf(0xA0C4E9, 0xA0C4E9, 0x478ACD, 0x478ACD), 0xBBDCFC, 0xBBDCFC, false, ChatColorSpec(listOf(0x498FD4, 0x2C66A0))),
        WallpaperGradient("g-peach", "Peach", listOf(0xFFE0BD, 0xFFE5C2, 0xFCAC92, 0xFCB197),
            listOf(0xE5B983, 0xEAC28A, 0xBB6144, 0xC06A4B), 0xFEC9AA, 0xFEC9AA, false, ChatColorSpec(listOf(0xDB7133, 0x911231))),
        WallpaperGradient("sunset", "Sunset", listOf(0xFFD9C2, 0xFFE7B0, 0xFFC6D4, 0xEBC9F0),
            listOf(0x3C2119, 0x372A12, 0x361A28, 0x2C1C33), 0xF08A5D, 0xF08A5D),
        WallpaperGradient("ocean", "Ocean", listOf(0xBEE3FF, 0xC6F1EA, 0xD3DCFF, 0xAFDDF6),
            listOf(0x12314F, 0x113638, 0x1A2A55, 0x0F2D46), 0x3DA1FD, 0x2E8BF0),
        WallpaperGradient("dusk", "Dusk", listOf(0xE2CCFA, 0xF6CDEA, 0xCED4FA, 0xEBD2FF),
            listOf(0x2C1F4E, 0x3E1F44, 0x22224E, 0x341C4A), 0x9B6DF3, 0x8A5CF0),
        WallpaperGradient("forest", "Forest", listOf(0xCFEFCB, 0xE7F4C2, 0xC4EEDD, 0xDCF2CE),
            listOf(0x143A25, 0x1E3C1B, 0x113A33, 0x1A3A1F), 0x34C76F, 0x1FA85A),
        WallpaperGradient("mono", "Graphite", listOf(0xEFEFF2, 0xE6E6EB, 0xF2F1F5, 0xE9E8EE),
            listOf(0x18181B, 0x131316, 0x1C1C20, 0x141417), 0x8E8E93, 0x3A3A3C),
        WallpaperGradient("rose", "Rose", listOf(0xFFD2DB, 0xFFE2CE, 0xF9CBE7, 0xFFD8E9),
            listOf(0x3E1E2B, 0x40261E, 0x351A33, 0x3A1F3C), 0xF06792, 0xE84D86),
    )

    fun gradient(id: String): WallpaperGradient? = all.firstOrNull { it.id == id }

    /** iOS WallpaperColors.all: the plain background colours. */
    val colors: List<Long> = listOf(
        0x0A0A0A, 0x1C1C1E, 0x2C2C2E, 0x3A3A3C,
        0x0E2A47, 0x123B24, 0x3A1E2E, 0x2A2340,
        0xE9E9EE, 0xD6ECFF, 0xDCF3D8, 0xFFE1E6,
    )

    /** iOS hexIsDark: the tick on a plain colour tile is white on a dark one. */
    fun isDark(hex: Long): Boolean {
        val r = (hex shr 16) and 0xFF; val g = (hex shr 8) and 0xFF; val b = hex and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b < 140
    }
}

/** iOS ChatColorSpec: one colour = solid, two = a gradient. Stored "s:RRGGBB" or "g:RRGGBB,RRGGBB". */
data class ChatColorSpec(val colors: List<Long>) {
    val isGradient: Boolean get() = colors.size >= 2
    val stored: String get() = (if (isGradient) "g:" else "s:") + colors.joinToString(",") { "%06X".format(it) }

    /** The gradient's direction in the reference spec degrees (180 = first colour on top). */
    val angleDegrees: Double get() = when (colors) {
        listOf(0xE57C00L, 0x5E0000L) -> 168.0
        listOf(0xF65560L, 0x442CEDL), listOf(0xEC13DDL, 0x1B36C6L), listOf(0xDB7133L, 0x911231L) -> 192.0
        else -> 180.0
    }

    companion object {
        fun parse(s: String?): ChatColorSpec? {
            if (s == null || s.length <= 2) return null
            val hexes = s.drop(2).split(",").mapNotNull { it.toLongOrNull(16) }
            return if (hexes.isEmpty()) null else ChatColorSpec(hexes)
        }

        /** HSB to RGB, brightness fixed at 0.86 for the Custom Color editor (iOS solid/gradient). */
        fun hsb(hue: Double, saturation: Double, brightness: Double = 0.86): Long {
            val h = (hue.coerceIn(0.0, 1.0) * 6.0).let { if (it >= 6.0) 0.0 else it }
            val s = saturation.coerceIn(0.0, 1.0); val v = brightness.coerceIn(0.0, 1.0)
            val i = h.toInt(); val f = h - i
            val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
            val (r, g, b) = when (i) {
                0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t)
                3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q)
            }
            return ((r * 255).toLong() shl 16) or ((g * 255).toLong() shl 8) or (b * 255).toLong()
        }
    }
}

object ChatColors {
    /** iOS ChatColors.presets: Ultramarine, twelve solids, nine gradients, in this order. */
    val presets: List<ChatColorSpec> = listOf(
        listOf(0x0552F0L, 0x2C6BEDL),
        listOf(0xCF163EL), listOf(0xC73F0AL), listOf(0x6F6A58L), listOf(0x3B7845L),
        listOf(0x1D8663L), listOf(0x077D92L), listOf(0x336BA3L), listOf(0x6058CAL),
        listOf(0x9932C8L), listOf(0xAA377AL), listOf(0x8F616AL), listOf(0x71717FL),
        listOf(0xE57C00L, 0x5E0000L), listOf(0x2C2C3AL, 0x787891L), listOf(0xF65560L, 0x442CEDL),
        listOf(0x004066L, 0x32867DL), listOf(0xEC13DDL, 0x1B36C6L), listOf(0x2F9373L, 0x077343L),
        listOf(0x6281D5L, 0x974460L), listOf(0x498FD4L, 0x2C66A0L), listOf(0xDB7133L, 0x911231L),
    ).map { ChatColorSpec(it) }
}

/**
 * The resolution rules over the stored strings, keyed as iOS keys them: "wallpaper.<cid>",
 * "wallpaper.__default__", "chatColor.<cid>", "chatColor.__default__".
 */
object ChatLookRules {
    const val WALL_DEFAULT = "wallpaper.__default__"
    const val COLOR_DEFAULT = "chatColor.__default__"
    fun wallKey(cid: String) = "wallpaper.$cid"
    fun colorKey(cid: String) = "chatColor.$cid"

    /**
     * iOS wallpaper(for:): the Fariin chat never has one; else a group's shared wallpaper [group]
     * (it beats this phone's own pick, iOS groupResolved); else the chat's own, else the Settings one.
     */
    fun wallpaper(cid: String, read: (String) -> String?, group: ChatWallpaper? = null): ChatWallpaper {
        if (cid == OfficialChannel.CID) return ChatWallpaper.None
        group?.let { return it }
        // Owner 2026-11-09 (iOS e651ae8d8): the Settings wallpaper is for one-to-one chats only. A
        // group or channel shows a wallpaper only when set from inside it, else the plain page.
        if (com.fariin.app.crypto.Wire.isGroupCid(cid)) return ChatWallpaper.parse(read(wallKey(cid)))
        return ChatWallpaper.parse(read(wallKey(cid)) ?: read(WALL_DEFAULT))
    }

    /** The key holding a group's shared `wallpaper` field as this phone last read it. */
    fun groupKey(cid: String) = "wallpaper.group.$cid"

    /**
     * iOS groupResolved: the group's stored value as this phone can draw it, or null to use the
     * phone's own pick. "u:<link>" is a photo fetched once into the file named [photoId]; until it
     * is on the phone the chat keeps [lastShown] (null = its own pick). A "p:" value names a photo
     * on someone else's phone, so it reads as plain.
     */
    fun groupWallpaper(raw: String?, hasPhoto: (String) -> Boolean, lastShown: ChatWallpaper? = null): ChatWallpaper? {
        if (raw == null) return null
        val link = photoLink(raw) ?: return if (raw.startsWith("p:") || raw == "photo") ChatWallpaper.None else ChatWallpaper.parse(raw)
        val id = photoId(link)
        return if (hasPhoto(id)) ChatWallpaper.Photo(id) else lastShown
    }

    /** The download link of a shared photo value ("u:<link>"), else null. */
    fun photoLink(raw: String?): String? = raw?.takeIf { it.startsWith("u:") }?.drop(2)

    /** iOS sharedPhotoId: a stable local id for a group photo link, the same on every launch. */
    fun photoId(link: String): String = "grp-" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(link.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    fun defaultWallpaper(read: (String) -> String?): ChatWallpaper = ChatWallpaper.parse(read(WALL_DEFAULT))

    private fun stored(key: String, read: (String) -> String?): ChatColorSpec? =
        read(key).let { if (it == "__none__") null else ChatColorSpec.parse(it) }

    /** The colour chosen in Settings; null = Auto. */
    fun globalChosen(read: (String) -> String?): ChatColorSpec? = stored(COLOR_DEFAULT, read)

    /** Auto for the Settings colour itself: the Settings wallpaper's pairing. */
    fun globalAuto(read: (String) -> String?): ChatColorSpec? = defaultWallpaper(read).pairedColor

    /** What a chat without its own wallpaper or colour draws. */
    fun globalColor(read: (String) -> String?): ChatColorSpec? = globalChosen(read) ?: globalAuto(read)

    /**
     * iOS ChatColorStore.color(for:): the chat's own colour; else Auto, which asks the chat's OWN
     * wallpaper's pairing (a photo or plain one stops at the Settings colour), else the Settings
     * colour, else the Settings wallpaper's pairing. null = the app's default blue.
     */
    fun color(cid: String, read: (String) -> String?, group: ChatWallpaper? = null): ChatColorSpec? {
        stored(colorKey(cid), read)?.let { return it }
        // A group's shared wallpaper counts as the chat's own (iOS hasOverride).
        if (cid != OfficialChannel.CID && group != null) return group.pairedColor ?: globalChosen(read)
        if (cid != OfficialChannel.CID && read(wallKey(cid)) != null) {
            return wallpaper(cid, read).pairedColor ?: globalChosen(read)
        }
        // A group or channel never takes the Settings WALLPAPER's paired colour (its wallpaper is not
        // there); a colour picked in Settings still applies everywhere (owner 2026-11-09).
        if (com.fariin.app.crypto.Wire.isGroupCid(cid)) return globalChosen(read)
        return globalColor(read)
    }

    /** The one flat colour a bubble draws: a gradient's two stops blended. */
    fun flatHex(spec: ChatColorSpec): Long =
        if (spec.colors.size >= 2) mix(spec.colors[0], spec.colors[1]) else spec.colors.firstOrNull() ?: 0x3A76F0
}
