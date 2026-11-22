package com.fariin.app.data

import com.fariin.app.crypto.EncMeta
import com.fariin.app.crypto.Wire

/**
 * Songs and playlists (iOS MusicPlayback.swift + Message.tracks, 2026-11-21).
 *
 * A song is a plain `type: "file"` message whose file name is audio; its cover is the file's sealed
 * preview tile (`thumbUrl` / `thumbEnc`). A PLAYLIST is the same file message (its own file is the
 * first song) with every song in `album` as `{kind: "audio", fileUrl, enc, fileName, fileNameEnc?,
 * fileSize, title?, artist?}` and the first item carrying the sealed `listName`. Those items have no
 * `imageUrl`, so the photo album reader drops them. One audio item alone is a single song renamed.
 */
object MusicRules {
    /** iOS MusicFiles.maxTracks. */
    const val MAX_TRACKS = 10

    /** One song of a playlist; [url] is empty while it is still going up. */
    data class Track(
        val url: String,
        val enc: EncMeta?,
        val fileName: String,
        val fileSize: Long,
        /** Typed by the sender (opened); null = the song's own tags, else its file name. */
        val title: String? = null,
        val artist: String? = null,
    )

    data class Parsed(
        val tracks: List<Track>? = null,
        val listName: String? = null,
        val songTitle: String? = null,
        val songArtist: String? = null,
    )

    /** The playlist or renamed song inside [album] (raw Firestore). [open] opens a value sealed by the author. */
    fun parse(type: String?, album: Any?, open: (String) -> String): Parsed {
        if (type != "file") return Parsed()
        val items = (album as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: return Parsed()
        fun opened(v: Any?): String? {
            val s = v as? String ?: return null
            if (s.isEmpty()) return null
            val clear = open(s)
            return clear.takeUnless { it.isEmpty() || it == Wire.NOT_READY || it == Wire.LOCKED }
        }
        val audio = items.filter { it["kind"] == "audio" }
        val songs = audio.map { d ->
            Track(
                url = d["fileUrl"] as? String ?: "",
                enc = EncMeta.fromMap(d["enc"] as? Map<*, *>),
                fileName = opened(d["fileNameEnc"]) ?: d["fileName"] as? String ?: "Audio",
                fileSize = (d["fileSize"] as? Number)?.toLong() ?: 0L,
                title = opened(d["title"]),
                artist = opened(d["artist"]),
            )
        }
        return when {
            songs.size >= 2 -> Parsed(tracks = songs,
                listName = (audio.first()["listName"] as? String)?.let { opened(it) ?: "" })
            songs.size == 1 -> Parsed(songTitle = songs[0].title, songArtist = songs[0].artist)
            else -> Parsed()
        }
    }

    /** iOS MusicTrackRef.id: the first song is the message id (the document's own cache), then "-t1"... */
    fun trackId(messageId: String, index: Int): String = if (index == 0) messageId else "$messageId-t$index"

    /** The file name without its extension: the title before the tags are known. */
    fun plainTitle(fileName: String): String = fileName.substringBeforeLast('.', fileName).ifEmpty { fileName }

    /** One song to play: what the bubble, the player and the lock screen share. */
    data class Ref(
        val id: String,
        val messageId: String,
        val url: String,
        val enc: EncMeta?,
        val fileName: String,
        val fileSize: Long,
        val title: String? = null,
        val artist: String? = null,
    ) {
        val plainTitle: String get() = MusicRules.plainTitle(fileName)
    }

    /** A playlist: two songs or more, in a file message. */
    fun isPlaylist(type: String?, tracks: List<Track>?): Boolean = type == "file" && (tracks?.size ?: 0) >= 2

    /**
     * What plays from this file message: every song of a playlist, or the one song; null when it is a
     * document. [localPath] is my own copy of a single song still going up.
     */
    fun refs(id: String, type: String?, tracks: List<Track>?, fileName: String?, fileUrl: String?, enc: EncMeta?,
             fileSize: Long?, songTitle: String?, songArtist: String?, localPath: String? = null): List<Ref>? {
        if (isPlaylist(type, tracks)) return tracks!!.mapIndexed { i, t ->
            Ref(trackId(id, i), id, t.url, t.enc, t.fileName, t.fileSize, t.title, t.artist)
        }
        if (type != "file" || !com.fariin.app.media.MediaRules.isSong(fileName)) return null
        return listOf(Ref(id, id, localPath ?: fileUrl.orEmpty(), enc, fileName ?: "Audio", fileSize ?: 0L, songTitle, songArtist))
    }

    /** The playlist's name: `listName`, else (sent before the send sheet) the text; a sentinel is no name. */
    fun playlistName(listName: String?, text: String): String {
        val n = (listName ?: text).trim()
        return if (n == Wire.NOT_READY || n == Wire.LOCKED) "" else n
    }

    /** The words drawn under the music: the caption. An old playlist with no `listName` used its text as the name. */
    fun caption(isPlaylist: Boolean, listName: String?, text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t == Wire.NOT_READY || t == Wire.LOCKED) return null
        if (isPlaylist && listName == null) return null
        return text
    }

