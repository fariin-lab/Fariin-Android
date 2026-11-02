package com.fariin.app.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import com.fariin.app.ui.settings.PageBar
import com.fariin.app.ui.settings.SettingsSection
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Channel Settings > Reactions (iOS ChannelReactionsSettingsView): Any Emoji, Selected Emoji (up to
 * six, in one rounded bar: tap one to replace it, its x removes it, + adds), No Reactions. Saves
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
    var replacing by remember { mutableStateOf<Int?>(null) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    // The channel's real setting, once; no write before it is known (iOS).
    LaunchedEffect(doc.isNotEmpty()) {
        if (loaded || doc.isEmpty()) return@LaunchedEffect
        val info = ChannelInfo.from(cid, doc)
        mode = info.reactMode; list = info.reactList
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
    if (picking) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(onDismissRequest = { picking = false; replacing = null }, sheetState = sheet, containerColor = c.bgSecondary) {
            Text("Choose Emoji", fontSize = 17.sp, color = c.label, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            LazyVerticalGrid(GridCells.Adaptive(52.dp), Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(horizontal = 12.dp)) {
                items(ChannelRules.channelReactions) { e ->
                    Box(Modifier.size(52.dp).clip(CircleShape).clickable {
                        picking = false
                        ChannelReactions.place(list, replacing, e)?.let { list = it; save() }
                        replacing = null
                    }, contentAlignment = Alignment.Center) { Text(e, fontSize = 30.sp) }
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
                        Text(title, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                        if (mode == value) Icon(Icons.Filled.Check, null, tint = c.blue)
                    }
                    if (i < 2) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
                }
            }
            if (mode == "some") {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.Center) {
                    Row(Modifier.clip(CircleShape).background(c.card).border(1.dp, c.separator, CircleShape)
                        .padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.forEachIndexed { i, e ->
                            Box(Modifier.size(48.dp)) {
                                Box(Modifier.size(44.dp).align(Alignment.Center).clip(CircleShape)
                                    .then(if (picking && replacing == i) Modifier.border(2.dp, c.blue, CircleShape) else Modifier)
                                    .clickable { replacing = i; picking = true }, contentAlignment = Alignment.Center) {
                                    Text(e, fontSize = 28.sp)
                                }
                                if (list.size > 1) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(20.dp).clip(CircleShape)
                                    .background(c.secondary).clickable { list = ChannelReactions.remove(list, i); replacing = null; save() },
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Close, "Remove $e", tint = c.bgSecondary, modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                        if (list.size < ChannelReactions.MAX) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(44.dp).clip(CircleShape).background(c.fieldFill).clickable { replacing = null; picking = true },
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Add, "Add an emoji", tint = c.secondary)
                            }
                        }
                    }
                }
                Text(ChannelReactions.pickFooter(list), fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
            }
            problem?.let { Text(it, fontSize = 15.sp, color = c.red, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) }
        }
    }
}
