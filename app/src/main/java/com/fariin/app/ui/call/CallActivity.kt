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
        private const val EXTRA_LINK = "link.key"
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

        /** A call link (its key in the text form): the join screen's Join. */
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
    private var groupMode = false
    /** Picture in picture: a video call shrinks into a small window when I leave the app. */
    private var inPip by mutableStateOf(false)
    private var demoGroup: GroupCallUi? = null

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
                if (groupMode) {
                    val live by GroupCall.ui.collectAsState()
                    val g = demoGroup ?: live
                    LaunchedEffect(g.cid, g.connecting, g.notice) {
                        if (demoGroup == null && g.cid == null && !g.connecting) {
                            g.notice?.let { Toast.makeText(this@CallActivity, it, Toast.LENGTH_LONG).show(); GroupCall.clearNotice() }
                            finish()
                        }
                    }
                    GroupCallScreen(g, onMic = { GroupCall.toggleMic() }, onCamera = { cameraToggle(GroupCall.ui.value.cameraOn) },
                        onLeave = { GroupCall.leave(); finish() })
                } else {
                    val ui by CallManager.ui.collectAsState()
                    LaunchedEffect(ui.status.phase) { if (ui.status.phase == CallPhase.IDLE) finish() }
                    val pip = CallRouting.pipWanted(ui.status.phase, ui.cameraOn, ui.remoteCameraOn)
                    LaunchedEffect(pip) { updatePip(pip) }
                    if (inPip) { PipScreen(ui); return@FariinTheme }
                    CallScreen(ui,
                        onAccept = { withMic(ui.status.video, { CallManager.accept() }, { CallManager.micDenied() }) },
                        onDecline = { CallManager.decline() },
                        onEnd = { CallManager.hangUp() },
                        onMute = { CallManager.toggleMute() },
                        onRoute = { CallManager.setRoute(it) },
                        onCamera = { cameraToggle(ui.cameraOn) },
                        onFlip = { CallManager.flipCamera() },
                        onMinimize = { minimize() })
                }
            }
        }
    }

    private fun cameraToggle(on: Boolean) {
        if (on || granted(Manifest.permission.CAMERA)) {
            if (groupMode) GroupCall.toggleCamera() else CallManager.toggleCamera()
        } else { cameraAfterAsk = true; askCamera.launch(Manifest.permission.CAMERA) }
    }

    private fun pipParams(auto: Boolean): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16))
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
     * The header's minimize (iOS: the call shrinks into its card): a video call goes into the
     * picture-in-picture window, a voice call steps behind the app and goes on (the ongoing call
     * notification and the Calls tab bring it back), as Home does.
     */
    private fun minimize() {
        val u = CallManager.ui.value
        if (pipAllowed() && CallRouting.pipWanted(u.status.phase, u.cameraOn, u.remoteCameraOn)) {
            try { enterPictureInPictureMode(pipParams(true)); return } catch (_: Exception) {}
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
            CallNotifications.ACTION_ACCEPT -> withMic(CallManager.ui.value.status.video, { CallManager.accept() }, { CallManager.micDenied() })
            CallNotifications.ACTION_DECLINE -> { CallManager.decline(); finish() }
            CallNotifications.ACTION_HANGUP -> CallManager.hangUp()
        }
        i.action = null
        i.getStringExtra(EXTRA_LINK)?.let { text ->
            groupMode = true
            val video = i.getBooleanExtra(EXTRA_VIDEO, false)
            i.removeExtra(EXTRA_LINK)
            val key = com.fariin.app.call.CallLinkKey.ofText(text)
            if (key == null) { Toast.makeText(this, "This call link is no longer valid", Toast.LENGTH_LONG).show(); finish(); return }
            withMic(video, { GroupCall.joinLink(this, key, video) }, { finish() })
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

    private fun demo(screen: String) {
        when (screen) {
            "incoming" -> CallManager.demo(this, CallPhase.INCOMING, video = false)
            "incomingVideo" -> CallManager.demo(this, CallPhase.INCOMING, video = true)
            "calling" -> CallManager.demo(this, CallPhase.OUTGOING, video = false)
            "video" -> CallManager.demo(this, CallPhase.ACTIVE, video = true)
            "ended" -> CallManager.demo(this, CallPhase.ENDED, video = false)
            // A car or headset connected: the speaker button opens the route list.
            "bluetooth" -> CallManager.demo(this, CallPhase.ACTIVE, video = false, route = com.fariin.app.call.RouteState(
                com.fariin.app.call.AudioRoute.BLUETOOTH,
                setOf(com.fariin.app.call.AudioRoute.EARPIECE, com.fariin.app.call.AudioRoute.SPEAKER, com.fariin.app.call.AudioRoute.BLUETOOTH),
                "Car Kit"))
            "held" -> CallManager.demo(this, CallPhase.ACTIVE, video = false, held = true)
            "group" -> {
                groupMode = true
                demoGroup = GroupCallUi(cid = "demo", title = "Family", joined = true, members = listOf(
                    GroupMember("me", "You", speaking = false, micOn = true, video = null),
                    GroupMember("a", "Amina Yusuf", speaking = true, micOn = true, video = null),
                    GroupMember("b", "Hodan Ali", speaking = false, micOn = false, video = null),
                    GroupMember("c", "Omar Farah", speaking = false, micOn = true, video = null),
                ))
            }
            else -> CallManager.demo(this, CallPhase.ACTIVE, video = false)
        }
    }
}
