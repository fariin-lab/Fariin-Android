package com.fariin.app.ui.call

import androidx.compose.ui.res.vectorResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.CallEntry
import com.fariin.app.data.CallRun
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.theme.Fariin
import java.text.DateFormat
import java.util.Date

/** The words of the call info page, pure so they are unit tested (iOS ContactInfoView callLogCard). */
object CallInfoText {
    /** "Outgoing voice call", "Incoming video call", "Missed voice call" (missed only when they called). */
    fun kind(e: CallEntry, me: String, nowMs: Long): String {
        val k = if (e.video) "video call" else "voice call"
        return when {
            e.missedIncoming(me, nowMs) -> "Missed $k"
            e.mine(me) -> "Outgoing $k"
            else -> "Incoming $k"
        }
    }

    /** The length of an answered call: "45 sec", "3 min", "1 hr 5 min"; nothing for a missed one. */
    fun length(e: CallEntry, nowMs: Long): String? {
        if (e.missed(nowMs) || e.durationSec <= 0) return null
        val s = e.durationSec
        if (s < 60) return "$s sec"
        val m = s / 60
        if (m < 60) return "$m min"
        return if (m % 60 == 0) "${m / 60} hr" else "${m / 60} hr ${m % 60} min"
    }

    /** A row left "No answer" when I called and nobody picked up. */
    fun note(e: CallEntry, me: String, nowMs: Long): String? =
        if (e.mine(me) && e.missed(nowMs)) (if (e.outcome == "declined") "Declined" else "No answer") else null
}

/**
 * The (i) page of a Calls tab row: the photo and name, voice and video buttons that call back
 * (a group's call for a group row), and the calls in that row with their time and length, newest
 * first, under the day they were made.
 */
@Composable
fun CallInfoScreen(run: CallRun, me: String, onBack: () -> Unit, onCall: (video: Boolean) -> Unit) {
    BackHandler(onBack = onBack)
    val c = Fariin.colors
    val call = run.latest
    val now = System.currentTimeMillis()
    val barScroll = rememberScrollState()
    com.fariin.app.ui.components.GlassColumnPage(com.fariin.app.ui.components.underBar(barScroll), Modifier.background(c.bg), page = c.bg, barEstimate = 52.dp, bar = {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().background(c.bg).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                com.fariin.app.ui.components.FloatingBack(onBack, Modifier.padding(start = 4.dp))
                Text("Call info", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(48.dp))
            }
        }
    }) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(top = top).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            Avatar(call.photoUrl, 96.dp, squircle = call.groupCid != null, kind = com.fariin.app.ui.components.AvatarKind.of(call.groupCid != null, false))
            Spacer(Modifier.height(12.dp))
            Text(call.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
            if (call.groupCid != null) {
                Spacer(Modifier.height(2.dp))
                Text("Group", fontSize = 15.sp, color = c.secondary)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_voice), "Voice", Modifier.weight(1f)) { onCall(false) }
                Tile(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_video), "Video", Modifier.weight(1f)) { onCall(true) }
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.fieldFill).padding(14.dp)) {
                Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(call.dateMs)), fontSize = 15.sp, color = c.secondary)
                for (e in run.entries) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val missed = e.missedIncoming(me, now)
                        Icon(when { missed -> androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_arrow_down_left); e.mine(me) -> androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_arrow_up_right); else -> androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_arrow_down_left) },
                            null, tint = if (missed) c.red else c.secondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(CallInfoText.kind(e, me, now), fontSize = 15.sp, color = if (missed) c.red else c.label)
                            val sub = CallInfoText.length(e, now) ?: CallInfoText.note(e, me, now)
                            if (sub != null) Text(sub, fontSize = 13.sp, color = c.secondary)
                        }
                        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.dateMs)), fontSize = 15.sp, color = c.secondary)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Tile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Fariin.colors
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(c.fieldFill).clickable(onClick = onClick)
        .semantics { contentDescription = "$label call" }.padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = c.label, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 13.sp, color = c.label)
    }
}

