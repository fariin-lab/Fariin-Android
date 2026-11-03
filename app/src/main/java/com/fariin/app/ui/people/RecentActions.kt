package com.fariin.app.ui.people

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.DoNotTouch
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RemoveModerator
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.AdminLog
import com.fariin.app.data.AuthRepository
import com.fariin.app.data.GroupRules
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.FloatingIcon
import com.fariin.app.ui.components.SkeletonBlock
import com.fariin.app.ui.components.SkeletonCircle
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.official.OfficialAvatar
import com.fariin.app.ui.settings.PageBar
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.util.Locale

/*
 * Recent Actions (Kulan-iOS GroupRecentActionsView): what the group's owner and admins did, from the
 * server's `groupAdminLog` callable, 50 at a time. Each day under a centred date pill, then a card of
 * rows: the actor's face with the kind of action in a small badge, the name with the time at its
 * right, the action with the person or value in bold, and what it carries (rights, restrictions, the
 * new name). The filter by kind of action and by admin is a bottom sheet. Pushed from Group Info's
 * settings card for the owner and admins (iOS: Group Settings, Recent Actions).
 */

private val CardShape26 = RoundedCornerShape(26.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecentActionsPage(cid: String, me: String, onBack: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc = liveGroupDoc(cid)
    var events by remember(cid) { mutableStateOf<List<AdminLog.Event>>(emptyList()) }
    val names = remember(cid) { mutableStateMapOf<String, String>() }
    var next by remember(cid) { mutableStateOf<Map<String, Any?>?>(null) }
    var loading by remember(cid) { mutableStateOf(true) }
    var loadingMore by remember(cid) { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var failure by remember(cid) { mutableStateOf<String?>(null) }
    var kinds by remember(cid) { mutableStateOf<Set<AdminLog.Kind>?>(null) }
    var actors by remember(cid) { mutableStateOf<Set<String>?>(null) }
    var showFilter by remember { mutableStateOf(false) }
    val filtering = kinds != null || actors != null

    @Suppress("UNCHECKED_CAST")
    fun strMap(k: String) = (doc[k] as? Map<*, *>)?.entries?.mapNotNull { (a, b) -> (a as? String)?.let { it to (b as? String ?: "") } }?.toMap() ?: emptyMap()
    val groupNames = strMap("names")
    val photos = strMap("photos")
    val name: (String) -> String = { AdminLog.name(it, me, names, groupNames) }
    val photo: (String) -> String? = { photos[it]?.ifEmpty { null } }

    suspend fun load(more: Boolean) {
        if (more) { if (loadingMore || loading || next == null) return; loadingMore = true } else loading = true
        try {
            val r = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("groupAdminLog")
                .call(AdminLog.request(cid, if (more) next else null)).await()
            val page = AdminLog.Page.from(r.getData() as? Map<*, *>)
            names.putAll(page.names)
            events = if (more) AdminLog.merge(events, page.events) else page.events
            next = page.next
            failure = null
        } catch (e: Exception) {
            failure = AdminLog.failure((e as? FirebaseFunctionsException)?.code == FirebaseFunctionsException.Code.PERMISSION_DENIED)
        } finally { loading = false; loadingMore = false }
    }
    LaunchedEffect(cid) { if (events.isEmpty()) load(false) }

    if (showFilter) RecentActionsFilterSheet(AdminLog.filterPeople(doc["createdBy"] as? String ?: "",
        (doc["admins"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(), events), kinds, actors, name, photo,
        onClose = { showFilter = false }) { k, a -> kinds = k; actors = a }

    val is24h = DateFormat.is24HourFormat(ctx)
    val locale = Locale.getDefault()
    val monthDay = DateFormat.getBestDateTimePattern(locale, "MMMMd")
    val monthDayYear = DateFormat.getBestDateTimePattern(locale, "MMMMdyyyy")
    val untilPattern = DateFormat.getBestDateTimePattern(locale, if (is24h) "MMMdyyyyHHmm" else "MMMdyyyyhmma")
    val untilText: (Double) -> String = { ms ->
        java.time.Instant.ofEpochMilli(ms.toLong()).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern(untilPattern, locale))
    }
    val shown = AdminLog.shown(events, kinds, actors)

    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar("Recent Actions", onBack, action = {
            FloatingIcon(Icons.Filled.FilterList, if (filtering) "Filter, on" else "Filter",
                onClick = { if (events.isNotEmpty()) showFilter = true },
                tint = when { events.isEmpty() -> c.tertiary; filtering -> c.blue; else -> c.label },
                modifier = Modifier.padding(end = 8.dp))
        })
        PullToRefreshBox(refreshing, onRefresh = { refreshing = true; scope.launch { load(false); refreshing = false } },
            modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 16.dp)) {
                when {
                    loading && events.isEmpty() -> item { RecentActionsSkeleton() }
                    failure != null && events.isEmpty() -> item {
                        EmptyState(rememberVectorPainter(Icons.Filled.Refresh), "Couldn't Load", failure ?: "", "Try Again") {
                            scope.launch { load(false) }
                        }
                    }
                    events.isEmpty() -> item {
                        EmptyState(rememberVectorPainter(Icons.Filled.History), "No Recent Actions",
                            "What the owner and admins change in this group shows here.")
                    }
                    shown.isEmpty() -> {
                        item {
                            EmptyState(rememberVectorPainter(Icons.Filled.FilterList), "No Matching Actions",
                                if (next == null) "Nothing here matches the filter." else "Nothing loaded so far matches the filter.",
                                "Show All Actions") { kinds = null; actors = null }
                        }
                        if (next != null) item(key = "more${events.size}") { MoreLoader { scope.launch { load(true) } } }
                    }
                    else -> {
                        AdminLog.days(shown).forEach { (day, list) ->
                            item(key = "d$day") { DatePill(AdminLog.dayTitle(day, LocalDate.now(), locale, monthDay, monthDayYear)) }
                            item(key = "c$day") {
                                Column(Modifier.fillMaxWidth().clip(CardShape26).background(c.card)) {
                                    list.forEachIndexed { i, e ->
                                        if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 68.dp))
                                        ActionRow(e, name, photo, AdminLog.timeText(e.atMs, is24h, locale),
                                            AdminLog.detail(e, events, (doc["avatarUrl"] as? String)?.ifEmpty { null },
                                                GroupRules.description(doc).ifEmpty { null }, name, untilText),
                                            doc["avatarUrl"] as? String)
                                    }
                                }
                            }
                        }
                        // A new identity per page, so it asks again if it is still on screen after a page
                        // whose actions the filter hides.
                        if (next != null) item(key = "more${events.size}") { MoreLoader { scope.launch { load(true) } } }
                    }
                }
                if (!(loading && events.isEmpty())) item(key = "footer") {
                    Text("Only the owner and admins see Recent Actions.", fontSize = 13.sp, color = c.secondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(top = 14.dp, bottom = 28.dp))
                }
            }
        }
    }
}

