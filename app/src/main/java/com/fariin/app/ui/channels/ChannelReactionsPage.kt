package com.fariin.app.ui.channels

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
 * Channel Settings > Reactions (iOS ChannelReactionsSettingsView): Any Emoji, Selected Emoji (up to
 * twelve, six per row in one rounded bar: tap one to replace it, its x removes it, + adds), No Reactions. Saves
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
        saveJob = scope.launch {
            delay(350)
            try {
                val (km, kl) = ChannelReactions.save(cid, m, l)
                savedMode = km; savedList = kl
                if (mode == m && list == l) { mode = km; list = kl }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                problem = com.fariin.app.channel.ChannelService.errorText(e, "Couldn't save. Try again.")
                mode = savedMode; list = savedList
            }
        }
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
    }
    if (picking) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(onDismissRequest = { closePicker() }, sheetState = sheet, containerColor = c.bgSecondary) {
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
                picked = { ReactionPicks.isPicked(list, it) }, onNote = { note = it })
            else LazyVerticalGrid(GridCells.Adaptive(52.dp), Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(horizontal = 12.dp)) {
                items(ChannelRules.channelReactions) { e ->
                    Box(Modifier.size(52.dp).clip(CircleShape).clickable { tapped(e) }, contentAlignment = Alignment.Center) {
                        Text(e, fontSize = 30.sp)
                        com.fariin.app.ui.thread.PickMark(ReactionPicks.isPicked(list, e))
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar("Reactions", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SettingsSection(footer = ChannelReactions.FOOTER) {
                listOf("all" to "Any Emoji", "some" to "Selected Emoji", "off" to "No Reactions").forEachIndexed { i, (value, title) ->
                    Row(Modifier.fillMaxWidth().alpha(if (loaded) 1f else 0.4f).clickable(enabled = loaded && mode != value) {
                        mode = value
                        if (value == "some") list = ChannelReactions.startList(list, lastPicks)
                        save()
                    }.heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        // A radio at the start, as every other single choice in the app (PrivacyPages.CheckRow).
                        androidx.compose.material3.RadioButton(selected = mode == value, onClick = null,
                            colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = c.accent))
                        Spacer(Modifier.width(16.dp))
                        Text(title, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                    }
                    if (i < 2) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
                }
            }
            if (mode == "some") {
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
            problem?.let { Text(it, fontSize = 15.sp, color = c.red, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) }
        }
    }
}