    /**
     * The player bar's second line for a voice note (iOS PlayerAccessory.subtitle): "Voice message ·
     * <sender>", or "Voice message" alone when there is no sender or the sender already is the title
     * (a one-to-one chat with the other person). [sender] is "You" for my own note.
     */
    fun noteSubtitle(sender: String, title: String): String =
        if (sender.isEmpty() || sender == title) "Voice message" else "Voice message · $sender"

    /** Who sent the open voice note (iOS PlayerAccessory.sender): "You", the channel, the group member, else the person. */
    fun noteSender(mine: Boolean, channel: Boolean, group: Boolean, chatName: String, memberName: String?, personName: String): String = when {
        mine -> "You"
        channel -> chatName
        group -> memberName ?: chatName
        else -> personName
    }

    /** "1:05", or "1:02:03" past an hour (iOS MusicFiles.clock). */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** The chat list line a playlist send writes (iOS sendPlaylist). */
    fun playlistPreview(count: Int) = "🎵 $count songs"

    /** Storage path of song [i] (iOS sendPlaylist path(i)): the first is the document's own path. */
    fun songPath(cid: String, messageId: String, i: Int) =
        if (i == 0) "chat/$cid/$messageId.file.enc" else "chat/$cid/$messageId.t$i.file.enc"

    /** One song sealed for the write: its envelope, plain stand-in name, sealed name, size, sealed words. */
    data class SealedSong(
        val enc: Map<String, Any>,
        val plainName: String,
        val sealedName: String?,
        val size: Int,
        val sealedTitle: String? = null,
        val sealedArtist: String? = null,
    )

    /** Song [i]'s `album` item, key for key as iOS writes it. [url] null while it is still going up. */
    fun playlistItem(songs: List<SealedSong>, i: Int, url: String?, listNameSealed: String): Map<String, Any> = buildMap {
        val s = songs[i]
        put("kind", "audio")
        put("enc", s.enc)
        put("fileName", s.plainName)
        put("fileSize", s.size)
        s.sealedName?.let { put("fileNameEnc", it) }
        url?.let { put("fileUrl", it) }
        s.sealedTitle?.let { put("title", it) }
        s.sealedArtist?.let { put("artist", it) }
        if (i == 0) put("listName", listNameSealed)
    }

    /** The playlist message as first written (iOS sendPlaylist `msg`), before the songs are up. */
    fun playlistMessage(
        me: String, songs: List<SealedSong>, captionSealed: String, listNameSealed: String, clientTs: Double,
        clientId: String?, thumbUrl: String?, thumbEnc: Map<String, Any>?, serverTime: Any,
    ): Map<String, Any> = buildMap {
        require(songs.size >= 2) { "A playlist needs two songs or more." }
        val first = songs[0]
        put("type", "file")
        put("fileName", first.plainName)
        put("fileSize", first.size)
        put("enc", first.enc)
        put("text", captionSealed)
        put("authorId", me)
        put("createdAt", serverTime)
        put("clientTs", clientTs)
        put("uploading", true)
        put("album", songs.indices.map { playlistItem(songs, it, null, listNameSealed) })
        first.sealedName?.let { put("fileNameEnc", it) }
        if (thumbUrl != null && thumbEnc != null) { put("thumbUrl", thumbUrl); put("thumbEnc", thumbEnc) }
        clientId?.let { put("clientId", it) }
    }

    /** The one update once every song is up (iOS attachMedia): the first song's url and the full items. */
    fun playlistAttach(songs: List<SealedSong>, urls: List<String>, listNameSealed: String, delete: Any): Map<String, Any> = mapOf(
        "fileUrl" to urls[0],
        "album" to songs.indices.map { playlistItem(songs, it, urls[it], listNameSealed) },
        "uploading" to delete,
    )
}