@Composable
private fun DatePill(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Fariin.colors.label,
            modifier = Modifier.floating(CircleShape).padding(horizontal = 12.dp, vertical = 5.dp))
    }
}

@Composable
private fun ActionRow(e: AdminLog.Event, name: (String) -> String, photo: (String) -> String?, time: String,
                      more: AdminLog.Detail, groupPhoto: String?) {
    val c = Fariin.colors
    val words = AdminLog.action(e, name(e.target))
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 1.dp)) {
            if (e.by == "fariin") OfficialAvatar(42.dp) else Avatar(photo(e.by), 42.dp)
            Box(Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp).size(20.dp).clip(CircleShape).background(c.card)
                .border(0.5.dp, c.separator, CircleShape), contentAlignment = Alignment.Center) {
                Icon(typeIcon(e), null, tint = c.label, modifier = Modifier.size(12.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name(e.by), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f).width(8.dp))
                Text(time, fontSize = 12.sp, color = c.secondary)
            }
            Text(buildAnnotatedString {
                words.forEach { p -> if (p.bold) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(p.text) } else append(p.text) }
            }, fontSize = 15.sp, color = c.label)
            if (!more.isEmpty) DetailBlock(more, groupPhoto)
        }
    }
}

@Composable
private fun DetailBlock(d: AdminLog.Detail, groupPhoto: String?) {
    val c = Fariin.colors
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        d.quote?.let { q ->
            Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(1.5.dp)).background(c.blue))
                Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    d.quoteLabel?.let { Text(it, fontSize = 12.sp, color = c.secondary) }
                    Text(q, fontSize = 15.sp, color = c.label, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        d.photoUrl?.let { url ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Avatar(url.ifEmpty { groupPhoto }, 44.dp)
                Text("Current group photo", fontSize = 12.sp, color = c.secondary)
            }
        }
        d.note?.let { Text(it, fontSize = 13.sp, color = c.secondary) }
        if (d.bullets.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            d.bullets.forEach { b ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("•", fontSize = 13.sp, color = c.secondary)
                    Text(b, fontSize = 13.sp, color = c.secondary)
                }
            }
        }
    }
}


