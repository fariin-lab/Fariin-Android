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
}

fun peopleNav(nav: NavHostController) = PeopleNav(
    newChat = { nav.navigate("newchat") { launchSingleTop = true } },
    requests = { nav.navigate("requests") { launchSingleTop = true } },
    contact = { nav.navigate("contact/$it") { launchSingleTop = true } },
    group = { nav.navigate("group/$it") { launchSingleTop = true } },
    newGroup = { nav.navigate("newgroup") { launchSingleTop = true } },
    editProfile = { nav.navigate("editprofile") { launchSingleTop = true } },
)

/** The live group document (read only), for Group Info. */
@Composable
fun liveGroupDoc(cid: String): Map<String, Any?> {
    var data by remember(cid) { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    DisposableEffect(cid) {
        val reg = FirebaseFirestore.getInstance().collection("conversations").document(cid)
            .addSnapshotListener { s, _ -> s?.data?.let { data = it } }
        onDispose { reg.remove() }
    }
    return data
}
