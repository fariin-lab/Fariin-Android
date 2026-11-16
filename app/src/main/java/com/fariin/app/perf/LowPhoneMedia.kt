package com.fariin.app.perf

/**
 * Moving pictures in a chat on a LOW phone (low-end emulator, 2026-11-15: a group with a wallpaper,
 * five GIFs and five animated stickers drew a new frame almost every vsync, and each one cost the
 * whole screen). Big messengers do the same on cheap phones: stickers hold their frame while the chat
 * slides in or out and while the list scrolls, and only a couple play at once. AVERAGE and HIGH
 * phones are unchanged.
 */
object LowPhoneMedia {
    /** At most this many animated stickers paint at once on a LOW phone; the rest wait their turn. */
    const val LOW_STICKER_SLOTS = 2

    /** How many stickers may play at once; null = no limit. */
    fun stickerSlots(c: PerfClass?): Int? = if (c == PerfClass.LOW) LOW_STICKER_SLOTS else null

    /**
     * Whether a GIF's "still" (drawn under its video, and in the panel grid) must be decoded as one
     * frame. The GIF decoders join every image loader by themselves, so it moved too: a LOW phone
     * (where GIFs autoplay off by default) still ran four GIFs on the main thread. Still on a LOW
     * phone, and on any phone with "Autoplay GIFs" off.
     */
    fun gifStillOnly(c: PerfClass?, autoplayGifs: Boolean): Boolean = c == PerfClass.LOW || !autoplayGifs

    /** True while stickers and GIFs should hold their current frame. */
    fun held(c: PerfClass?, pageMoving: Boolean, scrolling: Boolean): Boolean =
        c == PerfClass.LOW && (pageMoving || scrolling)
}
