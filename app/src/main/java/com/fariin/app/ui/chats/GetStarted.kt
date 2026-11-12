package com.fariin.app.ui.chats

import com.fariin.app.ui.components.GlassDropdown
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
import kotlinx.coroutines.flow.first
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.R

/** iOS GetStartedCard: the four things a new account can do from the chat list. */
enum class GetStartedCard(val title: String, val iconRes: Int) {
    // The filled shapes iOS draws (person.2.fill, envelope.fill, person.crop.circle.fill,
    // paintpalette.fill), as our own drawings (owner 2026-11-12: the Android card looked different).
    NEW_GROUP("New group", com.fariin.app.R.drawable.ic_gs_people_fill),
    INVITE("Invite friends", com.fariin.app.R.drawable.ic_gs_envelope_fill),
    ADD_PHOTO("Add photo", com.fariin.app.R.drawable.ic_sf_person_crop_circle_fill),
    CHAT_COLOR("Bubble color", com.fariin.app.R.drawable.ic_gs_palette_fill),
    /** Only after Skip on the restore screen: the second chance (owner 2026-11-12). Never stored. */
    RESTORE("Restore", com.fariin.app.R.drawable.ic_sf_lock_fill),
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
        // A photo counts as done the moment the profile has one (no redraw bump: we are mid-draw).
        if (hasPhoto) addTo(c, "$uid.done", GetStartedCard.ADD_PHOTO)
        val removed = p(c).getStringSet("$uid.removed", emptySet()).orEmpty()
        val seen = p(c).getStringSet("$uid.seen", emptySet()).orEmpty()
        return GetStartedCard.entries.filter { it != GetStartedCard.RESTORE && it.name !in removed && it.name !in seen }
    }

    // DONE STEPS (owner 2026-11-04: "when a task finishes it needs to show the user"): a done step
    // shows a green tick on its circle for a moment, then leaves the card ("seen"). When every step
    // is done or hidden the card has nothing left and goes away.
    private fun addTo(c: Context, key: String, card: GetStartedCard): Boolean {
        val set = p(c).getStringSet(key, emptySet()).orEmpty()
        if (card.name in set) return false
        p(c).edit().putStringSet(key, set + card.name).apply()
        return true
    }
    fun isDone(c: Context, uid: String, card: GetStartedCard) =
        card.name in p(c).getStringSet("$uid.done", emptySet()).orEmpty()
    fun markSeen(c: Context, uid: String, card: GetStartedCard) { if (addTo(c, "$uid.seen", card)) version.intValue++ }
    /** From anywhere in the app: the signed-in account just did [card] (created a group, picked a colour...). */
    fun markDone(c: Context, card: GetStartedCard) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (addTo(c, "$uid.done", card)) version.intValue++
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    // Read on every redraw (the list redraws on each store bump).
    // Reading the store version makes this card redraw on every change, even when the list is equal.
    val storeVersion = GetStartedStore.version.intValue
    val doneNow = remember(storeVersion, cards) { cards.filter { GetStartedStore.isDone(ctx, uid, it) }.toSet() }
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0f), c.bg), endY = 120f))
        .padding(top = 40.dp, bottom = 20.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).floating(RoundedCornerShape(30.dp))
            .padding(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Things you can do", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.weight(1f))
                Box(Modifier.size(28.dp).clip(CircleShape).background(c.label.copy(alpha = 0.08f)).clickable(onClick = onHideAll),
                    contentAlignment = Alignment.Center) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), "Hide", tint = c.secondary, modifier = Modifier.size(14.dp))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                cards.forEach { card ->
                    var menu by remember { mutableStateOf(false) }
                    val done = card in doneNow
                    val life = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                    if (done) androidx.compose.runtime.LaunchedEffect(card) {
                        // The tick plays where the user can see it: not behind the share sheet or a settings page.
                        life.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
                        kotlinx.coroutines.delay(300)
                        kotlinx.coroutines.delay(1_600)
                        GetStartedStore.markSeen(ctx, uid, card)
                    }
                    Box(Modifier.weight(1f)) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .combinedClickable(onLongClick = { menu = true }, onClick = {
                                // Opening the share sheet is the invite; the other steps are marked where they happen.
                                if (card == GetStartedCard.INVITE) GetStartedStore.markDone(ctx, card)
                                onTap(card)
                            }).padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(54.dp).floating(CircleShape), contentAlignment = Alignment.Center) {
                                Icon(ImageVector.vectorResource(card.iconRes), null, tint = c.label, modifier = Modifier.size(26.dp))
                                androidx.compose.animation.AnimatedVisibility(done, Modifier.align(Alignment.BottomEnd),
                                    enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn()) {
                                    Box(Modifier.size(20.dp).clip(CircleShape).background(Color(0xFF34C759)), contentAlignment = Alignment.Center) {
                                        Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), "Done", tint = Color.White, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(card.title, fontSize = 13.sp, color = c.label, maxLines = 1)
                        }
                        GlassDropdown(menu, onDismissRequest = { menu = false }, containerColor = c.card) {
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

