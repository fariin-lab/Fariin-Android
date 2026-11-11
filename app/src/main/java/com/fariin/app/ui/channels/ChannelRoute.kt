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
import com.fariin.app.ui.components.page
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
    /** The official Fariin chat. */
    val official: () -> Unit = {},
    /** Edit Channel (name, photo, description), from the admin card in the chat. */
    val edit: (String) -> Unit = {},
)

val LocalChannelNav = staticCompositionLocalOf { ChannelNav() }

fun channelNav(nav: NavHostController) = ChannelNav(
    find = { nav.navigate("findchannels") { launchSingleTop = true } },
    // Same switch as New Group (iOS NewChannelView guards Flags.groupCreateEnabled).
    create = { if (com.fariin.app.data.AppFeatures.groupCreate) nav.navigate("newchannel") { launchSingleTop = true }
        else com.fariin.app.ui.components.Snack.show(com.fariin.app.data.AppFeatures.CREATE_OFF) },
    page = { nav.navigate("channelpage/$it") { launchSingleTop = true } },
    // Not singleTop: a channel opened from a chat is another "thread/{cid}" page, and singleTop
    // replaced the chat with it, so Back went to the chat list instead of the chat (owner 2026-11-06).
    open = { cid -> if (nav.currentBackStackEntry?.arguments?.getString("cid") != cid) nav.navigate("thread/$cid") },
    official = { nav.navigate("official") { launchSingleTop = true } },
    edit = { nav.navigate("channeledit/$it") { launchSingleTop = true } },
)

/** The channel pages' routes, added to the signed-in NavHost. */
fun NavGraphBuilder.channelRoutes(nav: NavHostController, me: String, chats: () -> List<com.fariin.app.data.Conversation> = { emptyList() }) {
    page("official") { com.fariin.app.ui.official.OfficialChatScreen(onBack = { nav.popBackStack() }) }
    page("findchannels") {
        FindChannelsScreen(me, onBack = { nav.popBackStack() }, onOpen = { cid -> nav.navigate("thread/$cid") })
    }
    page("newchannel") {
        NewChannelScreen(me, onBack = { nav.popBackStack() },
            onDone = { cid -> nav.navigate("thread/$cid") { popUpTo("main"); launchSingleTop = true } })
    }
    page("channelpage/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@page
        ChannelPageRoute(cid, me, onBack = { nav.popBackStack() }, onLeft = { if (!nav.popBackStack("findchannels", false)) nav.popBackStack("main", false) },
            manage = ChannelManageNav(
                edit = { nav.navigate("channeledit/$it") { launchSingleTop = true } },
                followers = { nav.navigate("channelfollowers/$it") { launchSingleTop = true } },
                admins = { nav.navigate("channeladmins/$it") { launchSingleTop = true } },
                links = { nav.navigate("channellinks/$it") { launchSingleTop = true } },
                banned = { nav.navigate("channelbanned/$it") { launchSingleTop = true } },
                reactions = { nav.navigate("channelreactions/$it") { launchSingleTop = true } },
                apiKey = { nav.navigate("channelapikey/$it") { launchSingleTop = true } },
                requests = { nav.navigate("channelrequests/$it") { launchSingleTop = true } },
                settings = { nav.navigate("channelsettings/$it") { launchSingleTop = true } },
            ))
    }
    page("channelsettings/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@page
        ChannelSettingsScreen(cid, me, onBack = { nav.popBackStack() }, nav = ChannelManageNav(
            edit = { nav.navigate("channeledit/$it") { launchSingleTop = true } },
            followers = { nav.navigate("channelfollowers/$it") { launchSingleTop = true } },
            admins = { nav.navigate("channeladmins/$it") { launchSingleTop = true } },
            links = { nav.navigate("channellinks/$it") { launchSingleTop = true } },
            banned = { nav.navigate("channelbanned/$it") { launchSingleTop = true } },
            reactions = { nav.navigate("channelreactions/$it") { launchSingleTop = true } },
            apiKey = { nav.navigate("channelapikey/$it") { launchSingleTop = true } },
            requests = { nav.navigate("channelrequests/$it") { launchSingleTop = true } },
            type = { nav.navigate("channeltype/$it") { launchSingleTop = true } },
            recent = { nav.navigate("channelrecent/$it") { launchSingleTop = true } },
        ))
    }
    page("channelrecent/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@page
        com.fariin.app.ui.people.GroupPagesHost(cid, me, com.fariin.app.ui.people.GroupPage.RecentActions, onExit = { nav.popBackStack() })
    }
    page("channeltype/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@page
        com.fariin.app.ui.people.GroupPagesHost(cid, me, com.fariin.app.ui.people.GroupPage.Type, onExit = { nav.popBackStack() })
    }
    // Channel management for its owner and admins (ChannelManage.kt).
    page("channeledit/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@page
        ChannelEditRoute(cid, me, onClose = { nav.popBackStack() })
    }
    page("channelfollowers/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@page
        ChannelFollowersScreen(cid, me, onBack = { nav.popBackStack() }, chats = chats)
    }
    page("channeladmins/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@page
        ChannelAdminsScreen(cid, me, onBack = { nav.popBackStack() }, onOpen = { uid -> nav.navigate("channeladmin/$cid/$uid") { launchSingleTop = true } })
    }
    // The group pages a channel shares (iOS: Invite Link and Banned Users are the same pages).
    page("channellinks/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@page
        com.fariin.app.ui.people.GroupPagesHost(cid, me, com.fariin.app.ui.people.GroupPage.Links, onExit = { nav.popBackStack() })
    }
    page("channelbanned/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@page
        com.fariin.app.ui.people.GroupPagesHost(cid, me, com.fariin.app.ui.people.GroupPage.Banned, onExit = { nav.popBackStack() })
    }
    page("channelrequests/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@page
        com.fariin.app.ui.people.GroupPagesHost(cid, me, com.fariin.app.ui.people.GroupPage.JoinRequests, onExit = { nav.popBackStack() })
    }
    page("channelapikey/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@page
        ChannelApiKeyScreen(cid, onBack = { nav.popBackStack() })
    }
    page("channelreactions/{cid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@page
        ChannelReactionsScreen(cid, onBack = { nav.popBackStack() })
    }
    page("channeladmin/{cid}/{uid}") { e ->
        val cid = e.arguments?.getString("cid") ?: return@page
        val uid = e.arguments?.getString("uid") ?: return@page
        ChannelAdminScreen(cid, me, uid, onBack = { nav.popBackStack() })
    }
}

