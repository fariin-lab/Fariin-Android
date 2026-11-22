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
 * notes of a run. As iOS: play, pause and scrubbing only, no next or previous, no artwork, and it
 * goes away when the note is closed or the run of notes ends.
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
    ).build()

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
