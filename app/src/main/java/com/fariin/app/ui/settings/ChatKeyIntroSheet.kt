package com.fariin.app.ui.settings

import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.components.FariinSheetDialog
import com.fariin.app.ui.components.FloatingIcon
import com.fariin.app.ui.components.rememberSheetMotion
import com.fariin.app.ui.components.sheetDrag
import com.fariin.app.ui.theme.Fariin

/**
 * "What is a Chat Key?" (iOS ChatKeyIntroSheet, owner 2026-09-11): shown before a key is made from
 * the Chat Key page, and before "People who know my key" is chosen without a key. "Got it" carries on
 * into what was tapped; the X, Back, a tap on the dim or a swipe down leave everything as it was.
 *
 * [onContinue] runs only after the sheet has fully gone, so the next step never opens under it.
 */
@Composable
internal fun ChatKeyIntroSheet(onDismiss: () -> Unit, onContinue: () -> Unit) {
    val c = Fariin.colors
    val go = remember { booleanArrayOf(false) }
    val motion = rememberSheetMotion { onDismiss(); if (go[0]) onContinue() }
    // As tall as its words (iOS sizes this sheet to its content): the sheet rests with about
    // [SheetHeight] showing; a drag up takes it to full height on a short phone.
    val density = LocalDensity.current
    val windowH = LocalWindowInfo.current.containerSize.height
    val statusTop = WindowInsets.statusBars.getTop(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    motion.mid = with(density) { windowH - statusTop - 10.dp.toPx() - SheetHeight.toPx() - navBottom }.coerceAtLeast(0f)
    // iOS uses the outgoing-bubble blue here (Theme.defaultBubble), the app's one blue.
    val accent = c.blue
    FariinSheetDialog(motion, fill = c.bgSecondary) { _ ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🔑", fontSize = 80.sp, modifier = Modifier.padding(top = 36.dp))
                Text("What is a Chat Key?", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.label,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
                Column(Modifier.fillMaxWidth().padding(top = 30.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Point(ImageVector.vectorResource(R.drawable.ic_sf_lock), accent, "Private and secure",
                        "Fariin makes a random 6-digit key that nobody else has. We keep only a scrambled copy that cannot be turned back into your key.")
                    Point(ImageVector.vectorResource(R.drawable.ic_sf_person2), accent, "Direct communication",
                        "People with your Chat Key can message and call you straight away, whatever your Messages and Calls settings say.")
                    Point(ImageVector.vectorResource(R.drawable.ic_sf_checkmark_shield), accent, "You are in control",
                        "Share it only with people you want to hear from. Reset it for a new one, or remove it, whenever you like.")
                }
                Box(Modifier.padding(top = 32.dp, bottom = 16.dp).fillMaxWidth().height(52.dp).clip(CircleShape).background(accent)
                    .clickable(onClickLabel = "Got it") { go[0] = true; motion.close() },
                    contentAlignment = Alignment.Center) {
                    Text("Got it", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
            // The top strip drags the sheet; the X sits in its corner, as on iOS.
            Box(Modifier.fillMaxWidth().height(28.dp).sheetDrag(motion))
            FloatingIcon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), contentDescription = "Close", onClick = { motion.close() },
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp), circle = true)
        }
    }
}

/** How much of the sheet shows at rest: the key, the title, three points and the button. */
private val SheetHeight = 620.dp

@Composable
private fun Point(icon: ImageVector, tint: Color, title: String, body: String) {
    val c = Fariin.colors
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(26.dp))
        Box(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
            Text(body, fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
