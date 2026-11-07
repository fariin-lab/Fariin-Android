package com.fariin.app.ui.thread

import android.content.Context
import com.fariin.app.data.GifFavorites
import com.fariin.app.data.Message
import com.fariin.app.data.StickerFavorites
import com.fariin.app.data.StickerRef
import com.fariin.app.media.ChatMedia
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * The message menu's "Save to Favorites" (sticker) and "Add to Favorites" (GIF) rows, after iOS
 * StickerSend.favoriteTitle / toggleFavorite and GifFavorites.menuTitle / toggle. The work outlives
 * the menu, which closes as the row is tapped, so it runs on a scope of its own.
 */
internal object FavoriteToggles {
    private val scope = MainScope()
    private const val COULD_NOT_SAVE =
        "Couldn't save this sticker. Your phone may be low on storage; free some space and try again."

    private fun sticker(m: Message): StickerRef? {
        val pack = m.stickerPack ?: return null
        val id = m.stickerId ?: return null
        return StickerRef(pack, id, m.stickerEmoji ?: "", m.stickerAnimated)
    }

    /** The sticker row's title, or null when the message is not a sticker. */
    fun stickerTitle(m: Message): String? {
        if (m.deleted) return null
        val s = sticker(m) ?: return null
        return if (StickerFavorites.isFavorite(s)) "Remove from Favorites" else "Save to Favorites"
    }

    /**
     * Saves or removes, then shows the confirmation. A built-in sticker is kept by name; one somebody
     * made is kept as its own PNG copy under the message's sticker id, so the menu then reads Remove.
     */
    fun toggleSticker(m: Message, media: ChatMedia, notice: (String) -> Unit) {
        val s = sticker(m) ?: return notice(COULD_NOT_SAVE)
        scope.launch {
            val words = runCatching {
                when {
                    StickerFavorites.isFavorite(s) -> { StickerFavorites.toggle(s); "Removed from Favorites" }
                    s.packId != StickerFavorites.MINE -> { StickerFavorites.toggle(s); "Saved to Favorites" }
                    else -> {
                        val png = StickerFavorites.png(s.stickerId)?.readBytes()
                            ?: (m.localPath ?: m.imageUrl)?.let { media.file(it, m.enc)?.readBytes() }
                        if (png == null || png.isEmpty()) COULD_NOT_SAVE
                        else { StickerFavorites.addMine(png, s.emoji, keepingId = s.stickerId); "Saved to Favorites" }
                    }
                }
            }.getOrDefault(COULD_NOT_SAVE)
            notice(words)
        }
    }

    fun gifTitle(ctx: Context, me: String, m: Message): String? = GifFavorites.menuTitle(ctx, me, m)

    fun toggleGif(ctx: Context, me: String, m: Message, notice: (String) -> Unit) = notice(GifFavorites.toggle(ctx, me, m))
}
