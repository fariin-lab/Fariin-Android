package com.fariin.app.ui.thread

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.GroupAdmins
import com.fariin.app.data.GroupManage
import com.fariin.app.data.MemberRestrict
import com.fariin.app.data.MemberRestrict.R
import com.fariin.app.data.Message
import com.fariin.app.data.MessageRules
import com.fariin.app.data.SelectRules
import com.fariin.app.ui.components.FariinSwitch
import com.fariin.app.ui.components.SheetShape
import com.fariin.app.ui.components.sheetFill
import com.fariin.app.ui.settings.SettingsSection
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource

/**
 * iOS AdminDeleteSheet (owner 2026-10-18): in a GROUP, an admin deleting SOMEONE ELSE's message gets
 * this sheet in place of the plain question. Report Spam, Delete all from <name> (the delete
 * right), Restrict <name> (the Remove members right, never the owner or an admin), all unticked at
 * first: Proceed alone is the plain delete. Restrict opens "What can <name> do?"; "Fully ban" turns
 * it into remove and ban. Proceed runs the delete, delete-all, report, then ban or restrict.
 */
object AdminDelete {
    /** ThreadView.adminDeleteRequestFor: a group, I hold Delete messages, every pick goes for everyone, one is another member's. */
    fun opens(doc: Map<String, Any?>, me: String, picked: List<Message>): Boolean {
        if ((doc["type"] as? String) != "group" || picked.isEmpty()) return false
        if (!GroupAdmins.Team.from(doc).can(me, GroupAdmins.Right.DELETE_MESSAGES)) return false
        return picked.all { mayDelete(doc, me, it) } && picked.any { it.authorId != me && !it.isSystem }
    }

    /** ThreadView.canDeleteForEveryone, with the delete right read from the group itself. */
    fun mayDelete(doc: Map<String, Any?>, me: String, m: Message): Boolean {
        val t = GroupAdmins.Team.from(doc)
        return MessageRules.canDeleteForEveryone(m, me, t.can(me, GroupAdmins.Right.DELETE_MESSAGES),
            authorIsOwner = t.isOwner(m.authorId), iAmOwner = t.isOwner(me))
    }

    /** loadedDeletable: every LOADED message of these senders that I may delete for everyone. */
    fun loadedByAuthor(doc: Map<String, Any?>, me: String, loaded: List<Message>, authors: List<String>): Map<String, List<Message>> =
        loaded.filter { it.authorId in authors && it.authorId != me && !it.isSystem && mayDelete(doc, me, it) }.groupBy { it.authorId }

    /** The senders in pick order, never me. */
    fun authors(me: String, picked: List<Message>): List<String> =
        picked.map { it.authorId }.filter { it != me && it.isNotEmpty() }.distinct()

    /** canRestrict: the Remove members right, and every sender a plain member still in the group. */
    fun canRestrict(doc: Map<String, Any?>, me: String, authors: List<String>): Boolean {
        val t = GroupAdmins.Team.from(doc)
        return t.can(me, GroupAdmins.Right.BAN_USERS) && authors.all { !t.isOwner(it) && !t.isAdmin(it) && it in t.users }
    }

    fun title(n: Int) = "Delete $n Message${if (n == 1) "" else "s"}"

    /** The toast after Proceed (iOS applyAdminDelete). */
    fun doneLine(reported: Boolean, banned: List<String>, restricted: List<String>, refused: Boolean, name: (String) -> String): String {
        if (refused) return "Some of that couldn't be done"
        val said = mutableListOf<String>()
        if (reported) said += "Reported as spam"
        if (banned.isNotEmpty()) said += if (banned.size == 1) "${name(banned[0])} removed" else "${banned.size} people removed"
        if (restricted.isNotEmpty()) said += if (restricted.size == 1) "${name(restricted[0])} restricted" else "${restricted.size} people restricted"
        return said.joinToString(". ")
    }

