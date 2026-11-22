package com.fariin.app.media

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.fariin.app.R
import com.fariin.app.data.ChatsNow
import com.fariin.app.data.VoiceMediaRules
import com.fariin.app.security.AppLock
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The open voice note on the lock screen, in the notification shade and on headset buttons (iOS
 * MPNowPlayingInfoCenter + MPRemoteCommandCenter in VoiceNotePlayer). One session over [VoicePlayer]'s
 * own ExoPlayer: each new note hands its player over, so the notification does not blink between
 * notes of a run. A voice note: play, pause and scrubbing only, no artwork, as iOS. A song: its
 * title, artist and picture, and next and previous inside a playlist. It goes away when the note is closed or the run of notes ends.
 *
 * Play and pause from outside go through [VoicePlayer.remotePlay] / [VoicePlayer.remotePause], so a
 * call still blocks play and a call ending does not override the person's choice.
 */
@OptIn(UnstableApi::class)
object VoiceSession {
    private var session: MediaSession? = null
    /** Our own controller: binding it brings up [VoiceMediaService], allowed from the background too. */
    private var controller: ListenableFuture<MediaController>? = null
    /** The finished note's player, kept until the next note takes over or the session closes. */
    private var retired: ExoPlayer? = null
    private var closeLater: Job? = null
    private val main = CoroutineScope(Dispatchers.Main)

    internal val current: MediaSession? get() = session

    /** What outside controls may do: play, pause, scrub. No next or previous (iOS enables neither). */
    private val commands = Player.Commands.Builder().addAll(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_STOP,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_TIMELINE,
        Player.COMMAND_GET_METADATA,
        Player.COMMAND_GET_AUDIO_ATTRIBUTES,
        // A playlist's song only: the player below offers these while there is a song to go to.
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    ).build()

    /**
     * The words for a song (iOS updateNowPlaying with isMusic): its title over its artist, and its
     * picture. With App Lock on only "Song", as a voice note shows only "Voice message".
     */
    fun musicMetadata(context: Context, music: VoicePlayer.MusicNow): MediaMetadata {
        val locked = AppLock.enabled(context.applicationContext)
        val b = MediaMetadata.Builder()
            .setTitle(if (locked) "Song" else music.title)
            .setArtist(if (locked) null else music.artist.ifEmpty { null })
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        if (!locked) music.art?.let { b.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        return b.build()
    }

    /** The words for [cid]'s note: the same title as the floating bar, nothing with App Lock on. */
    fun metadata(context: Context, cid: String, author: String): MediaMetadata {
        val me = com.fariin.app.Fb.auth.currentUser?.uid.orEmpty()
        val name = ChatsNow.find(cid)?.displayName(me).orEmpty()
        val m = VoiceMediaRules.meta(name, mine = author.isNotEmpty() && author == me,
            appLocked = AppLock.enabled(context.applicationContext))
        return MediaMetadata.Builder()
            .setTitle(m.title)
            .setArtist(m.subtitle.ifEmpty { null })
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
    }

    /** A note started on [p]: show it, or move the open session over to it. */
    fun attach(context: Context, p: ExoPlayer) {
        closeLater?.cancel(); closeLater = null
        val app = context.applicationContext
        val s = session
        if (s == null) {
            val b = MediaSession.Builder(app, Remote(p)).setId("fariin-voice").setCallback(Callback)
            openApp(app)?.let { b.setSessionActivity(it) }
            session = b.build()
            connect(app)
        } else {
            s.player = Remote(p)
        }
        retired?.let { if (it !== p) it.release() }
        retired = null
    }

    /**
     * The note on [p] finished. Its player stays in the session for a moment, as the run's next note
     * may take over; if nothing does, the session closes by itself.
     */
    fun retire(p: ExoPlayer) {
        if (session == null) { p.release(); return }
        retired?.let { if (it !== p) it.release() }
        retired = p
        closeLater?.cancel()
        closeLater = main.launch { delay(4000); close() }
    }

    /** Nothing open any more: the notification and lock screen controls go (iOS clearNowPlaying). */
    fun close() {
        closeLater?.cancel(); closeLater = null
        session?.release(); session = null
        controller?.let { MediaController.releaseFuture(it) }; controller = null
        retired?.release(); retired = null
    }

    private fun connect(app: Context) {
        if (controller != null) return
        runCatching {
            controller = MediaController.Builder(app, SessionToken(app, ComponentName(app, VoiceMediaService::class.java)))
                .buildAsync()
        }
    }

    private fun openApp(c: Context): PendingIntent? =
        c.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
            PendingIntent.getActivity(c, 7301, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    private object Callback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(commands)
                .build()
    }

    /** The player outside controls see: play and pause take the app's own path, the rest is the note's player. */
    private class Remote(p: ExoPlayer) : ForwardingPlayer(p) {
        override fun play() = VoicePlayer.remotePlay()
        override fun pause() = VoicePlayer.remotePause()
        override fun setPlayWhenReady(playWhenReady: Boolean) =
            if (playWhenReady) VoicePlayer.remotePlay() else VoicePlayer.remotePause()
        // Posted: stopping releases this very player, not from inside the session's own call.
        override fun stop() { main.launch { VoicePlayer.stop() } }

        // Next and previous for a playlist's song (owner brief 2026-11-22): on only while there is one to go to.
        private fun extra(): Player.Commands {
            val m = VoicePlayer.state.value.music ?: return Player.Commands.EMPTY
            val b = Player.Commands.Builder()
            if (m.hasNext) b.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            if (m.hasPrevious) b.addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            return b.build()
        }
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(extra()).build()
        override fun isCommandAvailable(command: Int): Boolean =
            super.isCommandAvailable(command) || extra().contains(command)
        override fun seekToNext() = VoicePlayer.nextTrack()
        override fun seekToNextMediaItem() = VoicePlayer.nextTrack()
        override fun seekToPrevious() = VoicePlayer.previousTrack()
        override fun seekToPreviousMediaItem() = VoicePlayer.previousTrack()
        override fun hasNextMediaItem(): Boolean = VoicePlayer.state.value.music?.hasNext == true
        override fun hasPreviousMediaItem(): Boolean = VoicePlayer.state.value.music?.hasPrevious == true
    }
}

/** Hosts [VoiceSession] for the media notification; foreground while a note plays (media3 does that). */
@OptIn(UnstableApi::class)
class VoiceMediaService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().also {
            it.setSmallIcon(R.drawable.ic_stat_fariin)
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = VoiceSession.current

    /** The app was swiped away: the note stops with it, as on iOS. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        VoicePlayer.stop()
        stopSelf()
    }
}
