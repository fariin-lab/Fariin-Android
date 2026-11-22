package com.fariin.app.ui.people

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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.components.FariinSheetDialog
import com.fariin.app.ui.components.VerifiedSeal
import com.fariin.app.ui.components.rememberSheetMotion
import com.fariin.app.ui.components.sheetDrag
import com.fariin.app.ui.theme.Fariin

/**
 * What the tick claims (iOS VerificationInfoSheet): a tap on a person's seal on their profile opens
 * this. The big seal, "Verified", what the badge's type says, the fixed line, and "Got it".
 * A tick typed into a name has no sheet behind it, which is the point.
 */
@Composable
internal fun VerifiedInfoSheet(kind: String?, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val motion = rememberSheetMotion(onDismiss)
    // As tall as its words, measured (RequestsIntroSheet's way).
    val density = LocalDensity.current
    val windowH = LocalWindowInfo.current.containerSize.height
    val statusTop = WindowInsets.statusBars.getTop(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    var contentH by remember { mutableIntStateOf(0) }
    val restH = if (contentH > 0) contentH.toFloat() else with(density) { 340.dp.toPx() }
    val wantMid = with(density) { windowH - statusTop - 10.dp.toPx() - restH - navBottom }.coerceAtLeast(0f)
    if (motion.mid <= 0f) motion.mid = wantMid
    androidx.compose.runtime.SideEffect { motion.fitMid(wantMid) }
    val seal = if (com.fariin.app.data.VerifiedPeople.isGold(kind)) Color(0xFFE8B21E) else c.blue
    FariinSheetDialog(motion, fill = c.bgSecondary) { _ ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .onSizeChanged { if (it.height > 0) contentH = it.height }.padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                VerifiedSeal(seal, Modifier.padding(top = 28.dp).size(56.dp), label = null)
                Text("Verified", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
                Text(verifiedExplanation(kind), fontSize = 16.sp, color = c.secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 18.dp, start = 12.dp, end = 12.dp))
                Text("Fariin reviewed this account and confirmed it is genuine. Verification is granted by Fariin only. It can never be bought or requested.",
                    fontSize = 13.sp, color = c.secondary.copy(alpha = 0.7f), textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 18.dp, start = 12.dp, end = 12.dp))
                Box(Modifier.padding(top = 24.dp, bottom = 12.dp).fillMaxWidth().height(50.dp).clip(CircleShape).background(c.blue)
                    .clickable(onClickLabel = "Got it") { motion.close() },
                    contentAlignment = Alignment.Center) {
                    Text("Got it", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
            Box(Modifier.fillMaxWidth().height(28.dp).sheetDrag(motion))
        }
    }
}

/** iOS Verification.Kind.explanation; an unknown or empty type says what every badge means. */
internal fun verifiedExplanation(kind: String?): String = when (kind) {
    "official" -> "This is an official Fariin account."
    "business" -> "This business has been verified as genuine."
    "public_figure" -> "This person's identity has been verified."
    "organization" -> "This organization has been verified as genuine."
    "government" -> "This is a verified government account."
    "media" -> "This news organization has been verified as genuine."
    "creator" -> "This creator's identity has been verified."
    else -> "This account has been verified as genuine."
}
