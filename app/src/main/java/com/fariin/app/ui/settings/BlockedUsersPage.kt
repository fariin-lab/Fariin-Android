package com.fariin.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.BlockList
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.FirestorePeople
import com.fariin.app.ui.chats.SwipeAction
import com.fariin.app.ui.chats.SwipeActionsRow
import com.fariin.app.ui.chats.SwipeColors
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** A blocked or blockable person: uid, name, @username, photo. */
private data class BlockPerson(val uid: String, val name: String, val handle: String, val photo: String?)

/**
 * iOS BlockedUsersView: a red "Block User…" row that opens the person picker, the search field,
 * newest block first, swipe left to Unblock (asked first), and what blocking does in the footer.
 * The empty text sits inside the list, so Block User stays reachable with nobody blocked.
 */
@Composable
internal fun BlockedUsersPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val snap by BlockList.state.collectAsState()
    var people by remember { mutableStateOf<Map<String, BlockPerson>>(emptyMap()) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var blockError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(snap.entries.keys) {
        val db = FirebaseFirestore.getInstance()
        val out = HashMap(people)
        for (u in snap.entries.keys) if (u !in out) {
            val d = try { db.collection("users").document(u).get().await().data } catch (_: Exception) { null }
            out[u] = BlockPerson(u, d?.get("name") as? String ?: "", d?.get("handle") as? String ?: "",
                (d?.get("photoUrl") as? String)?.ifEmpty { null })
        }
        people = out
    }
    if (picking) {
        BackHandler { picking = false }
        BlockPickerPage(onClose = { picking = false }, onFail = { blockError = it })
        return
    }
    val q = search.trim().lowercase()
    val all = snap.entries.entries.sortedByDescending { it.value }.map { it.key }
    val rows = if (q.isEmpty()) all else all.filter { u ->
        val p = people[u]
        p != null && (p.name.lowercase().contains(q) || p.handle.lowercase().contains(q))
    }
    ScrollPage("Blocked Users", pop) {
        SearchBox(search, "Search") { search = it }
        ErrorNote(blockError)
        SettingsSection {
            SettingsRow("Block User…", icon = painterResource(com.fariin.app.R.drawable.ic_block), titleColor = c.red,
                trailing = Trailing.NONE, last = true) { blockError = null; picking = true }
        }
        SettingsSection(header = if (all.isNotEmpty()) "Blocked" else null,
            footer = "Blocked people can't call or message you or see your stories. They aren't told.") {
            if (rows.isEmpty()) Text(if (q.isEmpty()) "People you block will appear here." else "No match.",
                fontSize = 15.sp, color = c.secondary, modifier = Modifier.fillMaxWidth().padding(16.dp))
            val unblockIcon = painterResource(com.fariin.app.R.drawable.ic_block)
            for ((i, u) in rows.withIndex()) {
                val p = people[u]
                // Not read yet: a grey circle and bar, not "An account you blocked" (that is for a read that found nothing).
                if (p == null) { PlaceholderPersonRow(last = i == rows.lastIndex); continue }
                val name = p?.name?.ifEmpty { null } ?: p?.handle?.takeIf { it.isNotEmpty() }?.let { "@$it" } ?: "An account you blocked"
                // Swipe left to Unblock, as iOS; the confirm stands behind it, so nothing is undone by accident.
                SwipeActionsRow(id = "blocked:$u", enabled = true, leading = emptyList(),
                    trailing = listOf(SwipeAction("Unblock", unblockIcon, SwipeColors.red) { confirm = u })) {
                    Box(Modifier.background(c.card)) {
                        PersonRow(name, p?.photo, sub = p?.handle?.takeIf { it.isNotEmpty() && p.name.isNotEmpty() }?.let { "@$it" },
                            last = i == rows.lastIndex, onClick = null)
                    }
                }
            }
        }
    }
    // The contact page's Unblock sheet: their photo and name, the red button spins until it is done.
    confirm?.let { u ->
        val p = people[u]
        val name = p?.name?.ifEmpty { null } ?: p?.handle?.takeIf { it.isNotEmpty() }?.let { "@$it" } ?: ""
        com.fariin.app.ui.people.PersonConfirmSheet(p?.photo, name, if (name.isEmpty()) "Unblock?" else "Unblock $name?",
            "They will be able to message and call you again. Messages sent while blocked stay hidden.",
            listOf(com.fariin.app.ui.people.PersonAction("Unblock") {
                val ok = FirestorePeople(FirebaseAuth.getInstance().currentUser!!.uid, CryptoHub.get(ctx)).setBlocked(u, false)
                if (!ok) toast(ctx, "Check your connection and try again.")
                ok
            }), onDismiss = { confirm = null })
    }
}

