package com.fariin.app.ui.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.util.Rational
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fariin.app.call.CallRouting
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.fariin.app.call.CallManager
import com.fariin.app.call.CallNotifications
import com.fariin.app.call.CallPhase
import com.fariin.app.call.GroupCall
import com.fariin.app.call.GroupCallUi
import com.fariin.app.call.GroupMember
import com.fariin.app.call.GroupRing

import com.fariin.app.ui.theme.FariinTheme

/**
 * The call screens in their own activity, so a ring can open over the lock screen from the
 * full-screen notification: incoming, calling, in call and the short ended tail, plus a group
 * room. Opened by [startDial], [startGroup], the ring notification, or (debug only)
 * `adb shell am start -n com.fariin.app/.MainActivity --ez callDemo true --es callScreen incoming|calling|voice|video|group`.
 */
class CallActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_UID = "dial.uid"
        private const val EXTRA_NAME = "dial.name"
        private const val EXTRA_PHOTO = "dial.photo"
        private const val EXTRA_VIDEO = "dial.video"
        private const val EXTRA_GROUP = "group.cid"
        private const val EXTRA_START = "group.start"
        private const val EXTRA_ADHOC_UIDS = "adhoc.uids"
        private const val EXTRA_ADHOC_NAMES = "adhoc.names"
        private const val EXTRA_ADHOC_PHOTOS = "adhoc.photos"
        private const val EXTRA_LINK = "link.key"
        private const val EXTRA_LOBBY = "lobby.key"
        const val EXTRA_DEMO = "callScreen"

        fun startDial(c: Context, uid: String, name: String, photo: String?, video: Boolean) {
            c.startActivity(Intent(c, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_UID, uid).putExtra(EXTRA_NAME, name).putExtra(EXTRA_PHOTO, photo).putExtra(EXTRA_VIDEO, video))
        }

        /** [start] true: the call button (joins a running call or starts one); false: the Join bar. */
        fun startGroup(c: Context, cid: String, title: String, video: Boolean, start: Boolean = false) {
            c.startActivity(Intent(c, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_GROUP, cid).putExtra(EXTRA_NAME, title).putExtra(EXTRA_VIDEO, video).putExtra(EXTRA_START, start))
        }

        /** New Call with two or more people picked: a multi-person call with them (GroupCall.startAdhoc). */
        fun startAdhoc(c: Context, people: List<com.fariin.app.call.CallPerson>, video: Boolean) {
            c.startActivity(Intent(c, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_ADHOC_UIDS, people.map { it.uid }.toTypedArray())
                .putExtra(EXTRA_ADHOC_NAMES, people.map { it.name }.toTypedArray())
                .putExtra(EXTRA_ADHOC_PHOTOS, people.map { it.photo ?: "" }.toTypedArray())
                .putExtra(EXTRA_VIDEO, video))
        }

        /** A call link (its key in the text form): the pre-join screen (iOS CallLobbyView), then the call. */
        fun startLobby(c: Context, key: String, video: Boolean) {
            c.startActivity(Intent(c, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_LOBBY, key).putExtra(EXTRA_VIDEO, video))
        }

        /** A call link (its key in the text form): straight in, no lobby. */
        fun startLink(c: Context, key: String, video: Boolean) {
            c.startActivity(Intent(c, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_LINK, key).putExtra(EXTRA_VIDEO, video))
        }

        fun startRing(c: Context) {
            c.startActivity(Intent(c, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }

        fun startDemo(c: Context, screen: String) {
            c.startActivity(Intent(c, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_DEMO, screen))
        }
    }

    private var pending: (() -> Unit)? = null
    private var denied: (() -> Unit)? = null
    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val mic = r[Manifest.permission.RECORD_AUDIO] ?: granted(Manifest.permission.RECORD_AUDIO)
        if (mic) pending?.invoke() else denied?.invoke()
        pending = null; denied = null
    }
    private var cameraAfterAsk = false
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && cameraAfterAsk) { if (groupMode) GroupCall.toggleCamera() else CallManager.toggleCamera() }
        cameraAfterAsk = false
    }
    /**
     * Screen share: Android's own consent dialog, asked at the moment of need (the share button).
     * Its answer goes to the call it was asked for: the group room or the 1:1 call.
     */
    private val askScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (groupMode) GroupCall.shareConsent(r.resultCode, r.data) else CallManager.shareConsent(r.resultCode, r.data)
    }

    private fun shareTapped() {
        val ask = if (groupMode) GroupCall.shareTapped() else CallManager.shareTapped()
        if (!ask) return
        val pm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        try { askScreen.launch(pm.createScreenCaptureIntent()) } catch (_: Exception) {
            if (groupMode) GroupCall.shareConsent(RESULT_CANCELED, null) else CallManager.shareConsent(RESULT_CANCELED, null)
        }
    }

    private var groupMode by mutableStateOf(false)
    /** The group ring screen (GroupRing's invite) is up. */
    private var ringMode by mutableStateOf(false)
    /** A link's pre-join screen, until its call has been joined once. */
    private var lobbyKey by mutableStateOf<String?>(null)
    private var lobbyVideo = true
    private var lobbyJoined by mutableStateOf(false)
    /** Picture in picture: a video call shrinks into a small window when I leave the app. */
    private var inPip by mutableStateOf(false)
    private var demoGroup: GroupCallUi? = null
    /** Debug demo only: my real camera in the card, so the card's video path runs without a call. */
    private var demoCam by mutableStateOf<livekit.org.webrtc.VideoTrack?>(null)
    private var demoCapturer: livekit.org.webrtc.CameraVideoCapturer? = null
    private var demoHelper: livekit.org.webrtc.SurfaceTextureHelper? = null

    private fun startDemoCamera() {
        if (demoCam != null || !granted(Manifest.permission.CAMERA)) return
        val f = com.fariin.app.call.RtcEngine.factory(this)
        val en = livekit.org.webrtc.Camera2Enumerator(this)
        val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.firstOrNull() ?: return
        val cap = en.createCapturer(name, null) ?: return
        val src = f.createVideoSource(false)
        val helper = livekit.org.webrtc.SurfaceTextureHelper.create("demo-camera", com.fariin.app.call.RtcEngine.egl.eglBaseContext)
        cap.initialize(helper, applicationContext, src.capturerObserver)
        try { cap.startCapture(1280, 720, 30) } catch (_: Exception) { helper.dispose(); return }
        demoCapturer = cap; demoHelper = helper
        demoCam = f.createVideoTrack("demo-video", src)
    }

    override fun onDestroy() {
        demoCam?.let { try { demoCapturer?.stopCapture() } catch (_: InterruptedException) {}; demoCapturer?.dispose(); demoHelper?.dispose() }
        demoCam = null
        super.onDestroy()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun withMic(video: Boolean, then: () -> Unit, otherwise: () -> Unit) {
        val need = buildList {
            if (!granted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (video && !granted(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
        }
        if (need.isEmpty()) { then(); return }
        pending = then; denied = otherwise
        ask.launch(need.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The call screens are always dark: light status and navigation bar icons.
        enableEdgeToEdge(androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        CallManager.init(this)
        handle(intent)
        setContent {
            FariinTheme(dark = true) {
                if (ringMode) {
                    val inv by GroupRing.invite.collectAsState()
                    val i = inv
                    // The ring ended (timed out, the call ended, answered on another phone): the screen goes.
                    LaunchedEffect(i == null) { if (i == null && ringMode) finish() }
                    // System back closes the screen; the ring goes on in its notification until the minute is over.
                    androidx.activity.compose.BackHandler { finish() }
                    if (i != null) IncomingGroupCallScreen(i, onIgnore = { GroupRing.decline(); finish() }, onJoin = { muted -> acceptRing(muted) })
                } else if (groupMode) {
                    val live by GroupCall.ui.collectAsState()
                    val g = demoGroup ?: live
                    LaunchedEffect(g.joined) { if (g.joined) lobbyJoined = true }
                    val inLobby = lobbyKey != null && !lobbyJoined
                    LaunchedEffect(g.cid, g.connecting, g.notice, inLobby) {
                        if (demoGroup == null && g.cid == null && !g.connecting && !inLobby) {
                            g.notice?.let { Toast.makeText(this@CallActivity, it, Toast.LENGTH_LONG).show(); GroupCall.clearNotice() }
                            finish()
                        }
                    }
                    // In the call the note is the pill under the header (iOS GroupCallTopToast); the
                    // system toast only where that pill is not drawn (the lobby, picture in picture).
                    LaunchedEffect(g.toast, inLobby, inPip) {
                        if (inLobby || inPip) g.toast?.let { Toast.makeText(this@CallActivity, it, Toast.LENGTH_SHORT).show(); GroupCall.clearToast() }
                    }
                    if (inLobby) {
                        CallLobbyScreen(lobbyKey!!, lobbyVideo, g, onLeave = { GroupCall.leave(); finish() }, onJoin = { video, mic -> joinFromLobby(video, mic) })
                    } else GroupCallScreen(g, onMic = { micToggle() }, onCamera = { cameraToggle(GroupCall.ui.value.cameraOn) },
                        onLeave = { GroupCall.leave(); finish() }, onShare = { if (demoGroup == null) shareTapped() },
                        onMinimize = { minimizeGroup() }, pip = inPip,
                        onHand = { GroupCall.setHand(it) }, onReact = { GroupCall.react(it) })
                } else {
                    val ui by CallManager.ui.collectAsState()
                    LaunchedEffect(ui.status.phase) { if (ui.status.phase == CallPhase.IDLE) finish() }
                    val pip = CallRouting.pipWanted(ui.status.phase, ui.cameraOn, ui.remoteCameraOn)
                    val pipTall = CallRouting.pipVideo(ui.cameraOn, ui.remoteCameraOn)
                    LaunchedEffect(pip, pipTall) { updatePip(pip) }
                    // iOS: a call shrunk away opens back up for the No answer page.
                    LaunchedEffect(ui.noAnswer) {
                        if (ui.noAnswer && inPip) startActivity(Intent(this@CallActivity, CallActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                    if (inPip) { PipScreen(ui); return@FariinTheme }
                    CallScreen(demoCam?.let { ui.copy(localVideo = it) } ?: ui,
                        onAccept = { withMic(ui.status.video, { CallManager.accept() }, { CallManager.micDenied() }) },
                        onDecline = { CallManager.decline() },
                        onEnd = { CallManager.hangUp() },
                        onMute = { CallManager.toggleMute() },
                        onRoute = { CallManager.setRoute(it) },
                        onCamera = { cameraToggle(ui.cameraOn) },
                        onFlip = { CallManager.flipCamera() },
                        onMinimize = { minimize() },
                        onShare = { if (ui.peerUid == "demo") demoShare() else shareTapped() },
                        onCallAgain = { withMic(ui.status.video, { CallManager.callAgain(this@CallActivity) }, { CallManager.closeNoAnswer() }) },
                        onVoiceMessage = { voiceMessageAfterNoAnswer() },
                        onCloseNoAnswer = { CallManager.closeNoAnswer() })
                }
            }
        }
    }

    /**
     * iOS recordVoiceNoteAfterNoAnswer: the page closes and the chat with them opens. (iOS also
     * starts its recorder there; the Android composer records only while the mic is held.)
     */
    private fun voiceMessageAfterNoAnswer() {
        val peer = CallManager.ui.value.peerUid
        val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        CallManager.closeNoAnswer()
        if (me != null && peer.isNotEmpty()) startActivity(com.fariin.app.system.ChatShortcuts.openIntent(this, com.fariin.app.call.CallWire.convId(me, peer)))
        finish()
    }

    /** iOS: nobody is looking at the No answer page once the app has gone; it closes. */
    override fun onStop() {
        super.onStop()
        if (!inPip && !isChangingConfigurations) CallManager.closeNoAnswer()
    }

    /** Join on the ring screen: the mic asked for now; the camera only when access was already given. */
    private fun acceptRing(muted: Boolean) {
        val video = GroupRing.invite.value?.video == true
        withMic(false, {
            groupMode = true; ringMode = false
            GroupRing.accept(this, muted, cameraAllowed = video && granted(Manifest.permission.CAMERA))
        }, {
            Toast.makeText(this, "Allow microphone access to make calls.", Toast.LENGTH_LONG).show()
        })
    }

    /** The lobby's Join: in the way its two buttons were left (it asked for each when it was turned on). */
    private fun joinFromLobby(video: Boolean, mic: Boolean) {
        val key = lobbyKey?.let { com.fariin.app.call.CallLinkKey.ofText(it) } ?: return
        GroupCall.joinLink(this, key, video && granted(Manifest.permission.CAMERA), mic = mic && granted(Manifest.permission.RECORD_AUDIO))
    }

    /** The group call's mute: turning the mic on asks for it if a muted join never had it. */
    private fun micToggle() {
        if (!GroupCall.ui.value.micOn && !granted(Manifest.permission.RECORD_AUDIO)) {
            withMic(false, { GroupCall.toggleMic() }, {})
        } else GroupCall.toggleMic()
    }

    private fun cameraToggle(on: Boolean) {
        if (on || granted(Manifest.permission.CAMERA)) {
            if (groupMode) GroupCall.toggleCamera() else CallManager.toggleCamera()
        } else { cameraAfterAsk = true; askCamera.launch(Manifest.permission.CAMERA) }
    }

    private fun pipParams(auto: Boolean): PictureInPictureParams {
        val u = CallManager.ui.value
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(if (CallRouting.pipVideo(u.cameraOn, u.remoteCameraOn)) Rational(9, 16) else Rational(1, 1))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setAutoEnterEnabled(auto).setSeamlessResizeEnabled(true)
        return b.build()
    }

    private fun pipAllowed() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Android 12 and later go into the window by themselves on Home; older ones in [onUserLeaveHint]. */
    private fun updatePip(wanted: Boolean) {
        if (!pipAllowed()) return
        try { setPictureInPictureParams(pipParams(wanted)) } catch (_: Exception) {}
    }

    /**
     * The header's minimize (iOS: the call shrinks into its card): the call goes into the
     * picture-in-picture window, as Home does. Without picture in picture it steps behind the app
     * and goes on (the ongoing call notification and the Calls tab bring it back).
     */
    private fun minimize() {
        val u = CallManager.ui.value
        if (pipAllowed() && CallRouting.pipWanted(u.status.phase, u.cameraOn, u.remoteCameraOn)) {
            try { enterPictureInPictureMode(pipParams(true)); return } catch (_: Exception) {}
        }
        moveTaskToBack(true)
    }

    /**
     * A group call shrinks (iOS GroupFloatingCallWindow): with a camera on anywhere, into the
     * picture-in-picture window showing the people; voice only, behind the app, where the ongoing
     * call notification and the chat's live call bar bring it back.
     */
    private fun minimizeGroup() {
        val g = GroupCall.ui.value
        if (pipAllowed() && (g.cameraOn || g.members.any { it.video != null || it.screen != null })) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build()); return
            } catch (_: Exception) {}
        }
        moveTaskToBack(true)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (groupMode || !pipAllowed() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        val u = CallManager.ui.value
        if (CallRouting.pipWanted(u.status.phase, u.cameraOn, u.remoteCameraOn)) {
            try { enterPictureInPictureMode(pipParams(false)) } catch (_: Exception) {}
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i ?: return
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        i.getStringExtra(EXTRA_DEMO)?.takeIf { debuggable }?.let { demo(it); i.removeExtra(EXTRA_DEMO); return }
        when (i.action) {
            GroupRing.ACTION_RING -> { i.action = null; if (GroupRing.invite.value != null) ringMode = true else finish(); return }
            GroupRing.ACTION_DECLINE -> { i.action = null; GroupRing.decline(); finish(); return }
            GroupRing.ACTION_ACCEPT -> { i.action = null; if (GroupRing.invite.value != null) { ringMode = true; acceptRing(false) } else finish(); return }
            CallNotifications.ACTION_ACCEPT -> withMic(CallManager.ui.value.status.video, { CallManager.accept() }, { CallManager.micDenied() })
            CallNotifications.ACTION_DECLINE -> { CallManager.decline(); finish() }
            CallNotifications.ACTION_HANGUP -> CallManager.hangUp()
        }
        i.action = null
        i.getStringExtra(EXTRA_LOBBY)?.let { text ->
            i.removeExtra(EXTRA_LOBBY)
            if (GroupCall.ui.value.cid != null) { groupMode = true; return }   // already in a call: that call comes forward
            groupMode = true; lobbyKey = text; lobbyJoined = false
            lobbyVideo = i.getBooleanExtra(EXTRA_VIDEO, true)
            return
        }
        i.getStringExtra(EXTRA_LINK)?.let { text ->
            groupMode = true
            val video = i.getBooleanExtra(EXTRA_VIDEO, false)
            i.removeExtra(EXTRA_LINK)
            val key = com.fariin.app.call.CallLinkKey.ofText(text)
            if (key == null) { Toast.makeText(this, "This call link is no longer valid", Toast.LENGTH_LONG).show(); finish(); return }
            withMic(video, { GroupCall.joinLink(this, key, video) }, { finish() })
            return
        }
        i.getStringArrayExtra(EXTRA_ADHOC_UIDS)?.let { uids ->
            groupMode = true
            val video = i.getBooleanExtra(EXTRA_VIDEO, false)
            val names = i.getStringArrayExtra(EXTRA_ADHOC_NAMES).orEmpty()
            val photos = i.getStringArrayExtra(EXTRA_ADHOC_PHOTOS).orEmpty()
            i.removeExtra(EXTRA_ADHOC_UIDS)
            if (GroupCall.ui.value.cid != null) return   // already in a call: that call comes forward
            val people = uids.mapIndexed { n, u ->
                com.fariin.app.call.CallPerson(u, names.getOrNull(n)?.ifBlank { null } ?: "Member", photos.getOrNull(n)?.ifEmpty { null })
            }
            withMic(video, { GroupCall.startAdhoc(this, people, video) }, { finish() })
            return
        }
        i.getStringExtra(EXTRA_GROUP)?.let { cid ->
            groupMode = true
            val video = i.getBooleanExtra(EXTRA_VIDEO, false)
            val title = i.getStringExtra(EXTRA_NAME) ?: ""
            val start = i.getBooleanExtra(EXTRA_START, false)
            i.removeExtra(EXTRA_GROUP)
            withMic(video, { GroupCall.join(this, cid, title, video, requireLive = !start) }, { finish() })
            return
        }
        val uid = i.getStringExtra(EXTRA_UID) ?: return
        i.removeExtra(EXTRA_UID)
        val video = i.getBooleanExtra(EXTRA_VIDEO, false)
        val name = i.getStringExtra(EXTRA_NAME) ?: ""
        val photo = i.getStringExtra(EXTRA_PHOTO)
        withMic(video, { CallManager.dial(this, uid, name, photo, video) }, {
            Toast.makeText(this, "Allow microphone access to make calls.", Toast.LENGTH_LONG).show()
            finish()
        })
    }

    /** Debug demo: the share button walks the demo share on and off without the system dialog. */
    private fun demoShare() {
        val u = CallManager.ui.value
        val next = if (u.sharePhase == com.fariin.app.call.SharePhase.OFF) com.fariin.app.call.SharePhase.LIVE else com.fariin.app.call.SharePhase.OFF
        CallManager.demo(this, CallPhase.ACTIVE, video = false, share = next)
    }

    private fun demo(screen: String) {
        when (screen) {
            "incoming" -> CallManager.demo(this, CallPhase.INCOMING, video = false)
            "incomingVideo" -> CallManager.demo(this, CallPhase.INCOMING, video = true)
            "calling" -> CallManager.demo(this, CallPhase.OUTGOING, video = false)
            "video" -> { CallManager.demo(this, CallPhase.ACTIVE, video = true); startDemoCamera() }
            "ended" -> CallManager.demo(this, CallPhase.ENDED, video = false)
            // A car or headset connected: the speaker button opens the route list.
            "bluetooth" -> CallManager.demo(this, CallPhase.ACTIVE, video = false, route = com.fariin.app.call.RouteState(
                com.fariin.app.call.AudioRoute.BLUETOOTH,
                setOf(com.fariin.app.call.AudioRoute.EARPIECE, com.fariin.app.call.AudioRoute.SPEAKER, com.fariin.app.call.AudioRoute.BLUETOOTH),
                "Car Kit"))
            "held" -> CallManager.demo(this, CallPhase.ACTIVE, video = false, held = true)
            // Screen share: mine (the card with Stop Sharing), theirs (their screen in the card).
            "shareMine" -> CallManager.demo(this, CallPhase.ACTIVE, video = false, share = com.fariin.app.call.SharePhase.LIVE)
            "shareTheirs" -> CallManager.demo(this, CallPhase.ACTIVE, video = false, theirScreen = true)
            // A group call while I share: my tile says so; the share button is white.
            "groupSharing" -> {
                groupMode = true
                demoGroup = GroupCallUi(cid = "demo", title = "Family", joined = true, sharing = true, members = listOf(
                    GroupMember("me", "You", speaking = false, micOn = true, video = null, local = true),
                    GroupMember("a", "Amina Yusuf", speaking = false, micOn = true, video = null),
                    GroupMember("b", "Hodan Ali", speaking = false, micOn = false, video = null),
                ))
            }
            "group" -> {
                groupMode = true
                demoGroup = GroupCallUi(cid = "demo", title = "Family", joined = true, activeSpeaker = "a", members = listOf(
                    GroupMember("me", "You", speaking = false, micOn = true, video = null, local = true),
                    GroupMember("a", "Amina Yusuf", speaking = true, micOn = true, video = null, role = com.fariin.app.call.CallRole.OWNER),
                    GroupMember("b", "Hodan Ali", speaking = false, micOn = false, video = null),
                    GroupMember("c", "Omar Farah", speaking = false, micOn = true, video = null, poor = true),
                ))
            }
            // One other person: they fill the stage, my card bottom right.
            "groupDuo" -> {
                groupMode = true
                demoGroup = GroupCallUi(cid = "demo", title = "Family", joined = true, members = listOf(
                    GroupMember("me", "You", speaking = false, micOn = false, video = null, local = true),
                    GroupMember("a", "Amina Yusuf", speaking = false, micOn = true, video = null),
                ))
            }
            // Nine people: the 2 x 3 grid with the rest in the strip; a link's creator with two at the door.
            "groupMany" -> {
                groupMode = true
                val names = listOf("Amina Yusuf", "Hodan Ali", "Omar Farah", "Bilan Noor", "Yusuf Aden", "Sahra Abdi", "Khadar Jama", "Ifrah Said")
                demoGroup = GroupCallUi(cid = "demo", title = "Team call", joined = true, activeSpeaker = "p7", linkRoom = "demo", linkCreator = true,
                    requests = listOf(com.fariin.app.call.LinkRequest("r1", "Faisal Warsame", null), com.fariin.app.call.LinkRequest("r2", "Nasra Ahmed", null)),
                    members = listOf(GroupMember("me", "You", speaking = false, micOn = true, video = null, local = true)) +
                        names.mapIndexed { n, s -> GroupMember("p${n + 1}", s, speaking = n == 6, micOn = n % 3 != 1, video = null) })
            }
            // A group chat's call I started, nobody here yet: the group's photo with the pulse.
            "groupWaiting" -> {
                groupMode = true
                demoGroup = GroupCallUi(cid = "demo", title = "Family", joined = true, calling = true, members = listOf(
                    GroupMember("me", "You", speaking = false, micOn = true, video = null, local = true)))
            }
            // The incoming group ring screen (no Firebase, no sound).
            "groupRing" -> {
                com.fariin.app.call.GroupRing.demo(com.fariin.app.call.GroupInvite("demo", "Family", video = false, startedBy = "a",
                    startedAtMs = System.currentTimeMillis(), faces = listOf(com.fariin.app.call.RingFace("a", "Amina Yusuf", null))))
                ringMode = true
            }
            // A link's pre-join screen (a made-up key: the server checks fail quietly here).
            "lobby" -> { groupMode = true; lobbyKey = com.fariin.app.call.CallLinkKey.generate().text; lobbyVideo = true }
            else -> CallManager.demo(this, CallPhase.ACTIVE, video = false)
        }
    }
}
