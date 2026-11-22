package com.fariin.app.data

import com.fariin.app.crypto.EncMeta

/**
 * The pure part of forwarding a sticker (iOS StickerSend.forward), kept apart from Firestore so it
 * can be unit tested: which stickers need their PNG opened from the source chat, the fields each
 * kind copies, which seal the target chat uses, and the `fwd` source field. The crypto comes in as
 * function parameters.
 */
object StickerForwardRules {
    /** Only a sticker somebody made is a sealed PNG of the source chat; a pack one goes by its name. */
    fun needsSourcePng(s: StickerRef): Boolean = s.packId == StickerFavorites.MINE

    /** The PNG opened (decrypted) from the source chat by [open], or null for a pack sticker. */
    suspend fun sourcePng(s: StickerRef, open: suspend () -> ByteArray): ByteArray? =
        if (needsSourcePng(s)) open() else null

    /** A pack sticker: its public link and its name, nothing sealed. */
    fun packFields(s: StickerRef): HashMap<String, Any> {
        val fields = hashMapOf<String, Any>(
            "type" to "sticker", "stickerPack" to s.packId, "stickerId" to s.stickerId,
            "animated" to s.animated, "width" to 512.0, "height" to 512.0,
            "imageUrl" to StickerGifRules.stickerPublicUrl(s.packId, s.stickerId),
        )
        if (s.emoji.isNotEmpty()) fields["emoji"] = s.emoji
        return fields
    }

    /** A sticker somebody made, written with `uploading` before its sealed PNG is attached. */
    fun mineFields(s: StickerRef, w: Double, h: Double): HashMap<String, Any> {
        val fields = hashMapOf<String, Any>(
            "type" to "sticker", "stickerPack" to s.packId, "stickerId" to s.stickerId,
            "animated" to false, "width" to w, "height" to h, "uploading" to true,
        )
        if (s.emoji.isNotEmpty()) fields["emoji"] = s.emoji
        return fields
    }

    /** The PNG sealed for the target chat: the group seal with its [members], else the 1:1 seal.
     *  A failure is thrown (the caller removes the message), never a plain copy. */
    suspend fun sealPng(png: ByteArray, members: List<String>?,
                        group: suspend (ByteArray, List<String>) -> Pair<ByteArray, EncMeta>,
                        direct: suspend (ByteArray) -> Pair<ByteArray, EncMeta>): Pair<ByteArray, EncMeta> =
        if (members != null) group(png, members) else direct(png)

    /** The forward marks (iOS sealedForwardSource): `forwarded`, and `fwd` sealed by [seal]. A plain
     *  group's seal hands the JSON back, so the map goes. A failed seal drops only `fwd`. */
    suspend fun forwardFields(src: Message.ForwardSource?, seal: suspend (String) -> String): Map<String, Any> {
        val out = HashMap<String, Any>()
        out["forwarded"] = true
        src?.let { s ->
            val j = s.json()
            runCatching { seal(j) }.getOrNull()?.let { out["fwd"] = if (it == j) s.map() else it }
        }
        return out
    }
}