    /** Outlives the sheet and the chat screen: Proceed's writes finish after both close. */
    internal val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdminDeleteSheet(picked: List<Message>, ui: ThreadUi, actions: ThreadActions, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val doc = ui.groupDoc
    val me = ui.me
    val authors = remember(picked) { AdminDelete.authors(me, picked) }
    val single = authors.size == 1
    fun name(uid: String) = ui.names[uid]?.ifEmpty { null } ?: "Member"
    val first = name(authors.firstOrNull() ?: "")
    val loaded = remember(picked, ui.messages) { AdminDelete.loadedByAuthor(doc, me, ui.messages, authors) }
    val canRestrict = AdminDelete.canRestrict(doc, me, authors)
    var report by remember { mutableStateOf(false) }
    var deleteAll by remember { mutableStateOf(false) }
    var restrict by remember { mutableStateOf(false) }
    var banMode by remember { mutableStateOf(false) }
    var mediaOpen by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(MemberRestrict.seed(doc, authors, System.currentTimeMillis().toDouble())) }

    val deleteMsgs: List<Message> = buildList {
        addAll(picked)
        val have = picked.map { it.id }.toMutableSet()
        if (deleteAll) authors.forEach { a -> loaded[a].orEmpty().forEach { if (have.add(it.id)) add(it) } }
    }

    fun proceed() {
        val now = System.currentTimeMillis().toDouble()
        val spam = if (report) authors.associateWith { a -> picked.filter { it.authorId == a }.map { it.id } } else emptyMap()
        val ban = if (canRestrict && restrict && banMode) authors else emptyList()
        val limits: Map<String, List<String>> = if (canRestrict && restrict && !banMode) authors.mapNotNull { a ->
            val f = MemberRestrict.flagsFor(doc, a, allowed, now)
            // Nothing held back now and nothing before: no write, no notice.
            if (f.isEmpty() && MemberRestrict.active(doc, a, now).isEmpty()) null else a to f
        }.toMap() else emptyMap()
        val ends = limits.keys.associateWith { MemberRestrict.sheetEnd(doc, it, now) }
        val names = authors.associateWith { name(it) }
        val cid = ui.cid
        onDismiss()
        // The picked ones through the single delete (the media viewer closes on it), the rest as one plan.
        picked.forEach { actions.onDeleteForEveryone(it) }
        val more = deleteMsgs.drop(picked.size)
        if (more.isNotEmpty()) actions.onDeleteMany(SelectRules.Plan(forEveryone = more, forMe = emptyList()))
        if (spam.isEmpty() && ban.isEmpty() && limits.isEmpty()) return
        val notice = actions.onNotice
        AdminDelete.scope.launch {
            for ((a, ids) in spam) GroupManage.reportSpam(a, cid, ids)
            var refused = false
            for (a in ban) runCatching { GroupManage.removeMember(cid, a, names[a] ?: "Member") }.onFailure { refused = true }
            for ((a, f) in limits) runCatching {
                GroupManage.restrictMember(cid, a, names[a] ?: "Member", f, if (f.isEmpty()) 0.0 else ends[a] ?: 0.0)
            }.onFailure { refused = true }
            notice(AdminDelete.doneLine(spam.isNotEmpty(), ban, limits.keys.toList(), refused) { names[it] ?: "Member" })
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape, containerColor = sheetFill) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(AdminDelete.title(deleteMsgs.size), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                val restrictTitle = when {
                    banMode -> if (single) "Remove and ban $first" else "Remove and ban senders"
                    else -> if (single) "Restrict $first" else "Restrict senders"
                }
                SettingsSection(header = "Additional actions") {
                    TickRow("Report Spam", report, last = false) { report = !report }
                    val n = authors.sumOf { loaded[it]?.size ?: 0 }
                    TickRow(if (single) "Delete all from $first" else "Delete all from senders", deleteAll,
                        supporting = "$n loaded message${if (n == 1) "" else "s"}", last = !canRestrict) { deleteAll = !deleteAll }
                    if (canRestrict) TickRow(restrictTitle, restrict, last = true) { restrict = !restrict }
                }
                if (canRestrict && banMode && restrict) TextButton({ banMode = false }, Modifier.padding(start = 20.dp)) {
                    Text("Restrict instead", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.accent)
                }
                AnimatedVisibility(canRestrict && restrict && !banMode) {
                    Column {
                        SettingsSection(header = if (single) "What can $first do?" else "What can these members do?",
                            footer = "Switches that are grey are off for everyone in this group. The restriction stays until an admin lifts it.") {
                            SwitchLine(MemberRestrict.label(R.SEND_TEXT), R.SEND_TEXT in allowed, MemberRestrict.groupLets(doc, R.SEND_TEXT)) {
                                allowed = MemberRestrict.setAllowed(doc, allowed, R.SEND_TEXT, it)
                            }
                            val onCount = MemberRestrict.MEDIA.count { it in allowed }
                            SwitchLine("Send Media", onCount > 0, MemberRestrict.groupLets(doc, R.SEND_PHOTOS),
                                extra = { MediaCount(onCount, mediaOpen) { mediaOpen = !mediaOpen } }) {
                                allowed = MemberRestrict.setAllMedia(doc, allowed, it)
                            }
                            if (mediaOpen) MemberRestrict.MEDIA.forEach { r ->
                                SwitchLine(MemberRestrict.label(r), r in allowed, MemberRestrict.groupLets(doc, r), indent = true) {
                                    allowed = MemberRestrict.setAllowed(doc, allowed, r, it)
                                }
                            }
                            // No Pin Messages switch: members never pin in a Fariin group (an admin right).
                            SwitchLine(MemberRestrict.label(R.ADD_MEMBERS), R.ADD_MEMBERS in allowed, MemberRestrict.groupLets(doc, R.ADD_MEMBERS)) {
                                allowed = MemberRestrict.setAllowed(doc, allowed, R.ADD_MEMBERS, it)
                            }
                            SwitchLine(MemberRestrict.label(R.CHANGE_INFO), R.CHANGE_INFO in allowed, MemberRestrict.groupLets(doc, R.CHANGE_INFO), last = true) {
                                allowed = MemberRestrict.setAllowed(doc, allowed, R.CHANGE_INFO, it)
                            }
                        }
                        TextButton({ banMode = true }, Modifier.padding(start = 20.dp)) {
                            Text(if (single) "Fully ban this member" else "Fully ban these members", fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold, color = c.red)
                        }
                    }
                }
            }
            Text("Proceed", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(50.dp).clip(CircleShape)
                    .background(c.accent).clickable(onClickLabel = "Proceed") { proceed() }.padding(vertical = 14.dp))
        }
    }
}

