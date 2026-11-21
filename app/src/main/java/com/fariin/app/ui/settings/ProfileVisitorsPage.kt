package com.fariin.app.ui.settings

import com.fariin.app.ui.components.fadeOnChange
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.PlusVisitors
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.cachedVector
import com.fariin.app.ui.components.listPress
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

internal const val PAGE_PROFILE_VISITORS = "settings:profileVisitors"

private data class VisitRow(val uid: String, val atMs: Long, val isNew: Boolean)
private data class VisitPerson(val name: String, val handle: String, val photo: String?, val verified: Boolean)

private sealed interface VisitPhase {
    data object Loading : VisitPhase
    data object Failed : VisitPhase
    data object Off : VisitPhase
    data class Locked(val total: Int) : VisitPhase
    data object On : VisitPhase
}

/**
 * Fariin Plus: who opened my profile (iOS PlusVisitorsView). With Plus: the last 30 days, newest
 * first, "New" above "Earlier", a tap opens their profile. Without: how many, never who, and a
 * button to the Plus page. Opening the page marks every visit seen on the server.
 */
@Composable
internal fun ProfileVisitorsPage(pop: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val people = com.fariin.app.ui.people.LocalPeopleNav.current
    var phase by remember { mutableStateOf<VisitPhase>(VisitPhase.Loading) }
    var rows by remember { mutableStateOf<List<VisitRow>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf<Map<String, VisitPerson>>(emptyMap()) }
    var plusPage by remember { mutableStateOf(false) }

    suspend fun loadProfiles(uids: List<String>) {
        val db = com.fariin.app.Fb.db
        for (u in uids) if (u !in profiles) {
            val d = try { db.collection("users").document(u).get().await().data } catch (_: Exception) { null } ?: continue
            profiles = profiles + (u to VisitPerson(d["name"] as? String ?: "", d["handle"] as? String ?: "",
                (d["photoUrl"] as? String)?.ifEmpty { null }, d["verified"] == true))
        }
    }
    fun apply(d: Map<*, *>, append: Boolean) {
        when (d["state"]) {
            "on" -> {
                val got = (d["rows"] as? List<*>).orEmpty().mapNotNull { x ->
                    val m = x as? Map<*, *> ?: return@mapNotNull null
                    val u = (m["uid"] as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    VisitRow(u, (m["at"] as? Number)?.toLong() ?: 0L, m["isNew"] == true)
                }
                val base = if (append) rows else emptyList()
                val have = base.map { it.uid }.toSet()
                rows = base + got.filter { it.uid !in have }
                next = d["next"] as? String
                phase = VisitPhase.On
                scope.launch { loadProfiles(got.map { it.uid }) }
            }
            "locked" -> { rows = emptyList(); next = null; phase = VisitPhase.Locked((d["total"] as? Number)?.toInt() ?: 0) }
            else -> { rows = emptyList(); next = null; phase = VisitPhase.Off }
        }
    }
    suspend fun load() {
        try {
            val first = PlusVisitors.list(null)
            apply(first, append = false); PlusVisitors.markSeen()
            // The Settings button's faces take this page (newest first; none unless on).
            PlusVisitors.noteLatest(if (first["state"] == "on") first["rows"] else null)
        }
        catch (e: FirebaseFunctionsException) {
            if (e.code == FirebaseFunctionsException.Code.NOT_FOUND) phase = VisitPhase.Off
            else if (!(phase == VisitPhase.On && rows.isNotEmpty())) phase = VisitPhase.Failed
        } catch (_: Exception) { if (!(phase == VisitPhase.On && rows.isNotEmpty())) phase = VisitPhase.Failed }
    }
    fun loadMore() {
        val cursor = next ?: return
        if (phase != VisitPhase.On || loadingMore) return
        loadingMore = true
        scope.launch { try { apply(PlusVisitors.list(cursor), append = true) } catch (_: Exception) {} finally { loadingMore = false } }
    }
    LaunchedEffect(Unit) { load() }
    // Plus went away for this account: so does the page.
    LaunchedEffect(com.fariin.app.data.PlusStore.visible) { if (!com.fariin.app.data.PlusStore.visible) pop() }

    ScrollPage("Profile Visitors", pop) {
        // Spinner, failure and the list fade into each other (iOS pops 82).
        Column(Modifier.fadeOnChange(phase.javaClass)) {
        when (val p = phase) {
            VisitPhase.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                com.fariin.app.ui.components.FariinSpinner(size = 24.dp)
            }
            VisitPhase.Failed -> SettingsSection {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Couldn't load your visitors.", fontSize = 17.sp, color = c.label)
                    Text("Try Again", fontSize = 17.sp, color = c.accent, modifier = Modifier.clip(CircleShape)
                        .listPress { phase = VisitPhase.Loading; scope.launch { load() } }.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            VisitPhase.Off -> SettingsSection {
                Text("This isn't available right now.", fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
            }
            is VisitPhase.Locked -> {
                SettingsSection {
                    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(ImageVector.cachedVector(R.drawable.ic_views), contentDescription = null, tint = PlusBlue, modifier = Modifier.size(38.dp))
                        Text(lockedTitle(p.total), fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            color = c.label, textAlign = TextAlign.Center)
                        Text("With Fariin Plus you can see who visited your profile in the last 30 days.", fontSize = 15.sp,
                            color = c.secondary, textAlign = TextAlign.Center)
                    }
                }
                SettingsSection {
                    Row(Modifier.fillMaxWidth().listPress { plusPage = true }.heightIn(min = 52.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        PlusBadgeImage(20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("See Who with Fariin Plus", fontSize = 17.sp, color = c.accent)
                    }
                }
            }
            VisitPhase.On -> {
                if (rows.isEmpty()) SettingsSection {
                    Text("People who visit your profile in the last 30 days will appear here.", fontSize = 15.sp,
                        color = c.secondary, modifier = Modifier.fillMaxWidth().padding(16.dp))
                } else {
                    val fresh = rows.filter { it.isNew }
                    val older = rows.filter { !it.isNew }
                    val is24 = android.text.format.DateFormat.is24HourFormat(ctx)
                    @Composable fun row(r: VisitRow, last: Boolean) {
                        val who = profiles[r.uid]
                        val name = who?.let { it.name.ifEmpty { if (it.handle.isNotEmpty()) "@${it.handle}" else "" } } ?: ""
                        Row(Modifier.fillMaxWidth().listPress { people.contact(r.uid) }.heightIn(min = 56.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(who?.photo, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (who == null) Box(Modifier.size(120.dp, 14.dp).clip(CircleShape).background(c.secondary.copy(alpha = 0.2f)))
                                    else Text(name, fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false))
                                    if (who?.verified == true) com.fariin.app.ui.components.VerifiedSeal(c.blue, Modifier.padding(start = 5.dp).size(13.dp))
                                    PlusBadge(r.uid, 13.dp, Modifier.padding(start = 5.dp))
                                }
                                Text(com.fariin.app.ui.chats.timeLabel(r.atMs.toDouble(), is24), fontSize = 12.sp, color = c.secondary)
                            }
                            if (r.isNew) Box(Modifier.padding(start = 8.dp).size(8.dp).clip(CircleShape).background(PlusBlue))
                        }
                        if (last) LaunchedEffect(r.uid) { loadMore() }
                    }
                    if (fresh.isNotEmpty()) SettingsSection(header = "New") {
                        fresh.forEachIndexed { i, r -> row(r, older.isEmpty() && i == fresh.lastIndex) }
                    }
                    if (older.isNotEmpty()) SettingsSection(header = if (fresh.isEmpty()) "Last 30 Days" else "Earlier") {
                        older.forEachIndexed { i, r -> row(r, i == older.lastIndex) }
                        if (loadingMore) Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            com.fariin.app.ui.components.FariinSpinner(size = 20.dp)
                        }
                    }
                }
            }
        }
        }
    }
    if (plusPage) com.fariin.app.ui.settings.PlusGateSheet("profileVisitors") { plusPage = false }
}

private fun lockedTitle(n: Int) = when (n) {
    0 -> "No one has visited your profile yet"
    1 -> "1 person visited your profile"
    else -> "$n people visited your profile"
}

/** The round eye button at the top left of Settings, with the blue count of new visitors. */
@Composable
internal fun ProfileVisitorsButton(modifier: Modifier, onClick: () -> Unit) {
    if (!PlusVisitors.shows) return
    LaunchedEffect(Unit) { PlusVisitors.refreshCount(); PlusVisitors.refreshFaces() }
    val faces = PlusVisitors.faces
    Box(modifier) {
        // Owner 2026-11-20: his closed eye with lashes; with visitors, their latest faces instead, in
        // the same glass capsule (the way the reference app stacks a short video's viewers).
        // Eye <-> faces cross-fade and the capsule widens on the spring (iOS pops 73).
        com.fariin.app.ui.components.Swap(faces.isEmpty(), label = "visitorsButton") { none ->
        if (none) com.fariin.app.ui.components.FloatingIcon(ImageVector.cachedVector(R.drawable.ic_eye_closed_lashes), "Profile Visitors", onClick = onClick)
        else Box(Modifier.height(44.dp).clip(CircleShape).then(com.fariin.app.ui.components.barGlass())
            .clickable(onClickLabel = "Profile Visitors", onClick = onClick).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            val ring = com.fariin.app.ui.theme.Fariin.colors.bg
            Row {
                faces.take(3).forEachIndexed { i, f ->
                    Box(Modifier.offset(x = (-8 * i).dp).zIndex((3 - i).toFloat()).size(25.dp).clip(CircleShape).background(ring),
                        contentAlignment = Alignment.Center) {
                        com.fariin.app.ui.components.Avatar(f.photoUrl, 22.dp)
                    }
                }
            }
        }
        }
        // The new-visitors badge fades in and out; its count rolls.
        val n = PlusVisitors.newCount
        val heldN = androidx.compose.runtime.remember { intArrayOf(n) }
        if (n > 0) heldN[0] = n
        com.fariin.app.ui.components.Appear(n > 0, Modifier.align(Alignment.TopEnd), resize = false) {
            Box(Modifier.heightIn(min = 18.dp).clip(CircleShape).background(PlusBlue)
                .padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                com.fariin.app.ui.components.RollingNumber(minOf(heldN[0], 100)) { k ->
                    Text(if (k > 99) "99+" else "$k", fontSize = 11.sp, color = androidx.compose.ui.graphics.Color.White,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
            }
        }
    }
}
