package com.fariin.app.ui.people

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.fariin.app.data.Conversation
import com.fariin.app.data.PeopleApi
import com.google.firebase.firestore.FirebaseFirestore

/** The people pages' routes, added to both the signed-in and the demo NavHost. */
fun NavGraphBuilder.peopleRoutes(
    nav: NavHostController,
    api: PeopleApi,
    chats: () -> List<Conversation>,
    requests: () -> List<Conversation>,
    groupDoc: @Composable (String) -> Map<String, Any?>,
) {
    val openChat: (String) -> Unit = { cid -> nav.navigate("thread/$cid") { popUpTo("main"); launchSingleTop = true } }
    composable("newchat") {
        NewChatScreen(api, chats(), onBack = { nav.popBackStack() }, onOpenChat = openChat, onNewGroup = { nav.navigate("newgroup") })
    }
    composable("requests") { MessageRequestsScreen(api, requests(), onBack = { nav.popBackStack() }, onOpenChat = openChat) }
    composable("contact/{uid}") { e ->
        val uid = e.arguments?.getString("uid") ?: return@composable
        val conv = chats().firstOrNull { !it.isGroup && it.users.contains(uid) }
        ContactInfoScreen(api, uid, conv, onBack = { nav.popBackStack() }, onOpenChat = openChat)
    }
    composable("group/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@composable
        GroupInfoScreen(api, groupDoc(cid), cid, chats(), onBack = { nav.popBackStack() },
            onLeft = { nav.popBackStack("main", false) }, onOpenPerson = { nav.navigate("contact/$it") })
    }
    composable("newgroup") { NewGroupScreen(api, chats(), onBack = { nav.popBackStack() }, onCreated = openChat) }
    composable("editprofile") { EditProfileScreen(api, onBack = { nav.popBackStack() }) }
    // Calls tab: New Call, call links (ui/calls).
    composable("newcall") {
        com.fariin.app.ui.calls.NewCallScreen(api.me, chats(), onBack = { nav.popBackStack() },
            onCreateLink = { nav.navigate("calllink/new") { popUpTo("newcall") { inclusive = true } } })
    }
    composable("calllink/new") { com.fariin.app.ui.calls.CreateCallLinkScreen(onClose = { nav.popBackStack() }) }
    composable("calllink/{roomId}") { e ->
        val id = e.arguments?.getString("roomId") ?: return@composable
        com.fariin.app.ui.calls.CallLinkDetailsScreen(id, onBack = { nav.popBackStack() })
    }
    composable("joinlink/{key}/{video}") { e ->
        val key = e.arguments?.getString("key") ?: return@composable
        com.fariin.app.ui.calls.JoinLinkScreen(key, e.arguments?.getString("video") != "voice", onBack = { nav.popBackStack() })
    }
}

fun peopleNav(nav: NavHostController, api: PeopleApi? = null) = PeopleNav(
    newChat = { nav.navigate("newchat") { launchSingleTop = true } },
    requests = { nav.navigate("requests") { launchSingleTop = true } },
    contact = { nav.navigate("contact/$it") { launchSingleTop = true } },
    group = { nav.navigate("group/$it") { launchSingleTop = true } },
    newGroup = { nav.navigate("newgroup") { launchSingleTop = true } },
    editProfile = { nav.navigate("editprofile") { launchSingleTop = true } },
    newCall = { nav.navigate("newcall") { launchSingleTop = true } },
    newCallLink = { nav.navigate("calllink/new") { launchSingleTop = true } },
    callLink = { nav.navigate("calllink/$it") { launchSingleTop = true } },
    joinLink = { key, video -> nav.navigate("joinlink/$key/${if (video) "video" else "voice"}") { launchSingleTop = true } },
    chat = { nav.navigate("thread/$it") { popUpTo("main"); launchSingleTop = true } },
    searchPeople = { q -> api?.let { runCatching { it.search(q) }.getOrDefault(emptyList()) }.orEmpty() },
    startChat = { p -> api?.let { runCatching { it.openChat(p) }.getOrNull() } },
)

/** The live group document (read only), for Group Info. */
@Composable
fun liveGroupDoc(cid: String): Map<String, Any?> {
    // A group I was removed from this session opens as I last saw it, without me (iOS FormerGroups).
    var data by remember(cid) { mutableStateOf<Map<String, Any?>>(com.fariin.app.data.FormerGroups.get(cid) ?: emptyMap()) }
    DisposableEffect(cid) {
        val reg = FirebaseFirestore.getInstance().collection("conversations").document(cid)
            .addSnapshotListener { s, e ->
                s?.data?.let { data = it }
                // Refused: taken out of the group while this page is open. The page draws me out of it.
                val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                if (s == null && me != null && data.isNotEmpty() && com.fariin.app.crypto.Wire.isGroupCid(cid) &&
                    (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code ==
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    data = com.fariin.app.data.FormerGroups.strip(data, me)
                }
            }
        onDispose { reg.remove() }
    }
    return data
}
