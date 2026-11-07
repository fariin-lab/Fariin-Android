package com.fariin.app.ui.thread

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.fariin.app.data.CardRules
import com.fariin.app.data.Conversation
import com.fariin.app.data.CryptoHub
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.components.floating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Share a contact: one of the people I have a 1:1 chat with (iOS sends their card from the
 * profile's Share with page; the attach sheet's own Contact tile was retired there with phone
 * numbers). Tap one, then Send. Their own profile name travels, never a name I saved for them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactPickSheet(me: String, cid: String, onDismiss: () -> Unit, onSend: (CardRules.Contact) -> Unit) {
    val c = Fariin.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<String?>(null) }
    val people = remember { CryptoHub.listedAll().filter { pickable(it, me, cid) }.sortedBy { it.displayName(me).lowercase() } }
    val shown = people.filter { query.isBlank() || it.displayName(me).contains(query.trim(), ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet,
        containerColor = com.fariin.app.ui.components.sheetFill, shape = com.fariin.app.ui.components.SheetShape) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Text("Share Contact", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp))
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(c.fieldFill)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                if (query.isEmpty()) Text("Search", fontSize = 17.sp, color = c.secondary)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                    cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            }
            if (people.isEmpty()) {
                Text("People you chat with show up here.", fontSize = 15.sp, color = c.secondary,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            } else if (shown.isEmpty()) {
                Text("No results", fontSize = 15.sp, color = c.secondary,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 520.dp)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp).clip(RoundedCornerShape(20.dp)).background(c.card)) {
                items(shown, key = { it.id }) { conv ->
                    val on = conv.id == chosen
                    Row(
                        Modifier.fillMaxWidth().clickable { chosen = if (on) null else conv.id }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(conv.displayPhoto(me), 44.dp, squircle = conv.isChannel, kind = com.fariin.app.ui.components.AvatarKind.of(conv.isGroup, conv.isChannel))
                        Spacer(Modifier.width(12.dp))
                        Text(conv.displayName(me), fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).background(if (on) c.blue else Color.Transparent)
                                .border(1.5.dp, if (on) c.blue else c.secondary.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
            val pick = people.firstOrNull { it.id == chosen }
            Box(
                Modifier.fillMaxWidth().padding(16.dp).height(50.dp)
                    .floating(CircleShape, fill = if (pick == null) c.secondary.copy(alpha = 0.3f) else c.blue)
                    .clickable(enabled = pick != null) { pick?.let { onSend(cardFor(it, me)) }; onDismiss() },
                contentAlignment = Alignment.Center,
            ) { Text("Send", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** A person I have a 1:1 with: not a group or channel, not a deleted account, not this chat's own person. */
private fun pickable(conv: Conversation, me: String, cid: String): Boolean {
    if (conv.isGroup || conv.isChannel || conv.id == cid) return false
    val other = conv.otherUid(me)
    return other.isNotEmpty() && other != me && !conv.isDeletedAccount(other)
}

private fun cardFor(conv: Conversation, me: String): CardRules.Contact {
    val other = conv.otherUid(me)
    return CardRules.Contact(other, conv.names[other]?.takeIf { it.isNotBlank() } ?: conv.name(me), conv.photoUrl(me))
}
