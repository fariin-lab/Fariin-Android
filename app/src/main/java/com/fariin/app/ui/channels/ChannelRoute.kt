package com.fariin.app.ui.channels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.fariin.app.channel.ChannelFollows
import com.fariin.app.channel.ChannelInfo
import com.fariin.app.channel.ChannelPreview
import com.fariin.app.channel.ChannelRules
import com.fariin.app.channel.ChannelService
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.Message
import kotlinx.coroutines.launch

/** Where the channel pages open from anywhere under the shell (set by MainActivity's NavHost). */
data class ChannelNav(
    val find: () -> Unit = {},
    val create: () -> Unit = {},
    /** The channel's own page (visitor, follower or admin). */
    val page: (String) -> Unit = {},
    /** The channel's posts (a visitor reads a public channel from outside). */
    val open: (String) -> Unit = {},
)

val LocalChannelNav = staticCompositionLocalOf { ChannelNav() }

fun channelNav(nav: NavHostController) = ChannelNav(
    find = { nav.navigate("findchannels") { launchSingleTop = true } },
    create = { nav.navigate("newchannel") { launchSingleTop = true } },
    page = { nav.navigate("channelpage/$it") { launchSingleTop = true } },
    open = { nav.navigate("thread/$it") { launchSingleTop = true } },
)

/** The channel pages' routes, added to the signed-in NavHost. */
fun NavGraphBuilder.channelRoutes(nav: NavHostController, me: String, chats: () -> List<com.fariin.app.data.Conversation> = { emptyList() }) {
    composable("findchannels") {
        FindChannelsScreen(me, onBack = { nav.popBackStack() }, onOpen = { cid -> nav.navigate("thread/$cid") })
    }
    composable("newchannel") {
        NewChannelScreen(me, onBack = { nav.popBackStack() },
            onDone = { cid -> nav.navigate("thread/$cid") { popUpTo("main"); launchSingleTop = true } })
    }
    composable("channelpage/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@composable
        ChannelPageRoute(cid, me, onBack = { nav.popBackStack() }, onLeft = { nav.popBackStack("main", false) },
            manage = ChannelManageNav(
                edit = { nav.navigate("channeledit/$it") { launchSingleTop = true } },
                followers = { nav.navigate("channelfollowers/$it") { launchSingleTop = true } },
                admins = { nav.navigate("channeladmins/$it") { launchSingleTop = true } },
                links = { nav.navigate("channellinks/$it") { launchSingleTop = true } },
                banned = { nav.navigate("channelbanned/$it") { launchSingleTop = true } },
                reactions = { nav.navigate("channelreactions/$it") { launchSingleTop = true } },
            ))
    }
    // Channel management for its owner and admins (ChannelManage.kt).
    composable("channeledit/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@composable
        ChannelEditRoute(cid, me, onClose = { nav.popBackStack() })
    }
    composable("channelfollowers/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@composable
        ChannelFollowersScreen(cid, me, onBack = { nav.popBackStack() }, chats = chats)
    }
    composable("channeladmins/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@composable
        ChannelAdminsScreen(cid, me, onBack = { nav.popBackStack() }, onOpen = { uid -> nav.navigate("channeladmin/$cid/$uid") { launchSingleTop = true } })
    }
    // The group pages a channel shares (iOS: Invite Link and Banned Users are the same pages).
    composable("channellinks/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@composable
        com.fariin.app.ui.people.GroupPagesHost(cid, me, com.fariin.app.ui.people.GroupPage.Links, onExit = { nav.popBackStack() })
    }
    composable("channelbanned/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@composable
        com.fariin.app.ui.people.GroupPagesHost(cid, me, com.fariin.app.ui.people.GroupPage.Banned, onExit = { nav.popBackStack() })
    }
    composable("channelreactions/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@composable
        ChannelReactionsScreen(cid, onBack = { nav.popBackStack() })
    }
    composable("channeladmin/{cid}/{uid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@composable
        val uid = e.arguments?.getString("uid") ?: return@composable
        ChannelAdminScreen(cid, me, uid, onBack = { nav.popBackStack() })
    }
}

/**
 * The channel half of a chat page, or null when this chat is not a channel. `doc` is the chat's
 * live document (empty when a visitor may not read it: then the preview fills the header).
 */
