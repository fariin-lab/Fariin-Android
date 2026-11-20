package com.fariin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fariin.app.R
import com.fariin.app.data.GeoBlock
import com.fariin.app.ui.theme.Fariin

/**
 * "Not available in your region" (iOS 394c178fb): an opaque window over the whole app while
 * [GeoBlock] says this account is blocked, so no chat shows behind it. Try Again asks the server
 * (Checking... for about 3 s); Sign Out is the normal log out.
 */
@Composable
fun GeoBlockGate(me: String, onSignOut: () -> Unit) {
    val b = GeoBlock.blocked ?: return
    if (b.first != me) return
    val c = Fariin.colors
    var checking by remember { mutableStateOf(false) }
    if (checking) LaunchedEffect(Unit) { com.fariin.app.data.AppConfig.askNow(); kotlinx.coroutines.delay(3000); checking = false }
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(c.bg).padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(88.dp).clip(CircleShape).background(c.blue.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(ImageVector.cachedVector(R.drawable.ic_sf_globe), null, tint = c.blue, modifier = Modifier.size(44.dp))
                }
                Spacer(Modifier.height(24.dp))
                Text("Not available in your region", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(b.second, fontSize = 16.sp, color = c.secondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                Box(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp)).background(c.accent)
                    .clickable(enabled = !checking) { checking = true }, contentAlignment = Alignment.Center) {
                    Text(if (checking) "Checking…" else "Try Again", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp)).clickable(onClick = onSignOut),
                    contentAlignment = Alignment.Center) {
                    Text("Sign Out", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.red)
                }
            }
        }
    }
}
