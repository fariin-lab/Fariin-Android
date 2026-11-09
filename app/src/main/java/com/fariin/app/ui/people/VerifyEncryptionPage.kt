package com.fariin.app.ui.people

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.fariin.app.crypto.SafetyNumber
import com.fariin.app.data.QrRules
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin

/**
 * Encryption (iOS VerifyEncryptionView, owner screenshots 2026-11-04): both faces overlapping with
 * the lock where they meet, "End-to-end encrypted", one grey card holding the QR of the safety
 * number on white and the twelve groups of five in four columns, the hint, and Scan their code.
 * A scan that matches marks this number Verified on this phone (keyed by the number, so a new key
 * on either side clears it, as iOS).
 */
@Composable
internal fun VerifyEncryptionPage(cid: String, peerName: String, peerPhoto: String?, myPhoto: String?,
                                  number: String?, onClose: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("fariin_verify", android.content.Context.MODE_PRIVATE) }
    fun key(n: String) = "fariin_verified_${cid}_$n"
    var verified by remember(number) { mutableStateOf(number != null && prefs.getBoolean(key(number), false)) }
    var scanning by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<String?>(null) }
    BackHandler { if (scanning) scanning = false else onClose() }

    if (scanning) {
        VerifyScanner(onClose = { scanning = false }) { code ->
            scanning = false
            val scanned = SafetyNumber.numberFromQR(code)
            outcome = when {
                scanned == null -> "invalid"
                scanned == number -> { verified = true; prefs.edit().putBoolean(key(number), true).apply(); "match" }
                else -> "mismatch"
            }
        }
        return
    }
    outcome?.let { o ->
        val (title, text) = when (o) {
            "match" -> "Verified" to "Your safety number with $peerName matches. This chat is end-to-end encrypted and confirmed."
            "mismatch" -> "Numbers don't match" to "The scanned code doesn't match your safety number. Your messages are still encrypted, but you couldn't confirm $peerName's device. Try scanning again, or compare the numbers by hand."
            else -> "Not a Fariin code" to "That QR code isn't a Fariin verification code. Ask $peerName to open Encryption on their side."
        }
        com.fariin.app.ui.components.GlassAlert(onDismissRequest = { outcome = null }, containerColor = c.card, title = { Text(title) }, text = { Text(text) },
            confirmButton = { TextButton({ outcome = null }) { Text(if (o == "match") "Done" else "OK", color = c.accent) } })
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp)) {
            Box(Modifier.align(Alignment.CenterStart).size(44.dp).floating(CircleShape).clickable(onClickLabel = "Close", onClick = onClose),
                contentAlignment = Alignment.Center) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), null, tint = c.label) }
            Text("Encryption", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.align(Alignment.Center))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            // The two faces overlap and the lock sits where they meet (a seal once verified).
            Box(contentAlignment = Alignment.BottomCenter) {
                Row(horizontalArrangement = Arrangement.spacedBy((-12).dp)) {
                    Avatar(peerPhoto, 52.dp, Modifier.border(3.dp, c.bg, CircleShape))
                    Avatar(myPhoto, 52.dp, Modifier.border(3.dp, c.bg, CircleShape))
                }
                Box(Modifier.offset(y = 10.dp).size(26.dp).floating(CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (verified) ImageVector.vectorResource(R.drawable.ic_sf_checkmark_seal_fill) else ImageVector.vectorResource(R.drawable.ic_sf_lock_fill), null, tint = c.label, modifier = Modifier.size(13.dp))
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(if (verified) "This chat is verified" else "End-to-end encrypted", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = c.label, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text("Only you and $peerName can read these messages and hear these calls. Not even Fariin.", fontSize = 14.sp,
                color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
            Spacer(Modifier.height(24.dp))
            if (number == null) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.bgSecondary).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Can't show the safety number yet", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text("$peerName's key isn't available yet. Try again when they have opened Fariin.", fontSize = 14.sp,
                        color = c.secondary, textAlign = TextAlign.Center)
                }
            } else {
                // One grey card: the QR on white (a code has to be read off white), then the number.
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.bgSecondary).padding(vertical = 20.dp, horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(170.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(16.dp)) {
                        val m = remember(number) { runCatching { QrRules.matrix(SafetyNumber.qrPayload(number)) }.getOrNull() }
                        if (m != null) Canvas(Modifier.fillMaxSize().aspectRatio(1f)) {
                            val cell = size.width / m.size
                            for (y in m.indices) for (x in m[y].indices) if (m[y][x])
                                drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("SAFETY NUMBER", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = c.secondary)
                    Spacer(Modifier.height(12.dp))
                    SafetyNumber.grouped(number).chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { g -> Text(g, fontSize = 18.sp, fontFamily = FontFamily.Monospace, color = c.label) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Compare these numbers with $peerName's phone, or scan the code on it. If they match, nobody is in between.",
                fontSize = 14.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.height(16.dp))
        }
        if (number != null) Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().height(54.dp).floating(CircleShape)
            .clickable { scanning = true }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_qrcode_viewfinder), null, tint = c.label, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(10.dp))
            Text(if (verified) "Scan again" else "Scan their code", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
        }
    }
}

/** The camera for the other phone's verification code (iOS VerifyScanSheet). */
@Composable
private fun VerifyScanner(onClose: () -> Unit, onCode: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var hasCamera by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> hasCamera = ok; if (!ok) onClose() }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (!hasCamera) ask.launch(Manifest.permission.CAMERA) }
    var done by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) com.fariin.app.ui.settings.CameraFeed(owner, onCamera = {}) { code -> if (!done) { done = true; onCode(code) } }
        Box(Modifier.statusBarsPadding().padding(12.dp).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClickLabel = "Close", onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), null, tint = Color.White)
        }
        Text("Point at their Fariin verification code", fontSize = 15.sp, color = Color.White, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(32.dp))
    }
}
