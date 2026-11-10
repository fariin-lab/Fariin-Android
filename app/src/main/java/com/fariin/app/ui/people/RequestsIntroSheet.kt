package com.fariin.app.ui.people

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.ui.components.FariinSheetDialog
import com.fariin.app.ui.components.FloatingIcon
import com.fariin.app.ui.components.rememberSheetMotion
import com.fariin.app.ui.components.sheetDrag
import com.fariin.app.ui.theme.Fariin

/**
 * The first-time card on Message Requests (iOS MessageRequestsView introSheet, owner 2026-10-15):
 * shown the first time the page opens and never again on this install. Our own sheet: the request
 * icon in a soft circle, the title, one line, "Got it" and "Learn more" (fariin.com/help).
 */
object RequestsIntro {
    private const val PREFS = "fariin_requests"
    private const val KEY = "messageRequests.introSeen"
    fun seen(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun markSeen(c: Context) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply() }
}

@Composable
internal fun RequestsIntroSheet(onDismiss: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val motion = rememberSheetMotion { RequestsIntro.markSeen(ctx); onDismiss() }
    // As tall as its words, measured (the Chat Key intro's way).
    val density = LocalDensity.current
    val windowH = LocalWindowInfo.current.containerSize.height
    val statusTop = WindowInsets.statusBars.getTop(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    var contentH by remember { mutableIntStateOf(0) }
    val restH = if (contentH > 0) contentH.toFloat() else with(density) { 372.dp.toPx() }
    val wantMid = with(density) { windowH - statusTop - 10.dp.toPx() - restH - navBottom }.coerceAtLeast(0f)
    if (motion.mid <= 0f) motion.mid = wantMid
    androidx.compose.runtime.SideEffect { motion.fitMid(wantMid) }
    val accent = c.blue
    FariinSheetDialog(motion, fill = c.bgSecondary) { _ ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .onSizeChanged { if (it.height > 0) contentH = it.height }.padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.padding(top = 30.dp).size(72.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_message_request), contentDescription = null,
                        tint = accent, modifier = Modifier.size(34.dp))
                }
                Text("Message requests", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
                Text("Messages from people you haven’t chatted with before arrive here as requests. To reply, accept the request.",
                    fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp))
                Box(Modifier.padding(top = 24.dp).fillMaxWidth().height(52.dp).clip(CircleShape).background(accent)
                    .clickable(onClickLabel = "Got it") { motion.close() },
                    contentAlignment = Alignment.Center) {
                    Text("Got it", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
                Box(Modifier.padding(bottom = 12.dp).fillMaxWidth().height(44.dp)
                    .clickable(onClickLabel = "Learn more") { com.fariin.app.ui.settings.openWeb(ctx, "https://fariin.com/help") },
                    contentAlignment = Alignment.Center) {
                    Text("Learn more", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = accent)
                }
            }
            Box(Modifier.fillMaxWidth().height(28.dp).sheetDrag(motion))
            FloatingIcon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), contentDescription = "Close", onClick = { motion.close() },
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp), circle = true)
        }
    }
}
