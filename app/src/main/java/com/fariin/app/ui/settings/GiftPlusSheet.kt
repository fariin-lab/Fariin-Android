package com.fariin.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.fariin.app.R
import com.fariin.app.data.PlusStore
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.FariinSheetDialog
import com.fariin.app.ui.components.FariinSpinner
import com.fariin.app.ui.components.FloatingIcon
import com.fariin.app.ui.components.cachedVector
import com.fariin.app.ui.components.rememberSheetMotion
import com.fariin.app.ui.components.sheetDrag
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Fariin Plus: "Gift Fariin Plus" (iOS PlusGiftSheet). One card per giftMonths length, Send Gift calls
 * plusGift({to, months}); the server checks everything again. No payment here. Closes when Plus stops
 * being visible or gifts turn off.
 */
@Composable
fun GiftPlusSheet(toUid: String, toName: String, toPhoto: String?, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val motion = rememberSheetMotion { onDismiss() }
    val gone = !PlusStore.visible || PlusStore.state("gifts") == PlusStore.State.OFF
    LaunchedEffect(gone) { if (gone) motion.close() }
    val months = PlusStore.giftMonths
    var picked by remember { mutableIntStateOf(if (months.size >= 3) months[months.size / 2] else months.firstOrNull() ?: 1) }
    var busy by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf<Int?>(null) }
    var alert by remember { mutableStateOf<String?>(null) }
    val locked = PlusStore.state("gifts") == PlusStore.State.LOCKED
    // iOS shownName: a person without a name reads "this person".
    val shownName = toName.ifBlank { "this person" }
    val density = LocalDensity.current
    val windowH = LocalWindowInfo.current.containerSize.height
    val want = with(density) { (windowH - WindowInsets.statusBars.getTop(density) - 10.dp.toPx() - 470.dp.toPx() - WindowInsets.navigationBars.getBottom(density)).coerceAtLeast(0f) }
    if (motion.mid <= 0f) motion.mid = want
    androidx.compose.runtime.SideEffect { motion.fitMid(want) }

    fun send() {
        if (busy || locked) return
        busy = true
        scope.launch {
            try {
                FirebaseFunctions.getInstance(PlusStore.REGION).getHttpsCallable("plusGift")
                    .call(mapOf("to" to toUid, "months" to picked)).await()
                PlusStore.forgetBadge(toUid)
                sent = picked
            } catch (e: Exception) {
                // Refusals carry details.code and the server's own words; anything else is generic.
                alert = if (PlusStore.detailsCode(e) != null && !e.message.isNullOrBlank()) e.message
                    else "Couldn't send the gift. Check your connection and try again."
            } finally { busy = false }
        }
    }

    FariinSheetDialog(motion, fill = c.bgSecondary) { _ ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(36.dp))
                val done = sent
                if (done != null) {
                    Icon(ImageVector.cachedVector(R.drawable.ic_gift), contentDescription = null, tint = PlusBlue, modifier = Modifier.size(56.dp))
                    Text("Gift Sent", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label, modifier = Modifier.padding(top = 14.dp))
                    Text("Fariin Plus for ${monthsWord(done)} is on for $shownName now.", fontSize = 16.sp, color = c.secondary,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(28.dp))
                    BlueCapsule("Done", enabled = true) { motion.close() }
                } else {
                    Text("Gift Fariin Plus", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.label)
                    Spacer(Modifier.height(14.dp))
                    Avatar(toPhoto, 64.dp)
                    Text("To $shownName", fontSize = 16.sp, color = c.label, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        months.forEach { m ->
                            val on = m == picked
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(c.card)
                                .border(BorderStroke(if (on) 2.dp else 0.dp, if (on) PlusBlue else Color.Transparent), RoundedCornerShape(18.dp))
                                .clickable { picked = m }.padding(vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$m", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.label)
                                Text(if (m == 1) "month" else "months", fontSize = 14.sp, color = c.secondary)
                            }
                        }
                    }
                    Text("$shownName gets a message from Fariin, and Plus turns on right away.", fontSize = 14.sp, color = c.secondary,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
                    Spacer(Modifier.height(22.dp))
                    BlueCapsule("Send Gift", enabled = !locked && !busy, busy = busy) { send() }
                    if (locked) Text("Sending gifts comes with Fariin Plus.", fontSize = 13.sp, color = c.secondary,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(28.dp).sheetDrag(motion))
            FloatingIcon(ImageVector.cachedVector(R.drawable.ic_sf_xmark), contentDescription = "Close", onClick = { motion.close() },
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp), circle = true)
        }
    }
    alert?.let { msg ->
        AlertDialog(onDismissRequest = { alert = null }, title = { Text("Gift not sent") }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = { alert = null }) { Text("OK") } })
    }
}

private fun monthsWord(n: Int) = if (n == 1) "1 month" else "$n months"

@Composable
private fun BlueCapsule(text: String, enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(if (enabled || busy) PlusBlue else PlusBlue.copy(alpha = 0.4f))
        .clickable(enabled = enabled) { onClick() }, contentAlignment = Alignment.Center) {
        if (busy) FariinSpinner(size = 22.dp, color = Color.White)
        else Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}
