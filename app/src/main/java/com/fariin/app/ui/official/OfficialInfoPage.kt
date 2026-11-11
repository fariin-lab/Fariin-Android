package com.fariin.app.ui.official

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fariin.app.R
import com.fariin.app.data.OfficialChannel
import com.fariin.app.ui.components.GlassAlert
import com.fariin.app.ui.theme.Fariin

/**
 * The official chat's profile (iOS OfficialChatInfoView, owner 2026-11-11: Android had none, the
 * header did nothing). A large card: the mark, "Fariin" with the tick, "Official Chat"; the Mute
 * and Search circles; About in one short text; All Media; Help Center; Clear Chat in red (no
 * Block, iOS 2026-10-31; Unblock only for someone who blocked it before).
 */
@Composable
fun OfficialInfoPage(onBack: () -> Unit, onSearch: () -> Unit, onOpenImage: (String) -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val view by OfficialChannel.view.collectAsState()
    val is24h = android.text.format.DateFormat.is24HourFormat(ctx)
    var confirmClear by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    val media = remember(view.visible) { view.visible.filter { it.mediaUrl != null }.reversed() }
    val muted = view.state.isMutedNow(System.currentTimeMillis().toDouble())

    if (confirmClear) GlassAlert(onDismissRequest = { confirmClear = false }, title = { Text("Clear this chat?") },
        text = { Text("Removes these messages from this phone. New updates will still arrive.") },
        confirmButton = { TextButton({ OfficialChannel.clearHistory(); confirmClear = false }) { Text("Clear", color = c.red) } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel", color = c.accent) } }, containerColor = c.card)

    Box(Modifier.fillMaxSize().background(c.bgSecondary)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = 48.dp)
            .padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            // The card: the mark, the name with its tick, "Official Chat".
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                OfficialAvatar(112.dp)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(OfficialChannel.NAME, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.label)
                    Spacer(Modifier.width(6.dp))
                    com.fariin.app.ui.components.VerifiedSeal(c.blue, Modifier.size(22.dp), "Official")
                }
                Text(OfficialChannel.SUBTITLE, fontSize = 17.sp, color = c.secondary, modifier = Modifier.padding(top = 2.dp))
            }
            // Mute (its menu) and Search, each in a fifth of the row as a normal profile's circles.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Box {
                    var muteOpen by remember { mutableStateOf(false) }
                    Circle(painterResource(if (muted) R.drawable.ic_bell_off_outline else R.drawable.ic_notifications),
                        if (muted) "Notifications off" else "Mute") { muteOpen = true }
                    val circles = com.fariin.app.data.GroupInfoCircles
                    com.fariin.app.ui.components.GlassDropdown(muteOpen, onDismissRequest = { muteOpen = false },
                        shape = com.fariin.app.ui.components.FariinMenu.shape,
                        border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card) {
                        val until = view.state.mutedUntil
                        Text(if (!muted) circles.MUTE_HEADER else if (until <= 0.0) "Muted always"
                            else circles.mutedLabel(until, System.currentTimeMillis(), is24h),
                            fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        if (muted) DropdownMenuItem(text = { Text("Unmute", color = c.label) },
                            onClick = { muteOpen = false; OfficialChannel.setMuted(false) })
                        else circles.MUTE_CHOICES.forEach { (label, hours) ->
                            DropdownMenuItem(text = { Text(label, color = c.label) },
                                onClick = { muteOpen = false; OfficialChannel.setMuted(true, circles.muteUntil(hours)) })
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Circle(androidx.compose.ui.graphics.vector.rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_search_ios)), "Search", onSearch)
            }
            // About: one short text (owner 2026-09-29, word for word as iOS).
            Card {
                Text("The only official chat from Fariin\nKeep up to date with news & release notes",
                    fontSize = 17.sp, color = c.label, modifier = Modifier.padding(18.dp))
            }
            if (media.isNotEmpty()) Card {
                Column(Modifier.padding(16.dp)) {
                    Text("All Media", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        media.take(12).forEach { a ->
                            AsyncImage(a.mediaUrl, contentDescription = "Photo", contentScale = ContentScale.Crop,
                                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(12.dp))
                                    .clickable { a.mediaUrl?.let(onOpenImage) })
                        }
                    }
                }
            }
            // The same Help Center Settings opens.
            Card {
                Row(Modifier.fillMaxWidth().clickable {
                    runCatching { com.fariin.app.ui.browser.BrowserActivity.open(ctx, "https://fariin.com/help") }
                }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_set_help), null, tint = c.label, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Help Center", fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                    Icon(ImageVector.vectorResource(R.drawable.ic_sf_chevron_right), null, tint = c.secondary, modifier = Modifier.size(14.dp))
                }
            }
            Card {
                Column {
                    Text("Clear Chat", fontSize = 17.sp, color = c.red,
                        modifier = Modifier.fillMaxWidth().clickable { confirmClear = true }.padding(18.dp))
                    if (view.state.blocked) Text("Unblock", fontSize = 17.sp, color = c.accent,
                        modifier = Modifier.fillMaxWidth().clickable { OfficialChannel.setBlocked(false) }.padding(18.dp))
                }
            }
        }
        // The page's bar: back only, on the page colour.
        Box(Modifier.fillMaxWidth().background(c.bgSecondary).statusBarsPadding().height(48.dp)) {
            com.fariin.app.ui.components.FloatingBack(onBack, Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Fariin.colors.card)) { content() }
}

@Composable
private fun Circle(painter: androidx.compose.ui.graphics.painter.Painter, label: String, onClick: () -> Unit) {
    val c = Fariin.colors
    Box(Modifier.size(60.dp).clip(CircleShape).background(c.card).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painter, contentDescription = label, tint = c.label, modifier = Modifier.size(24.dp))
    }
}
