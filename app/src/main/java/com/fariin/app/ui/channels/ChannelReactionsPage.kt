package com.fariin.app.ui.channels

import com.fariin.app.ui.settings.BarPage
import com.fariin.app.ui.components.underBar

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.channel.ChannelInfo
import com.fariin.app.channel.ChannelRules
import com.fariin.app.data.ChannelReactions
import com.fariin.app.data.ReactionPicks
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.fariin.app.ui.settings.PageBar
import com.fariin.app.ui.settings.SettingsSection
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.components.floating
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Channel Settings > Reactions (iOS ChannelReactionsSettingsView): one Reactions switch; on shows the
 * picks (up to twelve, six per row in one rounded bar: a tap opens the sheet, + adds), off = none. Saves
 * run in the background after a short pause, so six taps are one write; a refusal puts back what
 * the server last kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelReactionsScreen(cid: String, onBack: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val doc = com.fariin.app.ui.people.liveGroupDoc(cid)
    var loaded by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("all") }
    var list by remember { mutableStateOf<List<String>>(emptyList()) }
    var savedMode by remember { mutableStateOf("all") }
    var savedList by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastPicks by remember { mutableStateOf<List<String>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    /** A refused tap in the sheet ("Keep at least one", "Up to 12"), said inside it. */
    var note by remember { mutableStateOf<String?>(null) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveTicket by remember { mutableStateOf(0) }
    val showsCustom = remember { com.fariin.app.ui.thread.canChooseCustom() }
    var customTab by remember { mutableStateOf(false) }
    // The channel's real setting, once; no write before it is known (iOS).
    LaunchedEffect(doc.isNotEmpty()) {
        if (loaded || doc.isEmpty()) return@LaunchedEffect
        val info = ChannelInfo.from(cid, doc)
        // An uploaded emoji taken off the admin list is dropped from the editor (and so from the next save).
        mode = info.reactMode; list = info.reactList.filterNot { com.fariin.app.ui.thread.CustomReaction.isRetired(it) }
        savedMode = mode; savedList = list
        if (list.isNotEmpty()) lastPicks = list
        loaded = true
    }
    fun save() {
        val m = mode
        val l = list
        if (l.isNotEmpty()) lastPicks = l
        problem = null
        saveJob?.cancel()
        val ticket = ++saveTicket
        saving = true
        saveJob = scope.launch {
            try {
                delay(350)
                val (km, kl) = ChannelReactions.save(cid, m, l)
                savedMode = km; savedList = kl
                if (mode == m && list == l) { mode = km; list = kl }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                problem = com.fariin.app.channel.ChannelService.errorText(e, "Couldn't save. Try again.")
                mode = savedMode; list = savedList
            } finally {
                // Only the newest save clears the spinner (a cancelled one was replaced, not finished).
                if (ticket == saveTicket) saving = false
            }
        }
    }
    /** The switch: on brings back the earlier picks (or the default set), off turns reactions off. */
    fun setOn(on: Boolean) {
        if (!loaded || on == (mode != "off")) return
        if (on) { mode = "some"; list = ChannelReactions.onList(list, lastPicks) } else mode = "off"
        save()
    }
    // A channel still on the old "all" (or with no reactSet, read as "all") becomes the switch's ON with
    // the default set, saved, so what the owner sees is what followers get (iOS 2dc585e8). Owner only.
    LaunchedEffect(loaded) {
        if (!loaded || mode != "all") return@LaunchedEffect
        val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (me == null || doc["createdBy"] as? String != me) return@LaunchedEffect
        mode = "some"
        if (list.isEmpty()) list = ChannelReactions.defaultSet
        save()
    }
    // Many at once, saved once (iOS 148c67ef, owner 2026-11-06): the sheet stays open while emoji are
    // turned on and off in `list` (the bar above follows live); closing it saves once if anything changed.
    fun closePicker() {
        picking = false; note = null
        if (ReactionPicks.changed(mode, list, savedMode, savedList)) save()
    }
    fun tapped(e: String) {
        val t = ReactionPicks.toggle(list, e)
        list = t.list; note = t.note
        // An old "all" channel opened by an admin (not converted): picking makes it the picks.
        if (mode == "all" && list.isNotEmpty()) mode = "some"
    }
    if (picking) {
        // Opens at 56% of the screen (owner 2026-11-07, as iOS), so the chosen bar and its caption stay
        // visible above; Material's own half step is fixed at 50%, so the body sizes itself (GrowingSheetBody).
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        // Picks outside the channel set lead the grid; fixed while the sheet is open, so an unticked one
        // stays in place and can be ticked again.
        val offer = remember { ReactionPicks.offer(list) }
        ModalBottomSheet(onDismissRequest = { closePicker() }, sheetState = sheet, containerColor = c.bgSecondary) {
          GrowingSheetBody(0.56f) {
            Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(ReactionPicks.title(list.size), fontSize = 17.sp, color = c.label, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().align(Alignment.Center))
                Text("Done", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.accent,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).clip(RoundedCornerShape(8.dp))
                        .clickable { scope.launch { sheet.hide() }.invokeOnCompletion { closePicker() } }.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            // Normal / Custom (iOS EmojiMorePicker showsCustom): the custom set only for the chooser account.
            if (showsCustom) {
                com.fariin.app.ui.components.FloatingSegments(listOf("Normal", "Custom"), if (customTab) 1 else 0, { customTab = it == 1 },
                    Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp))
            }
            note?.let {
                Text(it, fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 8.dp))
            }
            if (showsCustom && customTab) com.fariin.app.ui.thread.CustomEmojiGrid(onPick = { tapped(it) },
                modifier = Modifier.weight(1f), picked = { ReactionPicks.isPicked(list, it) }, onNote = { note = it })
            else LazyVerticalGrid(GridCells.Adaptive(52.dp), Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)) {
                items(offer) { e ->
                    Box(Modifier.size(52.dp).clip(CircleShape).clickable { tapped(e) }, contentAlignment = Alignment.Center) {
                        Text(e, fontSize = 30.sp)
                        com.fariin.app.ui.thread.PickMark(ReactionPicks.isPicked(list, e))
                    }
                }
            }
          }
        }
    }
    val barScroll = rememberScrollState()
    BarPage("Reactions", onBack, under = underBar(barScroll), modifier = Modifier.background(c.bgSecondary)) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(top = top).navigationBarsPadding()) {
            // ONE SWITCH (iOS 2dc585e8, owner 2026-11-07): on = the picks below, off = no reactions.
            SettingsSection(footer = ChannelReactions.footer(mode)) {
                com.fariin.app.ui.settings.SwitchRow("Reactions", checked = mode != "off", enabled = loaded, last = true) { setOn(it) }
            }
            if (mode != "off") {
                // Six per row; the + slot follows the last one while there are fewer than 12.
                val slots: List<Int> = list.indices.toList() + (if (list.size < ChannelReactions.MAX) listOf(-1) else emptyList())
                val rows = slots.chunked(ChannelReactions.PER_ROW)
                val shape = if (rows.size > 1) RoundedCornerShape(32.dp) else CircleShape
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.Center) {
                    Column(Modifier.floating(shape)
                        .padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                      rows.forEach { row ->
                       Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { i ->
                         if (i < 0) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(44.dp).clip(CircleShape).background(c.fieldFill).clickable { picking = true },
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Add, "Add an emoji", tint = c.secondary)
                            }
                         } else {
                            // No x on the bar (owner 2026-11-06: tapped by mistake). Any emoji opens the
                            // sheet, where a tap turns an emoji on or off.
                            val e = list[i]
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(44.dp).clip(CircleShape).clickable { picking = true }
                                    .semantics { contentDescription = "${com.fariin.app.ui.thread.CustomReaction.spoken(e)}. Change reactions" },
                                    contentAlignment = Alignment.Center) {
                                    com.fariin.app.ui.thread.ReactionGlyph(e, 28.sp, 34.dp)
                                }
                            }
                         }
                        }
                       }
                      }
                    }
                }
                Text(ReactionPicks.footer(list), fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
            }
            if (saving) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = c.secondary, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Saving…", fontSize = 13.sp, color = c.secondary)
            }
            problem?.let { Text(it, fontSize = 15.sp, color = c.red, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) }
        }
    }
}

