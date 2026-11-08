package com.fariin.app.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.call.CallManager
import com.fariin.app.call.GroupCall
import kotlinx.coroutines.delay

/** True while a 1:1 or group call is going on (the bar shows over the app). */
@Composable
fun ongoingCallShown(): Boolean {
    val one by CallManager.ui.collectAsState()
    val group by GroupCall.ui.collectAsState()
    return one.status.live || group.joined || group.connecting
}

/**
 * The bar across the top of the app during a call (owner 2026-11-08, the reference's): my mic on the
 * left, the call's name and clock in green in the middle (a tap goes back to the call), the red end
 * on the right. It takes the status bar's strip with it, so the app below starts under it.
 */
@Composable
fun OngoingCallBar() {
    val ctx = LocalContext.current
    val one by CallManager.ui.collectAsState()
    val group by GroupCall.ui.collectAsState()
    val isOne = one.status.live
    val isGroup = !isOne && (group.joined || group.connecting)
    if (!isOne && !isGroup) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val green = Color(0xFF34C759)
    val title = if (isOne) {
        val at = one.status.connectedAtMs
        val clock = if (at != null && at > 0) com.fariin.app.call.CallMachine.label(one.status, now) else com.fariin.app.call.CallMachine.label(one.status, now)
        "${one.peerName.ifEmpty { "Call" }} - $clock"
    } else {
        val n = group.members.size
        if (n > 1) "${group.title.ifEmpty { "Group call" }} - $n in call" else group.title.ifEmpty { "Group call" }
    }
    val video = if (isOne) one.cameraOn || one.remoteCameraOn else group.cameraOn
    val muted = if (isOne) one.muted else !group.micOn
    Row(Modifier.fillMaxWidth().background(Color(0xFF1C1C1E)).statusBarsPadding().height(56.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable {
            if (isOne) CallManager.toggleMute() else GroupCall.toggleMic()
        }.semantics { contentDescription = if (muted) "Unmute" else "Mute" }, contentAlignment = Alignment.Center) {
            Icon(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Row(Modifier.weight(1f).clip(CircleShape).clickable { CallActivity.startRing(ctx) }.padding(vertical = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (video) { Icon(Icons.Filled.Videocam, null, tint = green, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
            Text(title, color = green, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFFF3B30)).clickable {
            if (isOne) CallManager.hangUp() else GroupCall.leave()
        }.semantics { contentDescription = "End call" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.CallEnd, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}
