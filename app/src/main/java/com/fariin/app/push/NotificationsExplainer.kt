package com.fariin.app.push

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.ui.theme.Fariin

/**
 * The first-run notifications page (iOS FirstRunViews.NotificationsExplainerView), shown right after
 * sign-in on a phone that was never asked. Continue asks Android's question and waits for the answer
 * before moving on, so the question sits over this page and not over a half-drawn chat list. Not now
 * asks nothing. Either way the page is answered for good on this install.
 */
@Composable
fun NotificationsExplainer(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    var busy by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        Push.register(ctx)
        onDone()
    }
    fun finish(allow: Boolean) {
        if (busy) return
        busy = true
        Push.markExplainerSeen(ctx)
        if (allow) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else { Push.register(ctx); onDone() }
    }
    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Icon(
            painterResource(R.drawable.ic_notifications), contentDescription = null,
            tint = c.blue, modifier = Modifier.size(108.dp).padding(16.dp),
        )
        Text(
            "Allow notifications so you don't miss messages",
            fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center,
            lineHeight = 32.sp, modifier = Modifier.padding(top = 22.dp),
        )
        Text(
            "Know right away when you get new messages or calls. Fariin never asks for your contacts.",
            fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.weight(2f))
        Box(
            Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(c.accent)
                .clickable(enabled = !busy) { finish(true) },
            contentAlignment = Alignment.Center,
        ) { Text("Continue", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent) }
        Box(
            Modifier.fillMaxWidth().height(44.dp).padding(top = 4.dp).clickable(enabled = !busy) { finish(false) },
            contentAlignment = Alignment.Center,
        ) { Text("Not now", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.secondary) }
        Spacer(Modifier.height(8.dp))
    }
}