/** The kind of action in the face's small badge (iOS typeIcon). */
private fun typeIcon(e: AdminLog.Event): ImageVector = when (e.type) {
    "member_added" -> Icons.Outlined.PersonAddAlt
    "member_removed" -> Icons.Outlined.PersonRemove
    "member_unbanned", "join_approved" -> Icons.Outlined.HowToReg
    "join_denied" -> Icons.Outlined.PersonOff
    "admin_added", "admin_rights" -> Icons.Outlined.StarOutline
    "admin_removed" -> Icons.Outlined.RemoveModerator
    "owner_changed" -> Icons.Outlined.WorkspacePremium
    "restricted" -> Icons.Outlined.PanTool
    "unrestricted" -> Icons.Outlined.DoNotTouch
    "roles" -> Icons.Outlined.Sell
    "info" -> if (e.fields == listOf("photo")) Icons.Outlined.PhotoCamera else Icons.Outlined.Edit
    "settings" -> Icons.Outlined.Settings
    "pinned", "unpinned" -> Icons.Outlined.PushPin
    "link_created", "link_edited", "link_revoked", "link_deleted", "links_reset" -> Icons.Filled.Link
    "username_set" -> Icons.Outlined.AlternateEmail
    "made_private" -> Icons.Outlined.Lock
    "group_ended" -> Icons.Filled.Close
    "fariin_action" -> Icons.Outlined.VerifiedUser
    else -> Icons.Outlined.Schedule
}

@Composable
private fun MoreLoader(onShown: () -> Unit) {
    LaunchedEffect(Unit) { onShown() }
    Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Fariin.colors.blue, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun EmptyState(icon: Painter, title: String, text: String, button: String? = null, act: (() -> Unit)? = null) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 90.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(64.dp).floating(CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.label, modifier = Modifier.size(28.dp))
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
        Text(text, fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
        if (button != null && act != null) {
            Text(button, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent,
                modifier = Modifier.padding(top = 4.dp).clip(CircleShape).background(c.accent).clickable(onClick = act)
                    .padding(horizontal = 20.dp, vertical = 11.dp))
        }
    }
}

/**
 * Two days of placeholder entries in the page's own shape (iOS recentActionsSkeleton): pill 18 above
 * and 10 below, card radius 26, rows 14 x 11 with a 42 face, the name line with the time at its right.
 */