@Composable
fun rememberChannelThread(cid: String, me: String, doc: Map<String, Any?>, messages: List<Message>, notice: (String) -> Unit): ChannelThreadUi? {
    val entries by ChannelFollows.entries.collectAsState()
    val rx by ChannelFollows.rx.collectAsState()
    val pending by ChannelFollows.pending.collectAsState()
    val served by ChannelFollows.served.collectAsState()
    val bias by ChannelFollows.biasFlow.collectAsState()
    var preview by remember(cid) { mutableStateOf<ChannelPreview?>(null) }
    var joining by remember(cid) { mutableStateOf(false) }
    var problem by remember(cid) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val docInfo = if (doc["channel"] == true) ChannelInfo.from(cid, doc) else null
    // A visitor cannot read the document: the header comes from publicGroupPreview, once the
    // first page of posts says this chat can be read from outside.
    val maybeGuest = docInfo == null && com.fariin.app.crypto.Wire.isGroupCid(cid) && doc.isEmpty()
    LaunchedEffect(cid, maybeGuest) {
        if (!maybeGuest || preview != null) return@LaunchedEffect
        val r = ChannelService.preview(cid)
        if (r is ChannelService.PreviewResult.Found && r.preview.isChannel) {
            preview = r.preview
            CryptoHub.notePlainGroup(cid)   // read from outside = public and not end-to-end encrypted
        }
    }
    val info = docInfo ?: preview?.let { ChannelInfo.fromPreview(it) } ?: return null
    if (!info.isChannel) return null
    val follows = entries.containsKey(cid)
    val role = ChannelRules.role(info, me, follows)
    LaunchedEffect(follows) { if (follows) joining = false }
    val entry = entries[cid]
    // Read marks go forward as posts land (users/{me}/channels/{cid}.read).
    LaunchedEffect(role, info.postCount) { if (role == ChannelRules.Role.FOLLOWER) ChannelService.markRead(cid, info.postCount) }
    // Each post I see counts once (channelViews; a visitor of a public channel counts too).
    val counted = remember(cid) { HashSet<String>() }
    LaunchedEffect(messages.size) {
        val fresh = messages.filter { !it.isSystem && !it.deleted && it.sendState == null && counted.add(it.id) }.map { it.id }
        if (fresh.isNotEmpty()) try { ChannelService.views(cid, fresh) } catch (_: Exception) { counted.removeAll(fresh.toSet()) }
    }
    @Suppress("UNUSED_VARIABLE") val tick = rx.size + pending.size + served.size + bias.size
    return ChannelThreadUi(
        info = info.copy(followerCount = ChannelFollows.adjusted(cid, info.followerCount)),
        role = role,
        guest = role == ChannelRules.Role.VISITOR,
        muted = entry?.isMuted() ?: false,
        canReact = ChannelRules.canReact(info, role, guest = false),
        offered = ChannelRules.offered(info.reactMode, info.reactList),
        pickable = ChannelRules.pickable(info.reactMode, info.reactList),
        mine = { mid -> ChannelFollows.myReaction(cid, mid) },
        held = { mid -> ChannelFollows.confirmedReaction(cid, mid) },
        counts = { m -> ChannelFollows.countsFor(cid, m.id, m.reactionCounts) },
        onFollow = {
            problem = null; joining = true
            scope.launch {
                try {
                    if (ChannelService.follow(cid) == "requested") { joining = false; notice(ChannelRules.REQUEST_SENT) }
                    else notice(ChannelRules.FOLLOWING_NOTICE)
                } catch (e: Exception) { joining = false; problem = ChannelService.errorText(e) }
            }
        },
        onMute = { until -> scope.launch { if (!ChannelService.setMuted(cid, until != null, until ?: 0.0)) notice("Couldn't change that. Try again.") } },
        onReact = { m, e ->
            ChannelFollows.setPending(cid, m.id, e)
            scope.launch {
                try {
                    val counts = ChannelService.react(cid, m.id, e)
                    ChannelFollows.settle(cid, m.id, counts, e)
                } catch (ex: Exception) {
                    ChannelFollows.dropPending(cid, m.id)
                    notice(ChannelService.errorText(ex, "Couldn't react. Try again."))
                }
            }
        },
        onReport = { m -> scope.launch { if (ChannelService.report(cid, m.id)) notice("Thanks. Fariin's moderators will review this post.") else notice("Couldn't send the report. Try again.") } },
        problem = problem,
        joining = joining,
    )
}