/**
 * Visitors' channel previews, kept for the session (a second open draws the channel at once), and
 * the chats whose "is it a channel?" answer is still on its way. While that answer is pending the
 * thread shows a loading ring, not a plain group chat: that drew the admins' notices, deleted rows
 * and a message field for a moment on a cold open (2026-11-06, the Pixel).
 */
object ChannelGuestPreviews {
    val cache = java.util.concurrent.ConcurrentHashMap<String, ChannelPreview>()
    /** Chats whose preview answer came back (channel or not), so they are no longer waiting. */
    val answered = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    /** From the first frame: a group chat I am not in, with no channel flag readable and no answer
     *  yet, may be a channel I visit; until the answer it is drawn as neither. */
    fun waiting(cid: String, me: String, doc: Map<String, Any?>): Boolean =
        // A chat in my list is mine (a member's group, a followed channel): never waiting.
        com.fariin.app.data.ChatsNow.find(cid) == null &&
        doc["channel"] != true && com.fariin.app.crypto.Wire.isGroupCid(cid) &&
            me !in ((doc["users"] as? List<*>) ?: emptyList<Any>()) &&
            cache[cid] == null && answered[cid] != true
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
    var preview by remember(cid) { mutableStateOf<ChannelPreview?>(ChannelGuestPreviews.cache[cid]) }
    var joining by remember(cid) { mutableStateOf(false) }
    var problem by remember(cid) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val docInfo = if (doc["channel"] == true) ChannelInfo.from(cid, doc) else null
    // A visitor cannot read the document: the header comes from publicGroupPreview, once the
    // first page of posts says this chat can be read from outside. Not only an empty doc: a
    // pending local write could leave a doc without `users` in the phone's cache.
    val maybeGuest = docInfo == null && com.fariin.app.crypto.Wire.isGroupCid(cid) &&
        me !in ((doc["users"] as? List<*>) ?: emptyList<Any>())
    // Wake the server's channel functions as the page opens (iOS ChannelService.warm), so the first
    // reaction does not wait 1-2 s for a cold start. At most once a minute per channel.
    LaunchedEffect(cid) { ChannelService.warm(cid) }
    LaunchedEffect(cid, maybeGuest) {
        if (!maybeGuest || preview != null) return@LaunchedEffect
        try {
        var r = ChannelService.preview(cid)
        // A dropped connection is asked again a few times, not taken for "not a channel".
        var tries = 1
        while (r is ChannelService.PreviewResult.Failed && tries < 4) {
            kotlinx.coroutines.delay(2_000L * tries); tries++
            r = ChannelService.preview(cid)
        }
        if (r is ChannelService.PreviewResult.Found && r.preview.isChannel) {
            preview = r.preview
            ChannelGuestPreviews.cache[cid] = r.preview
            CryptoHub.notePlainGroup(cid)   // read from outside = public and not end-to-end encrypted
        } else if (r is ChannelService.PreviewResult.Found && !r.preview.isMember) {
            // A public group, not a channel: the guest's header and Join bar (GroupGuest.kt).
            com.fariin.app.ui.people.GroupGuestPreviews.cache[cid] = r.preview
        }
        } finally { ChannelGuestPreviews.answered[cid] = true }
    }
    val info = docInfo ?: preview?.let { ChannelInfo.fromPreview(it) } ?: return null
    if (!info.isChannel) return null
    val follows = entries.containsKey(cid)
    val role = ChannelRules.role(info, me, follows)
    LaunchedEffect(follows) { if (follows) joining = false }
    // Read marks go forward as posts land (users/{me}/channels/{cid}.read).
    LaunchedEffect(role, info.postCount) { if (role == ChannelRules.Role.FOLLOWER) ChannelService.markRead(cid, info.postCount) }
    // Views: only the posts that come on screen count (ThreadScreen -> ChannelViewQueue).
    @Suppress("UNUSED_VARIABLE") val tick = rx.size + pending.size + served.size + bias.size
    // An uploaded emoji taken off the admin list leaves the long-press bar and "More".
    val retired = com.fariin.app.ui.thread.rememberRetired()
    val liveList = info.reactList.filterNot(retired)
    return ChannelThreadUi(
        info = info.copy(followerCount = ChannelFollows.adjusted(cid, info.followerCount)),
        role = role,
        guest = role == ChannelRules.Role.VISITOR,
        muted = ChannelService.mutedFor(cid, doc, me),
        canReact = ChannelRules.canReact(info, role, guest = false),
        offered = ChannelRules.offered(info.reactMode, liveList),
        pickable = ChannelRules.pickable(info.reactMode, liveList),
        // The lambdas read the collected maps themselves, so a changed pick makes new lambdas and the
        // posts redraw at once. They used to read the store directly: equal lambdas, the list skipped
        // and my reaction showed late (owner's Pixel, VE3, 2026-11-09; found by the iOS session).
        mine = { mid -> pending[cid]?.get(mid)?.let { it.ifEmpty { null } } ?: rx[cid]?.get(mid) },
        held = { mid -> rx[cid]?.get(mid) },
        counts = { m -> served["$cid/${m.id}"] ?: m.reactionCounts },
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
            // One request per post at a time, always the newest wish (iOS ChannelFollows.react):
            // fast taps raced and the counts jumped about.
            com.fariin.app.channel.ChannelReactQueue.want(scope, cid, m.id, e) { ex ->
                ChannelFollows.dropPending(cid, m.id)
                notice(ChannelService.errorText(ex, "Couldn't react. Try again."))
            }
        },
        onReport = { m -> com.fariin.app.data.ReportFlow.ask(com.fariin.app.data.ReportTarget(com.fariin.app.data.ReportKind.POST,
            name = doc["title"] as? String, cid = cid, messageId = m.id)) },
        problem = problem,
        joining = joining,
        notice = notice,
        createdByMe = (doc["createdBy"] as? String) == me,
        canEdit = role == ChannelRules.Role.ADMIN && doc.isNotEmpty() &&
            com.fariin.app.channel.ChannelAdmins.Team.from(doc).can(me, com.fariin.app.channel.ChannelAdmins.Right.CHANGE_INFO),
        shareLink = { com.fariin.app.data.ChannelAddFollowers.link(cid, doc) },
    )
}