@Composable
private fun RecentActionsSkeleton() {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
        repeat(2) { day ->
            Box(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
                SkeletonBlock(84.dp, 26.dp, 13.dp)
            }
            Column(Modifier.fillMaxWidth().clip(CardShape26).background(c.card)) {
                repeat(if (day == 0) 4 else 3) { i ->
                    if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 68.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.padding(top = 1.dp)) { SkeletonCircle(42.dp) }
                        Column(Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row {
                                SkeletonBlock(listOf(96, 120, 84, 110)[i % 4].dp, 13.dp)
                                Spacer(Modifier.weight(1f))
                                SkeletonBlock(44.dp, 10.dp)
                            }
                            SkeletonBlock(listOf(200, 160, 220, 140)[i % 4].dp, 12.dp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The reference app's admin log filter (iOS RecentActionsFilterSheet): kinds of action and admins,
 * each with an "All" switch. Applies on Done; filters what has loaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentActionsFilterSheet(people: List<String>, startKinds: Set<AdminLog.Kind>?, startActors: Set<String>?,
                                     name: (String) -> String, photo: (String) -> String?, onClose: () -> Unit,
                                     apply: (Set<AdminLog.Kind>?, Set<String>?) -> Unit) {
    val c = Fariin.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var kinds by remember { mutableStateOf(startKinds ?: AdminLog.Kind.entries.toSet()) }
    var actors by remember { mutableStateOf(startActors ?: people.toSet()) }
    val everyKind = kinds.size == AdminLog.Kind.entries.size
    val everyone = people.all { it in actors }
    val canApply = kinds.isNotEmpty() && (people.isEmpty() || actors.isNotEmpty())
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = c.bgSecondary) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FloatingIcon(Icons.Filled.Close, "Close", onClick = onClose)
            Text("Filter", style = MaterialTheme.typography.titleLarge, color = c.label, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f))
            com.fariin.app.ui.components.FloatingTextAction("Done", enabled = canApply) {
                apply(if (everyKind) null else kinds, if (everyone) null else actors)
                onClose()
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            com.fariin.app.ui.settings.SettingsSection(header = "Actions") {
                SwitchRow("All Actions", everyKind) { kinds = if (it) AdminLog.Kind.entries.toSet() else emptySet() }
                AdminLog.Kind.entries.forEachIndexed { i, k ->
                    CheckRow(k in kinds, last = i == AdminLog.Kind.entries.lastIndex,
                        onClick = { kinds = if (k in kinds) kinds - k else kinds + k }) {
                        Icon(kindIcon(k), null, tint = c.label, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(k.title, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (people.isNotEmpty()) com.fariin.app.ui.settings.SettingsSection(header = "Admins") {
                SwitchRow("All Admins", everyone) { actors = if (it) people.toSet() else emptySet() }
                people.forEachIndexed { i, u ->
                    CheckRow(u in actors, last = i == people.lastIndex, onClick = { actors = if (u in actors) actors - u else actors + u }) {
                        if (u == "fariin") OfficialAvatar(32.dp) else Avatar(photo(u), 32.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(name(u), fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun kindIcon(k: AdminLog.Kind): Painter = when (k) {
    AdminLog.Kind.NEW_MEMBERS -> painterResource(R.drawable.ic_gs_members)
    AdminLog.Kind.JOIN_REQUESTS -> painterResource(R.drawable.ic_gs_join_requests)
    AdminLog.Kind.RESTRICTIONS -> painterResource(R.drawable.ic_gs_banned)
    AdminLog.Kind.ADMINS -> painterResource(R.drawable.ic_gs_admins)
    AdminLog.Kind.INFO -> painterResource(R.drawable.ic_gs_permissions)
    AdminLog.Kind.LINKS -> rememberVectorPainter(Icons.Filled.Link)
    AdminLog.Kind.PINS -> rememberVectorPainter(Icons.Outlined.PushPin)
    AdminLog.Kind.FARIIN -> rememberVectorPainter(Icons.Outlined.VerifiedUser)
}

@Composable
private fun SwitchRow(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.blue))
    }
    HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
}

@Composable
private fun CheckRow(on: Boolean, last: Boolean, onClick: () -> Unit, label: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        label()
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.Check, if (on) "Selected" else null, tint = if (on) c.blue else androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.size(20.dp))
    }
    if (!last) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
}
