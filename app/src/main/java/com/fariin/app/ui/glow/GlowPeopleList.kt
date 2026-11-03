package com.fariin.app.ui.glow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.Glow
import com.fariin.app.data.GlowLoad
import com.fariin.app.data.GlowPerson
import com.fariin.app.data.GlowRules
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.FariinMenu
import com.fariin.app.ui.components.FloatingSearchField
import com.fariin.app.ui.components.FloatingTopBar
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/**
 * The Glowers / Glowing list (iOS GlowPeopleListView): my username as the title, one full-width
 * Glowers | Glowing switch under it, rows of face + handle over name + the wide action, and the
 * search field at the bottom. Only ever MY lists: the rules refuse anybody else's names.
 */
@Composable
fun GlowPeopleListPage(title: String, startOnGlowing: Boolean = false, onOpenPerson: (String) -> Unit, onBack: () -> Unit) {
    val c = Fariin.colors
    BackHandler(onBack = onBack)
    val s by Glow.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(if (startOnGlowing) 1 else 0) }
    var search by rememberSaveable { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    val uids = (if (tab == 0) s.glowers else s.glowing).sorted()
    var load by remember { mutableStateOf<GlowLoad<List<GlowPerson>>>(GlowLoad.Loading) }
    LaunchedEffect(uids, reload) {
        if (load !is GlowLoad.Loaded || uids.isEmpty()) load = if (uids.isEmpty()) GlowLoad.Loaded(emptyList()) else GlowLoad.Loading
        load = Glow.people(uids)
    }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val side = if (tab == 0) "Glowers" else "Glowing"

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        FloatingTopBar(title.ifEmpty { "Glow" }, onBack = onBack)
        // Full width, 20 in (the back button's own edge), 46 tall: the search field's size.
        HalvesSwitch(listOf("Glowers", "Glowing"), tab, { tab = it },
            Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val l = load) {
                GlowLoad.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.secondary)
                GlowLoad.Failed -> GlowCouldNotLoad { reload++ }
                is GlowLoad.Loaded -> {
                    val rows = l.value.filter { GlowRules.matches(it, search) }
                    when {
                        rows.isEmpty() && l.value.isNotEmpty() -> GlowUnavailable("No Results for \"${search.trim()}\"",
                            "Check the spelling or try a new search.",
                            icon = { Icon(Icons.Outlined.Search, null, tint = c.secondary, modifier = Modifier.size(44.dp)) })
                        rows.isEmpty() -> GlowUnavailable(side,
                            if (tab == 0) "When somebody gives you a Glow, they appear here and in your Stories."
                            else "Open somebody's profile and choose Glow to follow their stories.",
                            icon = { GlowMark(48.dp, tint = c.secondary) })
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(rows, key = { it.id }) { p ->
                                PersonRow(p, glowersSide = tab == 0, glowing = s.glowing.contains(p.id),
                                    onOpen = { onOpenPerson(p.id) },
                                    onGive = { scope.launch { if (!Glow.give(p.id)) failed = true } },
                                    onRemove = { scope.launch { if (!Glow.remove(p.id)) failed = true } },
                                    onRemoveGlower = { scope.launch { if (!Glow.removeGlower(p.id)) failed = true } })
                            }
                        }
                    }
                }
            }
        }
        FloatingSearchField(search, { search = it }, "Search",
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 8.dp))
    }
    GlowWriteFailed(failed) { failed = false }
}

@Composable
private fun PersonRow(p: GlowPerson, glowersSide: Boolean, glowing: Boolean, onOpen: () -> Unit,
                      onGive: () -> Unit, onRemove: () -> Unit, onRemoveGlower: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // The face and words open the person's ordinary profile; no chevron.
        Row(Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.photoUrl, GlowRowAvatar)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(GlowRules.rowTitle(p), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                GlowRules.rowSubtitle(p)?.let { Text(it, fontSize = 14.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Spacer(Modifier.width(8.dp))
        }
        // Three states: "Glow back" would be a lie on somebody already glowed.
        if (glowersSide && !glowing) GlowFilledButton("Glow back", onClick = onGive)
        else GlowingCapsule(onRemove = onRemove)
        // The x severs somebody else's glow at me, so it asks; on Glowing it is not drawn at all.
        if (glowersSide) {
            var open by remember { mutableStateOf(false) }
            Box {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { open = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove this Glower", tint = c.secondary, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(open, onDismissRequest = { open = false }, shape = FariinMenu.shape, border = FariinMenu.border, containerColor = c.card) {
                    DropdownMenuItem(text = { Text("Remove this Glower", color = c.red) }, onClick = { open = false; onRemoveGlower() })
                }
            }
        }
    }
}

/** Two equal halves in one floating capsule, the chosen one a raised pill (iOS segmented control). */
@Composable
internal fun HalvesSwitch(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Fariin.colors
    Row(modifier.fillMaxWidth().height(46.dp).clip(CircleShape).background(c.fieldFill).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().then(if (on) Modifier.floating(CircleShape) else Modifier.clip(CircleShape))
                    .background(if (on) c.card else Color.Transparent).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 16.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = c.label)
            }
        }
    }
}