/**
 * iOS BlockPickerView: every 1:1 chat, A to Z under its letter ("#" last), the ones already
 * blocked greyed and marked. A tap asks first; only my own chats are listed, never a directory
 * search (a block list has no business saying whether an @username exists).
 */
@Composable
private fun BlockPickerPage(onClose: () -> Unit, onFail: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val me = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    val snap by BlockList.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var toBlock by remember { mutableStateOf<BlockPerson?>(null) }
    val everyone = remember(me) {
        CryptoHub.listedAll().filter { !it.isGroup && !it.isChannel && it.otherUid(me).isNotEmpty() }
            .map { BlockPerson(it.otherUid(me), it.displayName(me), "", it.displayPhoto(me)) }
            .distinctBy { it.uid }
    }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) everyone else everyone.filter { it.name.lowercase().contains(q) }
    val sections = shown.groupBy { p ->
        val f = p.name.trim().firstOrNull()?.uppercaseChar()
        if (f != null && f.isLetter()) f.toString() else "#"
    }.toList().sortedWith(compareBy({ it.first == "#" }, { it.first }))
        .map { (l, ps) -> l to ps.sortedBy { it.name.lowercase() } }
    val scroll = rememberScrollState()
    val under by remember { derivedStateOf { scroll.value > 0 } }
    com.fariin.app.ui.components.GlassBarPage(under, bar = { PageBar("Block User", onBack = onClose) }) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = top + 8.dp)) {
            SearchBox(query, "Search") { query = it }
            if (sections.isEmpty()) Text(if (q.isEmpty()) "You have no chats to block." else "No match.",
                fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(20.dp))
            for ((letter, ps) in sections) SettingsSection(header = letter) {
                for ((i, p) in ps.withIndex()) {
                    val already = snap.contains(p.uid)
                    Box(Modifier.alpha(if (already) 0.5f else 1f)) {
                        PersonRow(p.name.ifEmpty { "Fariin user" }, p.photo, sub = if (already) "Already blocked" else null,
                            last = i == ps.lastIndex, onClick = if (already) null else ({ toBlock = p }))
                    }
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = com.fariin.app.ui.shell.bottomBarInset()))
        }
    }
    toBlock?.let { p ->
        // The contact page's Block sheet; the picker closes once the block is written (a failure is
        // said on Blocked Users, as before).
        com.fariin.app.ui.people.PersonConfirmSheet(p.photo, p.name, "Block ${p.name.ifEmpty { "this person" }}?",
            "They will not be able to call you, and nothing they send will reach you. They are not told.",
            listOf(com.fariin.app.ui.people.PersonAction("Block") {
                val name = p.name.ifEmpty { "This person" }
                val ok = FirestorePeople(me, CryptoHub.get(ctx)).setBlocked(p.uid, true)
                if (!ok) onFail("$name could not be blocked. Try again.")
                onClose()
                ok
            }), onDismiss = { toBlock = null })
    }
}

/** The rounded search field of the people pickers (StoryPeoplePicker's). */
@Composable
private fun SearchBox(value: String, hint: String, onChange: (String) -> Unit) {
    val c = Fariin.colors
    Box(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 44.dp)
            .clip(RoundedCornerShape(22.dp)).background(c.fieldFill).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(hint, color = c.secondary, fontSize = 16.sp)
        BasicTextField(value, { onChange(it.take(60)) }, singleLine = true,
            textStyle = TextStyle(color = c.label, fontSize = 16.sp), cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth())
    }
}
