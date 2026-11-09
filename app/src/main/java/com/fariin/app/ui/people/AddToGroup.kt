package com.fariin.app.ui.people

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.Conversation
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.GroupRules
import com.fariin.app.data.GroupWelcome
import com.fariin.app.data.PeopleApi
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.SheetShape
import com.fariin.app.ui.components.Snack
import com.fariin.app.ui.components.sheetFill
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * "Add to a Group" from someone's profile (iOS AddToGroupView): my groups, A to Z; a tap asks
 * "Add New Member" and adds them. Not the new-group flow, only existing groups. A group they are
 * already in says "Already a member"; one where I may not add says why; the name stays in full colour, only the grey line explains (iOS fix 23).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToGroupSheet(api: PeopleApi, uid: String, name: String, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val me = api.me
    val scope = rememberCoroutineScope()
    // My groups with their full records (the add rule reads admins, the members switch and restrictions).
    val groups by produceState<List<Pair<Conversation, Map<String, Any?>>>?>(null, me) {
        val mine = CryptoHub.listedAll().filter { it.isGroup && !it.isChannel && it.users.contains(me) }
            .sortedBy { it.displayName(me).lowercase() }
        val db = FirebaseFirestore.getInstance()
        value = mine.map { g -> g to (runCatching { db.collection("conversations").document(g.id).get().await().data }.getOrNull() ?: emptyMap()) }
    }
    var working by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Conversation?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = sheetFill, shape = SheetShape) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Add to a Group", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp))
            val list = groups
            when {
                list == null -> com.fariin.app.ui.components.PageLoading(Modifier.fillMaxWidth().height(160.dp))
                list.isEmpty() -> Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_gs_members), null, tint = c.secondary, modifier = Modifier.size(40.dp))
                    Text("No Groups", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.label)
                    Text("You're not in any groups yet.", fontSize = 15.sp, color = c.secondary)
                }
                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    items(list, key = { it.first.id }) { (g, doc) ->
                        val already = g.users.contains(uid)
                        val blocked = when {
                            already -> null
                            !GroupWelcome.canAdd(me, doc) -> "Only admins can add members"
                            com.fariin.app.data.AppLimits.groupIsFull(g.isChannel, g.users.size) -> "Group is full"
                            else -> null
                        }
                        val off = already || blocked != null
                        Row(Modifier.fillMaxWidth().clickable(enabled = !off && working == null) { pending = g }
                            .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(g.displayPhoto(me), 44.dp, kind = com.fariin.app.ui.components.AvatarKind.GROUP)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(g.displayName(me), fontSize = 17.sp, color = c.label, maxLines = 1)
                                Text(if (already) "Already a member" else blocked ?: GroupRules.memberCount(g.users.size), fontSize = 13.sp, color = c.secondary)
                            }
                            if (working == g.id) com.fariin.app.ui.components.FariinSpinner(size = 20.dp)
                        }
                    }
                }
            }
        }
    }
    pending?.let { g ->
        AlertDialog(onDismissRequest = { pending = null },
            title = { Text("Add New Member") },
            text = { Text("Add \"$name\" to \"${g.displayName(me)}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null; working = g.id
                    scope.launch {
                        val said = runCatching { api.addMembers(g.id, listOf(uid)) }.getOrElse { "Couldn't add. Check your connection and try again." }
                        working = null
                        if (said == null) { Snack.show("Added to ${g.displayName(me)}"); onDismiss() } else Snack.show(said)
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } })
    }
}
