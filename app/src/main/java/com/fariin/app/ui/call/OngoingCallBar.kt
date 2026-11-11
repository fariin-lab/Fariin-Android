package com.fariin.app.ui.call

import androidx.compose.ui.res.vectorResource
import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.fariin.app.call.CallMachine
import com.fariin.app.call.CallManager
import com.fariin.app.call.GroupCall
import kotlinx.coroutines.delay

/** The colour behind the bar and around the app's card while a call goes on. */
val CallBarBackdrop = Color(0xFF0E0F11)

/** True while a 1:1 or group call is going on (the bar shows over the app). */
@Composable
fun ongoingCallShown(): Boolean {
    val one by CallManager.ui.collectAsState()
    val group by GroupCall.ui.collectAsState()
    return one.status.live || group.joined || group.connecting
}

/** What the bar shows; the last one is kept so the bar can slide away with it after the call ends. */
private data class BarModel(
    val isOne: Boolean, val title: String, val line2: String?, val video: Boolean, val muted: Boolean,
)

/** When this phone joined each group call, for the bar's clock (the group state carries no start). */
private object GroupClock {
    var cid: String? = null
    var since: Long = 0L
    fun since(id: String?): Long {
        if (id != cid) { cid = id; since = System.currentTimeMillis() }
        return since
    }
}

/**
 * The bar across the top of the app during a call (owner 2026-11-08, the reference's; made thin
 * 2026-11-11, owner: it took too much room): 40dp under
 * the status bar, my mic in a dark circle on the left, the call's name and clock in green in the
 * middle (a tap goes back to the call; a group adds "N in call" under it), the red end on the
 * right. It takes the status bar's strip with it, so the app below starts under it.
 */
@Composable
fun OngoingCallBar() {
    val ctx = LocalContext.current
    val one by CallManager.ui.collectAsState()
    val group by GroupCall.ui.collectAsState()
    val isOne = one.status.live
    val isGroup = !isOne && (group.joined || group.connecting)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val live: BarModel? = when {
        isOne -> BarModel(
            isOne = true,
            title = "${one.peerName.ifEmpty { "Call" }} · ${CallMachine.label(one.status, now)}",
            line2 = null,
            video = one.cameraOn || one.remoteCameraOn,
            muted = one.muted,
        )
        isGroup -> {
            val name = group.title.ifEmpty { "Group call" }
            val title = if (group.joined) "$name · ${CallMachine.clock(((now - GroupClock.since(group.cid)) / 1000).toInt())}" else name
            val n = group.members.size
            BarModel(isOne = false, title = title, line2 = if (n > 1) "$n in call" else null, video = group.cameraOn, muted = !group.micOn)
        }
        else -> null
    }
    var last by remember { mutableStateOf<BarModel?>(null) }
    if (live != null) last = live
    val m = live ?: last ?: return
    // Light status bar icons over the dark bar, whatever the app's own theme says; back on leaving.
    val view = LocalView.current
    val appDark = com.fariin.app.ui.theme.LocalFariinColors.current.dark
    SideEffect {
        (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = false }
    }
    DisposableEffect(Unit) {
        onDispose {
            (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !appDark }
        }
    }
    val green = Color(0xFF34C759)
    Row(Modifier.fillMaxWidth().background(CallBarBackdrop).statusBarsPadding().height(40.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFF24262B)).clickable {
            if (m.isOne) CallManager.toggleMute() else GroupCall.toggleMic()
        }.semantics { contentDescription = if (m.muted) "Unmute" else "Mute" }, contentAlignment = Alignment.Center) {
            Icon(if (m.muted) androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_mic_off) else androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_mic), null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)).clickable { CallActivity.startRing(ctx) }
            .padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (m.video) { Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_video), null, tint = green, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)) }
                Text(m.title, color = green, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            m.line2?.let { Text(it, color = Color(0xFF9EA3AB), fontSize = 11.sp, lineHeight = 12.sp, maxLines = 1) }
        }
        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFFFF3B30)).clickable {
            if (m.isOne) CallManager.hangUp() else GroupCall.leave()
        }.semantics { contentDescription = "End call" }, contentAlignment = Alignment.Center) {
            Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_phone_down), null, tint = Color.White, modifier = Modifier.size(17.dp))
        }
    }
}

/**
 * The app's card under the call bar: the top two corners rounded with a smooth (continuous) curve,
 * the bottom square to the screen's edge. The curve starts 1.3 radii from the corner and eases in,
 * so it reads as an iOS corner rather than a quarter circle.
 */
class CallCardShape(private val radiusPx: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val p = (radiusPx * 1.3f).coerceAtMost(minOf(size.width, size.height) / 2f)
        if (p <= 0.5f) return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        val k = p * 0.27f
        val w = size.width
        val path = Path().apply {
            moveTo(0f, size.height)
            lineTo(0f, p)
            cubicTo(0f, k, k, 0f, p, 0f)
            lineTo(w - p, 0f)
            cubicTo(w - k, 0f, w, k, w, p)
            lineTo(w, size.height)
            close()
        }
        return Outline.Generic(path)
    }
}