@Composable
private fun TickRow(title: String, on: Boolean, supporting: String? = null, last: Boolean, onClick: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (on) Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_checkmark_circle_fill), contentDescription = null, tint = c.accent, modifier = Modifier.size(24.dp))
        else Box(Modifier.size(24.dp).padding(2.dp).border(1.5.dp, c.secondary, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, color = c.label)
            if (supporting != null) Text(supporting, fontSize = 13.sp, color = c.secondary)
        }
    }
    if (!last) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 52.dp))
}

@Composable
private fun SwitchLine(title: String, on: Boolean, enabled: Boolean, indent: Boolean = false, last: Boolean = false,
                       extra: (@Composable () -> Unit)? = null, onChange: (Boolean) -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = if (indent) 36.dp else 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 17.sp, color = if (enabled) c.label else c.secondary)
        extra?.invoke()
        Spacer(Modifier.weight(1f))
        FariinSwitch(checked = enabled && on, onCheckedChange = onChange, enabled = enabled)
    }
    if (!last) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
}

@Composable
private fun MediaCount(on: Int, open: Boolean, onClick: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.clip(CircleShape).clickable(onClickLabel = if (open) "Hide media kinds" else "Show media kinds", onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$on/${MemberRestrict.MEDIA.size}", fontSize = 15.sp, color = c.secondary)
        Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_chevron_down), contentDescription = null, tint = c.secondary,
            modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f))
    }
}