/**
 * A sheet body that opens at [startFraction] of the screen and grows to the sheet's full height when
 * its list is dragged up (and shrinks back first when dragged down, before the sheet itself moves).
 * Material's own half step is fixed at 50% of the screen.
 */
@Composable
private fun GrowingSheetBody(startFraction: Float, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenPx = with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxPx = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else screenPx
        val minPx = minOf(screenPx * startFraction, maxPx)
        var h by remember { androidx.compose.runtime.mutableFloatStateOf(minPx) }
        val conn = remember(minPx, maxPx) {
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    // Finger up: the body grows first, then the list scrolls.
                    if (available.y >= 0f || h >= maxPx) return androidx.compose.ui.geometry.Offset.Zero
                    val grow = minOf(-available.y, maxPx - h)
                    h += grow
                    return androidx.compose.ui.geometry.Offset(0f, -grow)
                }
                override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset,
                                          source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    // Finger down with the list at its top: the body shrinks back before the sheet moves.
                    if (available.y <= 0f || h <= minPx) return androidx.compose.ui.geometry.Offset.Zero
                    val shrink = minOf(available.y, h - minPx)
                    h -= shrink
                    return androidx.compose.ui.geometry.Offset(0f, shrink)
                }
                override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                    // Let go between the two heights: settle on one of them.
                    if (h <= minPx || h >= maxPx) return androidx.compose.ui.unit.Velocity.Zero
                    val target = when {
                        available.y < -300f -> maxPx
                        available.y > 300f -> minPx
                        h > (minPx + maxPx) / 2f -> maxPx
                        else -> minPx
                    }
                    androidx.compose.animation.core.animate(h, target) { v, _ -> h = v }
                    return available
                }
            }
        }
        Column(Modifier.fillMaxWidth().height(with(density) { h.toDp() })
            .nestedScroll(conn), content = content)
    }
}
