package com.fariin.app.ui.chats

import androidx.compose.ui.res.vectorResource
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin

/** iOS GetStartedCard: the four things a new account can do from the chat list. */
enum class GetStartedCard(val title: String, val icon: ImageVector) {
    NEW_GROUP("New group", Icons.Filled.Groups),
    INVITE("Invite friends", Icons.Filled.Email),
    ADD_PHOTO("Add photo", Icons.Filled.AccountCircle),
    CHAT_COLOR("Chat color", Icons.Filled.Palette),
}

/**
 * iOS GetStartedStore: per account, on this phone. "finished" = the card's X (can be undone for a
 * few seconds), "removed" = single circles hidden by a long press.
 */
object GetStartedStore {
    private const val PREFS = "fariin_getstarted"
    /** Bumped on every change so the chat list redraws. */
    val version = mutableIntStateOf(0)
    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isFinished(c: Context, uid: String) = uid.isEmpty() || p(c).getBoolean("$uid.finished", false)
    fun finish(c: Context, uid: String) { p(c).edit().putBoolean("$uid.finished", true).apply(); version.intValue++ }
    fun reopen(c: Context, uid: String) { p(c).edit().putBoolean("$uid.finished", false).apply(); version.intValue++ }
    fun remove(c: Context, uid: String, card: GetStartedCard) {
        val set = p(c).getStringSet("$uid.removed", emptySet()).orEmpty() + card.name
        p(c).edit().putStringSet("$uid.removed", set).apply(); version.intValue++
    }
    fun cards(c: Context, uid: String, hasPhoto: Boolean): List<GetStartedCard> {
        if (isFinished(c, uid)) return emptyList()
        val removed = p(c).getStringSet("$uid.removed", emptySet()).orEmpty()
        return GetStartedCard.entries.filter { it.name !in removed && !(it == GetStartedCard.ADD_PHOTO && hasPhoto) }
    }

    /** iOS accountIsNew: joined in the last 7 days, by the sign-in record's creation time. */
    fun accountIsNew(): Boolean {
        val joined = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.metadata?.creationTimestamp ?: return false
        return System.currentTimeMillis() - joined < 7L * 86_400_000
    }

    const val CHAT_LIMIT = 5
}

/**
 * iOS GetStartedBanner: one floating card "Things you can do" with a circle per thing, an X that
 * hides the card, and a long press on a circle to hide just that one. It stands over a fade at the
 * foot of a short chat list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GetStartedBanner(cards: List<GetStartedCard>, onTap: (GetStartedCard) -> Unit, onRemove: (GetStartedCard) -> Unit, onHideAll: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0f), c.bg), endY = 120f))
        .padding(top = 40.dp, bottom = 20.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).floating(RoundedCornerShape(30.dp))
            .padding(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Things you can do", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.weight(1f))
                Box(Modifier.size(28.dp).clip(CircleShape).background(c.label.copy(alpha = 0.08f)).clickable(onClick = onHideAll),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, "Hide", tint = c.secondary, modifier = Modifier.size(14.dp))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                cards.forEach { card ->
                    var menu by remember { mutableStateOf(false) }
                    Box(Modifier.weight(1f)) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .combinedClickable(onLongClick = { menu = true }, onClick = { onTap(card) }).padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(54.dp).floating(CircleShape), contentAlignment = Alignment.Center) {
                                Icon(if (card == GetStartedCard.NEW_GROUP) ImageVector.vectorResource(com.fariin.app.R.drawable.ic_gs_members) else card.icon, null, tint = c.label, modifier = Modifier.size(26.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(card.title, fontSize = 13.sp, color = c.label, maxLines = 1)
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = c.card) {
                            DropdownMenuItem(text = { Text("Hide", color = c.red) }, onClick = { menu = false; onRemove(card) })
                        }
                    }
                }
            }
        }
    }
}

/** "Card hidden · Undo" for a few seconds after the X (iOS getStartedUndo). */
@Composable
fun GetStartedUndo(onUndo: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.padding(bottom = 24.dp).floating(CircleShape).clickable(onClick = onUndo).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text("Card hidden", fontSize = 15.sp, color = c.secondary)
        Spacer(Modifier.size(10.dp))
        Text("Undo", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.accent)
    }
}

