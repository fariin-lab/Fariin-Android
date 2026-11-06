package com.fariin.app.ui.thread

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Place
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.crypto.Wire
import com.fariin.app.data.CardRules
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.people.LocalPeopleNav
import com.fariin.app.ui.theme.Fariin

/**
 * A shared place (iOS location bubble): a small map of the spot with its pin (LocationMaps.kt), the
 * place's name ("Location" when none travelled) beside a red pin, the time. An approximate share
 * shows the town zoomed out with a soft circle and says so. Tap opens the full-screen map with
 * Directions and Copy coordinates.
 */
@Composable
internal fun LocationBody(loc: CardRules.Location, isMe: Boolean, width: Dp, meta: @Composable () -> Unit) {
    val c = Fariin.colors
    val ink = if (isMe) Color.White else c.label
    val sub = if (isMe) Color.White.copy(alpha = 0.75f) else c.secondary
    val approx = remember(loc) { com.fariin.app.data.LocationShare.looksApproximate(loc) }
    var open by remember { mutableStateOf(false) }
    Column(Modifier.width(width)) {
        Column(Modifier.fillMaxWidth().padding(top = 3.dp).clip(RoundedCornerShape(12.dp)).clickable { open = true }) {
            LocationCardMap(loc, approx, width, 140.dp)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(if (isMe) Color.White else c.red), contentAlignment = Alignment.Center) {
                    Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_location), null, tint = if (isMe) c.red else Color.White, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(CardRules.title(loc), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (approx) Text("Approximate area", fontSize = 13.sp, color = sub, maxLines = 1)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = sub, modifier = Modifier.size(22.dp))
            }
        }
        Box(Modifier.align(Alignment.End)) { meta() }
    }
    if (open) LocationMapPage(loc, approx) { open = false }
}

/**
 * A shared contact (iOS contact bubble): their photo, name and a chevron (tap opens their
 * profile), the time, and a Message button that opens a chat with them. No button on my own card
 * or on the card of the person this chat is already with.
 */
@Composable
internal fun ContactBody(card: CardRules.Contact, isMe: Boolean, me: String, cid: String, width: Dp,
                         meta: @Composable () -> Unit) {
    val c = Fariin.colors
    val nav = LocalPeopleNav.current
    val ink = if (isMe) Color.White else c.label
    val sub = if (isMe) Color.White.copy(alpha = 0.75f) else c.secondary
    Column(Modifier.width(width)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .clickable { if (card.uid == me) nav.myProfile() else nav.contact(card.uid) }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(card.photo, 44.dp)
            Spacer(Modifier.width(10.dp))
            Text(card.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = sub, modifier = Modifier.size(22.dp))
        }
        Box(Modifier.align(Alignment.End)) { meta() }
        if (CardRules.canMessage(card, me, cid)) {
            Box(
                Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp).height(40.dp).clip(CircleShape)
                    .background(if (isMe) Color.White.copy(alpha = 0.22f) else c.label.copy(alpha = 0.08f))
                    .clickable { nav.chat(Wire.directCid(me, card.uid)) },
                contentAlignment = Alignment.Center,
            ) { Text("Message", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = ink) }
        }
    }
}
