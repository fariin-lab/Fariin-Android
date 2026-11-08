package com.fariin.app.ui.stories

import androidx.compose.material.icons.filled.Search
import com.fariin.app.ui.components.frostedTopBar
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.zIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.fariin.app.data.StoryMedia
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fariin.app.R
import com.fariin.app.data.Conversation
import com.fariin.app.data.MyProfile
import com.fariin.app.data.MyProfileRepository
import com.fariin.app.data.StoriesRepository
import com.fariin.app.data.StoriesState
import com.fariin.app.data.StoryGroup
import com.fariin.app.data.StoryList
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.delay

/**
 * The Stories tab in the iOS list style (StoriesTabView.friendsList): "My Story >", my row
 * (Add Story when I have none), then "Recent Stories" (unseen) and "Viewed Stories", which
 * folds. Each row is the newest story's picture with the author's face in the corner, a green
 * to blue ring while unseen, grey once seen. Read only.
 *
 * A row opens the viewer (StoryViewer.kt) on that ring and walks on through the ones below it;
 * the camera button and Add Story open the story camera (StoryCamera.kt), whose gallery button picks
 * a photo or video; either way the composer (StoryComposer.kt) follows.
 *
 * Long press on a row: Send Message and Hide Stories (Unhide Story on the dimmed Hidden Stories
 * list); on my row Add Story and Posted Stories, which the "My Story >" heading also opens.
 *
 * The three-dot menu holds Story privacy (iOS moreMenu), which opens the Stories page of Settings;
 * a row's face wears one arc per story (StoryRing).
 */
@Composable
fun StoriesTab(me: String, conversations: List<Conversation>, demo: Pair<MyProfile, StoriesState>?, addRequest: Int = 0, shared: StoriesState? = null,
               /** False while another tab is open (MainShell keeps this one alive behind it). */
               active: Boolean = true) {
    val state: StoriesState
    val profile: MyProfile?
    if (demo != null) {
        profile = demo.first
        state = demo.second
    } else {
        val profRepo = remember { MyProfileRepository() }
        profile = remember(me) { profRepo.listen(me) }.collectFlow(null)
        val convs by rememberUpdatedState(conversations)
        val prof by rememberUpdatedState(profile)
        // The app's one feed when MainShell passes it (the chat list's rings read it too).
        state = shared ?: run {
            val repo = remember { StoriesRepository() }
            remember(me) {
                repo.listen(me, { convs }, { prof?.name?.ifEmpty { null } ?: "You" }, { prof?.photoUrl })
            }.collectFlow(StoriesState())
        }
    }
    StoriesScreen(me, conversations, profile, state, live = demo == null, addRequest, active)
}

@Composable
private fun <T> kotlinx.coroutines.flow.Flow<T>.collectFlow(initial: T): T {
    var v by remember(this) { mutableStateOf(initial) }
    LaunchedEffect(this) { collect { v = it } }
    return v
}

@Composable
private fun StoriesScreen(me: String, conversations: List<Conversation>, profile: MyProfile?, state: StoriesState, live: Boolean, addRequest: Int, active: Boolean = true) {
    val c = Fariin.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The viewer: which rings it walks through and where it starts.
    var viewing by remember { mutableStateOf<Pair<List<StoryGroup>, Int>?>(null) }
    // Posted Stories' tile the open story flies out of and back into (null: the ring's own card).
    var viewingOrigin by remember { mutableStateOf<StoryOrigin?>(null) }
    // SEVERAL IN ONE POST (iOS StoryEditorView items: the editor's + ticks up to five, the strip
    // moves between them, Post shares each as its own story). ONE editor holds the whole post; an
    // item's uri is its library photo (null = a camera capture or a text story), so the + shows it
    // ticked. [postId] gives each new post a fresh editor.
    val post = remember { androidx.compose.runtime.mutableStateListOf<StoryItemState>() }
    var postId by remember { mutableIntStateOf(0) }
    var composingText by remember { mutableStateOf(false) }
    fun openPost(uri: android.net.Uri?, m: StoryMedia.Prepared) {
        post.clear(); post.add(StoryItemState(m, uri)); postId++
    }
    var addMore by remember { mutableStateOf(false) }
    var pickError by remember { mutableStateOf<String?>(null) }
    // iOS "That video is too long" (past the 10-minute pick) and "That's today's limit".
    var tooLong by remember { mutableStateOf(false) }
    var dayLimit by remember { mutableStateOf(false) }
    val upload by StoryUploads.state.collectAsState()
    val pendingJpeg by StoryUploads.pending.collectAsState()
    val pendingImage = remember(pendingJpeg) {
        pendingJpeg?.let { b -> runCatching { android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() }
    }
    // The story's own library sheet (StoryLibrary.kt, iOS StoryLibraryPicker), never the system
    // picker. It stays open under the editor, so closing the editor comes back to it (iOS).
    var library by remember { mutableStateOf(false) }
    var libraryBusy by remember { mutableStateOf(false) }
    // A picked photo in the air from its tile to the editor (StoryLibraryFlight.kt, iOS flyFromTile).
    var libraryFlight by remember { mutableStateOf<LibraryFlight?>(null) }
    var arriving by remember { mutableStateOf(false) }
    val pickFromLibrary: (android.net.Uri) -> Unit = { uri ->
        if (!libraryBusy && libraryFlight == null) {
            libraryBusy = true
            scope.launch {
                prepareStoryMedia(context, uri)
                    .onSuccess {
                        pickError = null
                        // From its tile when the tile is on screen; the editor opens as it lands.
                        // The editor is built at once, unseen, while the picture flies; it shows as it lands.
                        if (StoryLibraryTiles.has(uri) && com.fariin.app.ui.components.Motion.on) { libraryFlight = LibraryFlight(uri, it); arriving = true; openPost(uri, it) }
                        else openPost(uri, it)
                    }
                    .onFailure { if (it is StoryMedia.TooLong) tooLong = true else pickError = it.message ?: "Couldn't open this file." }
                libraryBusy = false
            }
        }
    }
    // The story camera (StoryCamera.kt); its gallery button opens the library, as iOS onLibrary.
    var camera by rememberSaveable { mutableStateOf(false) }
    val openLibrary: () -> Unit = { if (live) library = true }
    // The day's allowance is asked before the camera opens (iOS composeStory), from the counter read
    // when the tab shows; it fails open, and the rules are what hold the line.
    val limits = com.fariin.app.data.StoryLimits
    LaunchedEffect(profile?.verified) { limits.verified = profile?.verified == true }
    LaunchedEffect(me, live, active) { if (live && active) limits.refresh(me) }
    val addStory: () -> Unit = {
        if (live) {
            if (limits.dailyReached()) {
                dayLimit = true
                // A window that rolled while the app sat open opens the camera on the next tap.
                scope.launch { limits.refresh(me) }
            } else camera = true
        }
    }
    // The chat list's camera button: open the camera once per press (not again on recomposition).
    // The count lives outside the tab: the tab's own saved state is dropped when another tab is
    // open, and coming back to Stories must not open the camera again. A smaller count = a new shell.
    LaunchedEffect(addRequest) {
        if (addRequest < AddRequests.handled) AddRequests.handled = addRequest
        if (addRequest > AddRequests.handled) { AddRequests.handled = addRequest; addStory() }
    }
    LaunchedEffect(pickError) { if (pickError != null) { delay(3000); pickError = null } }
    // "Xm ago" moves once a minute.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) { if (active) { now = System.currentTimeMillis(); while (true) { delay(60_000); now = System.currentTimeMillis() } } }
    var viewedCollapsed by rememberSaveable { mutableStateOf(false) }
    var hiddenCollapsed by rememberSaveable { mutableStateOf(false) }
    var posted by rememberSaveable { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    // The Settings pages pushed from the menu (Stories, then its audience pages); empty = closed.
    val privacyPages = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var hideTarget by remember { mutableStateOf<StoryGroup?>(null) }
    val openChat = com.fariin.app.ui.thread.LocalOpenChat.current
    // Hide Stories (iOS StoryPrefs "hiddenStories") and my story lists (Settings > Stories).
    LaunchedEffect(Unit) { com.fariin.app.data.StoryMutes.load(context) }
    LaunchedEffect(me, live) { if (live) com.fariin.app.data.StoryPrivacyStore.start(me) }
    val muted by com.fariin.app.data.StoryMutes.set.collectAsState()
    val myName = profile?.name?.ifEmpty { null } ?: "You"
    val (shown, hidden) = com.fariin.app.data.StoryMutes.split(state.others, muted)
    val recent = shown.filter { it.hasUnseen }
    val viewed = shown.filter { !it.hasUnseen }
    val ordered = recent + viewed
    // My Story leads into everyone else, in the page's order (iOS openStoryFromRow, owner 2026-11-07:
    // from my own story a swipe could not reach the next person). Each ring once.
    fun withOthers(mine: StoryGroup) = (listOf(mine) + ordered).distinctBy { it.key }
    // iOS .searchable "Search": typing replaces the page with name matches (StoriesTabView.searchResults).
    var search by rememberSaveable { mutableStateOf("") }
    val query = search.trim()
    val matches = if (query.isEmpty()) emptyList() else shown.filter { it.name.contains(query, ignoreCase = true) }
    val openRing: (StoryGroup) -> Unit = { g -> viewing = ordered to ordered.indexOfFirst { it.key == g.key }.coerceAtLeast(0) }
    // A hidden person's story walks the Hidden Stories list, not the page above it (iOS).
    val openHidden: (StoryGroup) -> Unit = { g -> viewing = hidden to hidden.indexOfFirst { it.key == g.key }.coerceAtLeast(0) }
    val sendMessage: (StoryGroup) -> Unit = { g ->
        val uid = g.stories.firstOrNull()?.authorUid
        val conv = if (g.key.startsWith(StoryGroup.GROUP_PREFIX)) conversations.firstOrNull { it.id == g.key.removePrefix(StoryGroup.GROUP_PREFIX) }
            else conversations.firstOrNull { !it.isGroup && it.otherUid(me) == uid }
        if (conv != null) openChat(conv)
    }
    fun friendMenu(g: StoryGroup) = listOf(
        RowAction("Send Message", R.drawable.ic_menu_chat) { sendMessage(g) },
        RowAction("Hide Stories", R.drawable.ic_eye_off, destructive = true) { hideTarget = g },
    )
    fun hiddenMenu(g: StoryGroup) = listOf(
        RowAction("Send Message", R.drawable.ic_menu_chat) { sendMessage(g) },
        RowAction("Unhide Story", R.drawable.ic_stories) {
            g.stories.firstOrNull()?.authorUid?.let { com.fariin.app.data.StoryMutes.setHidden(context, it, false) }
        },
    )
    val myMenu = listOf(
        RowAction("Add Story", R.drawable.ic_stories) { addStory() },
        RowAction("Posted Stories", R.drawable.ic_stories_stack) { posted = true },
    )
    // Glow: the Glowing grid's cards, the viewer door for them, and the page behind its heading.
    val glowOn = live && com.fariin.app.data.AppFeatures.glow
    val glowState by com.fariin.app.data.Glow.state.collectAsState()
    val glowCards = if (glowOn) com.fariin.app.ui.glow.rememberGlowCards(me) else com.fariin.app.data.GlowLoad.Loaded(emptyList())
    val glowOpener = remember { com.fariin.app.ui.glow.GlowOpener() }
    var glowPage by rememberSaveable { mutableStateOf(false) }
    // Glow notifications (iOS GlowNotificationsView): the iOS bell is not drawn in this layout, so
    // its door is in the overflow, with the bell's unread dot (iOS hasUnreadGlow).
    var glowNotifs by rememberSaveable { mutableStateOf(false) }
    // The tab stays alive behind the others (MainShell), but its pages are windows of their own and
    // would stay on screen over the open tab: they close when it is hidden, as they did when the
    // whole tab went.
    LaunchedEffect(active) {
        if (!active) {
            viewing = null; camera = false; library = false; post.clear(); composingText = false; posted = false; tooLong = false; dayLimit = false
            addMore = false; libraryFlight = null
            privacyPages.clear(); glowPage = false; glowNotifs = false; hideTarget = null; moreOpen = false
        }
    }
    val glowSeenUpTo by com.fariin.app.data.Glow.seenUpTo.collectAsState()
    var newestGlowMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(glowOn, glowState.glowers) {
        if (glowOn) newestGlowMs = runCatching { com.fariin.app.data.Glow.fetchRecentGlowers(1).firstOrNull()?.second }.getOrNull()
    }
    val glowUnread = glowOn && (newestGlowMs?.let { it > glowSeenUpTo } ?: (glowState.glowers.isNotEmpty() && glowSeenUpTo == 0L))
    // A glow person's ring is grey once their stories in my feed are all seen.
    val glowSeen: (String) -> Boolean = { uid -> state.others.firstOrNull { it.key == uid }?.hasUnseen == false }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // A second tap on Stories: to the top.
    com.fariin.app.ui.shell.OnTabReselect { listState.animateScrollToItem(0) }

    // The list runs under a frosted bar (owner 2026-11-08, as Calls and Settings): the rows show
    // blurred through it as they scroll under; 48 high, no line.
    val storiesHaze = remember { dev.chrisbanes.haze.HazeState() }
    val barScope = androidx.compose.runtime.rememberCoroutineScope()
    // The field scrolled up under the bar: a search button takes its place (as Calls and the chat list).
    val searchGone by remember { androidx.compose.runtime.derivedStateOf {
        val info = listState.layoutInfo.visibleItemsInfo
        val field = info.firstOrNull { it.key == "search" }
        if (field == null) info.isNotEmpty() && info.first().index > 0 else field.offset + field.size * 0.1f <= 0
    } }
    val frost = com.fariin.app.perf.rememberPowerState().glass()
    val topPad = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 48.dp
    Box(Modifier.fillMaxSize().background(c.bg)) {
        // iOS StoriesTabView bar: the ••• menu in a round glass button at the LEADING edge and
        // "Stories" centred (inline title, 17 semibold). No camera here: iOS took add-story off the
        // header (owner 2026-10-11, "I don't need it"); the camera on My Story's row posts.
        Box(Modifier.zIndex(1f).fillMaxWidth().then(Modifier.frostedTopBar(storiesHaze, c.bg, frost))
            .statusBarsPadding().height(48.dp).padding(horizontal = 16.dp)) {
            Text(com.fariin.app.ui.chats.rememberConnectionTitle("Stories"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp))
            androidx.compose.animation.AnimatedVisibility(searchGone && query.isEmpty(), Modifier.align(Alignment.CenterEnd),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.6f),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.6f)) {
                com.fariin.app.ui.components.FloatingIcon(Icons.Filled.Search, contentDescription = "Search",
                    onClick = { barScope.launch { listState.animateScrollToItem(0) } }, circle = true)
            }
            // iOS moreMenu: one entry, Story privacy, straight to the Stories page in Settings.
            Box(Modifier.align(Alignment.CenterStart)) {
                com.fariin.app.ui.components.FloatingIcon(Icons.Filled.MoreHoriz, contentDescription = "More", onClick = { moreOpen = true }, circle = true)
                if (glowUnread) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(8.dp).clip(CircleShape).background(c.accent))
                androidx.compose.material3.DropdownMenu(moreOpen, onDismissRequest = { moreOpen = false },
                    shape = com.fariin.app.ui.components.FariinMenu.shape, border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Story privacy", color = c.label) },
                        leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null, tint = c.label) },
                        onClick = { moreOpen = false; privacyPages.add(com.fariin.app.ui.settings.PAGE_STORIES) })
                    if (glowOn) androidx.compose.material3.DropdownMenuItem(text = { Text("Notifications", color = c.label) },
                        leadingIcon = { Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_bell), contentDescription = null, tint = c.label) },
                        trailingIcon = if (glowUnread) { { Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent)) } } else null,
                        onClick = { moreOpen = false; glowNotifs = true })
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().then(if (frost) Modifier.hazeSource(storiesHaze) else Modifier), state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = topPad, bottom = com.fariin.app.ui.shell.bottomBarInset())) {
            // A little air under the bar's buttons (owner 2026-11-08: "too close" to the ••• circle).
            item(key = "search", contentType = "search") { Box(Modifier.padding(top = 10.dp)) { com.fariin.app.ui.calls.SearchField(search, { search = it }, "Search", hiddenPx = {
                (-(listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "search" }?.offset ?: 0)).toFloat().coerceAtLeast(0f)
            }) } }
            // The field replaces the page rather than filtering it in place (iOS): one list of
            // matches in the list's own rows under "Friends", or "No Results".
            if (query.isNotEmpty()) {
                if (matches.isEmpty()) item(key = "noResults") { NoResults(query) }
                else {
                    item(key = "searchHeading") { Heading("Friends") }
                    items(matches, key = { "s" + it.key }) { g ->
                        FriendRow(g, now, friendMenu(g)) { viewing = matches to matches.indexOfFirst { it.key == g.key }.coerceAtLeast(0) }
                    }
                }
            } else {
            item {
                Row(
                    Modifier.clickable { posted = true }.padding(start = 20.dp, end = 20.dp, top = 8.dp)
                        .semantics { contentDescription = "Posted Stories" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // iOS: 17 semibold in the soft heading ink, 6 to a grey 13 pt chevron.
                    Text("My Story", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = headingInk())
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.secondary, modifier = Modifier.size(18.dp))
                }
            }
            item {
                val mine = state.mine
                val newest = mine?.stories?.lastOrNull()
                // iOS uploadPhase: "Preparing…" for the phone's half, "Uploading…" once it is sending.
                val sending by com.fariin.app.data.StoryOutbox.sending.collectAsState()
                val phaseLine = if (sending) "Uploading…" else "Preparing…"
                // A failed post still waiting on disk: the row says so and a tap sends it again, after
                // the alert is gone too (the reconnect retry still runs by itself, MainShell).
                val retryWaiting = remember(upload, mine?.stories?.size) {
                    upload !is StoryUploads.State.Uploading && com.fariin.app.data.StoryOutbox.hasWaiting(context, me)
                }
                val retryNow = { StoryUploads.state.value = null; StoryUploads.retry(context, me, storyConvs(conversations)) }
                val uploadLine = when (val u = upload) {
                    StoryUploads.State.Uploading -> phaseLine
                    is StoryUploads.State.Failed -> if (retryWaiting) com.fariin.app.data.StoryOutbox.RETRY_LINE else u.message
                    null -> if (retryWaiting) com.fariin.app.data.StoryOutbox.RETRY_LINE else null
                }
                if (upload == StoryUploads.State.Uploading) {
                    // iOS: while it uploads, My Story shows the picture going up and the ring turns
                    // round my face; the post's flight lands on this card (StoryPostFlight).
                    StoryRow(
                        thumbUrl = newest?.listThumb ?: "", title = "My Story", subtitle = phaseLine,
                        authorPhoto = profile?.photoUrl, seen = true, camera = true,
                        thumbImage = pendingImage, uploading = true,
                        backThumbUrl = if (pendingImage != null) newest?.listThumb else mine?.stories?.let { it.getOrNull(it.size - 2) }?.listThumb,
                        originKey = StoryPostFlight.UPLOAD_KEY,
                        onClick = { mine?.let { viewing = withOthers(it) to 0 } }, onCamera = addStory, menu = myMenu,
                    )
                } else if (mine != null && newest != null) {
                    StoryRow(
                        thumbUrl = newest.listThumb, title = "My Story", subtitle = uploadLine ?: StoryList.ago(newest.createdAtMs, now),
                        authorPhoto = profile?.photoUrl, seen = !mine.hasUnseen, camera = true,
                        seenFlags = com.fariin.app.data.StoryRings.seenFlags(mine),
                        backThumbUrl = mine.stories.getOrNull(mine.stories.size - 2)?.listThumb,
                        originKey = mine.key,
                        onClick = { if (retryWaiting) retryNow() else { viewing = withOthers(mine) to 0 } }, onCamera = addStory, menu = myMenu,
                    )
                } else {
                    StoryRow(
                        thumbUrl = "", title = "Add Story", subtitle = uploadLine ?: "Disappears after 24 hours",
                        authorPhoto = profile?.photoUrl, seen = true, camera = true, addBadge = true,
                        onClick = { if (retryWaiting) retryNow() else addStory() }, onCamera = addStory, menu = myMenu,
                    )
                }
                pickError?.let { Text(it, color = c.red, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
            }
            if (recent.isNotEmpty()) {
                item { Heading("Recent Stories") }
                items(recent, key = { "r" + it.key }) { g -> FriendRow(g, now, friendMenu(g)) { openRing(g) } }
            }
            if (viewed.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { viewedCollapsed = !viewedCollapsed }
                            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Viewed Stories", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = headingInk())
                        Spacer(Modifier.width(6.dp))
                        // iOS chevron.up, turned over while folded.
                        val turn by androidx.compose.animation.core.animateFloatAsState(if (viewedCollapsed) 180f else 0f,
                            androidx.compose.animation.core.tween(250), label = "fold")
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = if (viewedCollapsed) "Show viewed stories" else "Hide viewed stories",
                            tint = c.secondary, modifier = Modifier.size(20.dp).rotate(turn),
                        )
                    }
                }
                if (!viewedCollapsed) items(viewed, key = { "v" + it.key }) { g -> FriendRow(g, now, friendMenu(g)) { openRing(g) } }
            }
            // iOS Hidden Stories: only while there is one, dimmed, folding like Viewed Stories.
            if (hidden.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { hiddenCollapsed = !hiddenCollapsed }
                            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Hidden Stories", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = headingInk())
                        Spacer(Modifier.width(6.dp))
                        // iOS chevron.up, turned over while folded.
                        val turn by androidx.compose.animation.core.animateFloatAsState(if (hiddenCollapsed) 180f else 0f,
                            androidx.compose.animation.core.tween(250), label = "fold")
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = if (hiddenCollapsed) "Show hidden stories" else "Hide hidden stories",
                            tint = c.secondary, modifier = Modifier.size(20.dp).rotate(turn),
                        )
                    }
                }
                if (!hiddenCollapsed) items(hidden, key = { "h" + it.key }) { g ->
                    Box(Modifier.alpha(0.45f)) { FriendRow(g, now, hiddenMenu(g)) { openHidden(g) } }
                }
            }
            // Glowing (iOS glowSection): under Friends, never mixed into it; hidden with Glow.
            if (glowOn && com.fariin.app.ui.glow.hasGlowGrid(glowState.relationship, glowCards)) {
                item(key = "glowHeading") { com.fariin.app.ui.glow.GlowingHeading { glowPage = true } }
                val cards = glowCards.valueOrNull
                if (cards == null) item(key = "glowLoading") {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        com.fariin.app.ui.components.FariinSpinner(size = 22.dp)
                    }
                } else items(cards.chunked(2), key = { "glow:" + it.first().person.id }) { pair ->
                    com.fariin.app.ui.glow.GlowCardRow(me, pair, glowSeen, glowOpener)
                }
            }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    viewing?.let { (groups, start) ->
        // Live copies of the rings, so a story posted or deleted meanwhile is not stale when opened.
        if (live) StoryViewerDialog(me, groups, start, conversations, onClose = { viewing = null; viewingOrigin = null }, origin = viewingOrigin)
        else viewing = null
    }
    if (camera) {
        // A capture goes through the same prepare and composer as a picked item.
        StoryCameraDialog(
            active = post.isEmpty() && !library, error = pickError,
            onCaptured = { uri ->
                scope.launch {
                    prepareStoryMedia(context, uri)
                        .onSuccess { openPost(null, it); pickError = null }
                        .onFailure { pickError = it.message ?: "Couldn't open this file." }
                }
            },
            onTextStory = { m -> composingText = true; openPost(null, m) },
            onLibrary = openLibrary, onClose = { camera = false },
        )
    }
    if (privacyPages.isNotEmpty()) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { privacyPages.removeAt(privacyPages.lastIndex) },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            com.fariin.app.ui.components.AppPageDialogWindow()
            Box(Modifier.fillMaxSize().background(c.bgSecondary)) {
                com.fariin.app.ui.settings.moreRoute(privacyPages.last(), push = { privacyPages.add(it) },
                    pop = { if (privacyPages.isNotEmpty()) privacyPages.removeAt(privacyPages.lastIndex) })
            }
        }
    }
    if (live) com.fariin.app.ui.glow.GlowViewerHost(me, glowOpener, conversations)
    if (glowPage) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { glowPage = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            com.fariin.app.ui.components.AppPageDialogWindow()
            com.fariin.app.ui.glow.GlowStoriesPage(me, conversations, glowSeen, onBack = { glowPage = false })
        }
    }
    if (glowNotifs) {
        val people = com.fariin.app.ui.people.LocalPeopleNav.current
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { glowNotifs = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            com.fariin.app.ui.components.AppPageDialogWindow()
            com.fariin.app.ui.glow.GlowNotificationsPage(me, state.mine?.stories ?: emptyList(), conversations,
                onOpenPerson = { uid -> glowNotifs = false; people.contact(uid) },
                onOpenMyStory = { state.mine?.let { g -> viewing = listOf(g) to 0 } },
                onBack = { glowNotifs = false })
        }
    }
    if (posted) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { posted = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            com.fariin.app.ui.components.AppPageDialogWindow()
            PostedStoriesPage(me, state.mine?.stories ?: emptyList(), profile?.photoUrl,
                onOpen = { s ->
                    // Start the viewer on the tapped story: a watermark just before it.
                    state.mine?.let { g ->
                        viewingOrigin = StoryOrigin(postedTileKey(s.id), s.listThumb)
                        viewing = listOf(g.copy(lastViewedAtMs = s.createdAtMs - 1)) to 0
                    }
                },
                onBack = { posted = false }, conversations = conversations, myHandle = profile?.handle)
        }
    }
    hideTarget?.let { g ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { hideTarget = null },
            title = { Text("Hide Stories?") },
            text = { Text("New story updates from ${g.name.ifEmpty { "this person" }} won't appear at the top of the stories list anymore.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    g.stories.firstOrNull()?.authorUid?.let { com.fariin.app.data.StoryMutes.setHidden(context, it, true) }
                    hideTarget = null
                }) { Text("Hide Stories", color = c.red) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { hideTarget = null }) { Text("Cancel") } },
        )
    }
    // A failed post says so (iOS "Couldn't post story"): Try Again while its ticket waits on disk,
    // which is also retried by itself when the connection comes back (MainShell).
    (upload as? StoryUploads.State.Failed)?.takeIf { live && active }?.let { f ->
        val waiting = remember(f) { com.fariin.app.data.StoryOutbox.hasWaiting(context, me) }
        val dismiss = { StoryUploads.state.value = null }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Couldn't post story") },
            text = { Text(if (waiting) f.message + "\n\nIt will be tried again automatically when you're back online." else f.message) },
            confirmButton = {
                if (waiting) androidx.compose.material3.TextButton(onClick = {
                    dismiss(); StoryUploads.retry(context, me, storyConvs(conversations))
                }) { Text("Try Again") }
                else androidx.compose.material3.TextButton(onClick = dismiss) { Text("OK") }
            },
            dismissButton = if (waiting) ({ androidx.compose.material3.TextButton(onClick = dismiss) { Text("OK") } }) else null,
        )
    }
    if (tooLong) androidx.compose.material3.AlertDialog(
        onDismissRequest = { tooLong = false },
        title = { Text(com.fariin.app.data.StoryLimits.TOO_LONG_TITLE) },
        text = { Text(com.fariin.app.data.StoryLimits.TOO_LONG_TEXT) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { tooLong = false }) { Text("OK") } },
    )
    if (dayLimit) androidx.compose.material3.AlertDialog(
        onDismissRequest = { dayLimit = false },
        title = { Text(com.fariin.app.data.StoryLimits.DAY_TITLE) },
        text = { Text(limits.dayAlertText()) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { dayLimit = false }) { Text("OK") } },
    )
    if (library) StoryLibrarySheet(busy = libraryBusy, onPick = pickFromLibrary, onClose = { library = false })
    libraryFlight?.let { f ->
        StoryLibraryFlightLayer(f, onLanded = { arriving = false }, onDone = { libraryFlight = null; arriving = false })
    }
    if (post.isNotEmpty()) {
        // One editor for the whole post; a new post is a fresh one.
        androidx.compose.runtime.key(postId) {
            StoryComposerDialog(me, post, conversations,
                // Closing the editor drops the whole post (iOS: one editor, one post).
                onClose = { post.clear(); composingText = false },
                onPosted = { post.clear(); composingText = false; camera = false; library = false },
                textStory = composingText,
                // Every item posts at once, so the one on the card flies home.
                flyHome = true, onFlightStart = { camera = false; library = false; StoryAppShrink.play() },
                onAddMore = if (composingText) null else ({ addMore = true }), hidden = arriving)
        }
    }
    // The editor's + (iOS StoryLibraryPicker allowsMultiple from the editor), over the editor.
    if (addMore) StoryLibrarySheet(busy = false, onPick = {}, onClose = { addMore = false },
        multi = StoryLibraryMulti(
            preselected = post.mapNotNull { it.uri },
            reserved = post.count { it.uri == null },
            onApply = { removed, added ->
                // Into the same editor's items (iOS applyPickerChange): untick takes one out, a new tick
                // goes on the end; the editor goes if nothing is left.
                val off = removed.toSet()
                post.removeAll { it.uri != null && it.uri in off }
                added.forEach { (u, m) -> post.add(StoryItemState(m, u)) }
                if (post.isEmpty()) composingText = false
            },
        ))
}

/** iOS ContentUnavailableView.search, in the words the app's other searches use. */
@Composable
private fun NoResults(query: String) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No Results", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
        Spacer(Modifier.height(6.dp))
        Text("There were no results for \"$query\".\nTry a new search.", fontSize = 15.sp, color = c.secondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun Heading(title: String) {
    Text(
        title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = headingInk(),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** iOS listHeadingColor: the text colour at 70 %, a soft white in dark and a soft black in light. */
@Composable
private fun headingInk(): Color = Fariin.colors.label.copy(alpha = 0.7f)

/** iOS StoryListRow badge: the person's tick at 15 after the name (Fariin's own story included). */
@Composable
private fun RowVerifiedMark(uid: String) {
    if (uid.isEmpty() || uid.startsWith(StoryGroup.GROUP_PREFIX)) return
    val ctx = LocalContext.current
    val official = uid == com.fariin.app.data.OfficialChannel.CID
    if (!official) LaunchedEffect(uid) { com.fariin.app.data.VerifiedPeople.warm(ctx.applicationContext, uid) }
    val kinds by com.fariin.app.data.VerifiedPeople.kinds.collectAsState()
    val kind = if (official) "" else com.fariin.app.data.VerifiedPeople.badge(kinds, uid) ?: return
    val tint = if (com.fariin.app.data.VerifiedPeople.isGold(kind)) Color(0xFFE8B21E) else Color(0xFF2392FE)
    Box(Modifier.padding(start = 4.dp).size(15.dp).background(Color.White, CircleShape).padding(1.dp)) {
        Icon(Icons.Filled.Verified, contentDescription = "Verified", tint = tint, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun FriendRow(g: StoryGroup, now: Long, menu: List<RowAction>, onClick: () -> Unit) {
    val newest = g.stories.lastOrNull()
    StoryRow(
        thumbUrl = newest?.listThumb ?: "", title = g.name.ifEmpty { "Fariin user" },
        subtitle = StoryList.subtitle(g, now),
        authorPhoto = g.photoUrl, seen = !g.hasUnseen, seenFlags = com.fariin.app.data.StoryRings.seenFlags(g),
        squircle = false, originKey = g.key, badgeUid = g.key, onClick = onClick, menu = menu,
    )
}

/** One entry of a row's long-press menu (iOS CMAction). */
private class RowAction(val title: String, val icon: Int, val destructive: Boolean = false, val run: () -> Unit)

private val UnseenRing = Brush.linearGradient(listOf(Color(0xFF34C76F), Color(0xFF3DA1FD)))

/** iOS StoryListRow: 66 by 94 picture, 18 to the text, 20 side margin, 12 above and below. */
@Composable
private fun StoryRow(
    thumbUrl: String,
    title: String,
    subtitle: String,
    authorPhoto: String?,
    seen: Boolean,
    /** iOS seenFlags: one arc per story, oldest first (StoryRing); empty = the single ring. */
    seenFlags: List<Boolean> = emptyList(),
    camera: Boolean = false,
    addBadge: Boolean = false,
    backThumbUrl: String? = null,
    squircle: Boolean = false,
    /** The ring this card opens: the viewer zooms out of it and shrinks back into it. */
    originKey: String? = null,
    /** A picture not uploaded yet (the story going up), drawn instead of [thumbUrl]. */
    thumbImage: androidx.compose.ui.graphics.ImageBitmap? = null,
    /** The story is uploading: the face's ring turns (iOS isUploading). */
    uploading: Boolean = false,
    /** iOS badgeUid: whose verified tick follows the name; null for none (My Story). */
    badgeUid: String? = null,
    onClick: () -> Unit = {},
    onCamera: () -> Unit = {},
    menu: List<RowAction> = emptyList(),
) {
    val c = Fariin.colors
    val shape = RoundedCornerShape(14.dp)
    var menuOpen by remember { mutableStateOf(false) }
    Box {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = if (menu.isEmpty()) null else ({ menuOpen = true }))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 66.dp, height = 94.dp)) {
            if (!backThumbUrl.isNullOrEmpty()) {
                // The story before the newest, tilted behind it, cut off by a band of page colour.
                if (backThumbUrl.startsWith("demo:")) Box(
                    Modifier.offset(x = (-9).dp, y = 4.5.dp).size(width = 48.dp, height = 72.dp)
                        .rotate(-10.4f).clip(RoundedCornerShape(7.dp)).background(demoColor(backThumbUrl)),
                ) else AsyncImage(
                    backThumbUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.offset(x = (-9).dp, y = 4.5.dp).size(width = 48.dp, height = 72.dp)
                        .rotate(-10.4f).clip(RoundedCornerShape(7.dp)).background(c.placeholderFill),
                )
                Box(
                    Modifier.offset(x = (-2).dp, y = (-2).dp).size(width = 70.dp, height = 98.dp)
                        .clip(RoundedCornerShape(16.dp)).background(c.bg),
                )
            }
            val view = androidx.compose.ui.platform.LocalView.current
            val radiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { 14.dp.toPx() }
            if (originKey != null) androidx.compose.runtime.DisposableEffect(originKey) {
                onDispose { StoryOrigins.rects.remove(originKey); StoryOrigins.radii.remove(originKey) }
            }
            Box(
                Modifier.size(width = 66.dp, height = 94.dp)
                    .then(if (originKey == null) Modifier else Modifier
                        .onGloballyPositioned { lc ->
                            // In screen pixels: the viewer is its own window.
                            val loc = IntArray(2)
                            view.getLocationOnScreen(loc)
                            val p = lc.positionInWindow()
                            StoryOrigins.rects[originKey] = androidx.compose.ui.geometry.Rect(
                                p.x + loc[0], p.y + loc[1], p.x + loc[0] + lc.size.width, p.y + loc[1] + lc.size.height)
                            StoryOrigins.radii[originKey] = radiusPx
                        }
                        // The slot stays empty while its story is out, so the landing fills it.
                        .graphicsLayer { alpha = if (StoryOrigins.hiddenKey == originKey) 0f else 1f })
                    .clip(shape).background(c.placeholderFill),
                contentAlignment = Alignment.Center,
            ) {
                // A faint rim over the picture (the same 7% line as the floating controls), so a white
                // story still reads as a card on the white page instead of a hole beside the back card.
                Box(Modifier.matchParentSize().zIndex(1f).border(0.5.dp, c.label.copy(alpha = 0.12f), shape))
                when {
                    thumbImage != null -> androidx.compose.foundation.Image(thumbImage, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.1f; scaleY = 1.1f })
                    // Debug demo data only: a plain colour instead of a picture.
                    thumbUrl.startsWith("demo:") -> Box(Modifier.fillMaxSize().background(demoColor(thumbUrl)))
                    thumbUrl.isNotEmpty() -> AsyncImage(thumbUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.1f; scaleY = 1.1f })
                    addBadge && !authorPhoto.isNullOrEmpty() -> AsyncImage(authorPhoto, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    addBadge -> Icon(Icons.Filled.Person, null, tint = c.secondary, modifier = Modifier.size(30.dp))
                    else -> Icon(Icons.Filled.Add, null, tint = c.secondary, modifier = Modifier.size(26.dp))
                }
            }
            if (addBadge) {
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 6.dp).size(28.dp).clip(CircleShape).background(c.bg),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
                    }
                }
            } else {
                // The face: 26 avatar, 1 gap, 1.2 ring (1.5 grey when seen); one arc per story
                // when the row knows them (iOS StoryListRow faceRing, StoryRingView).
                val ring = if (seen) 1.5.dp else 1.2.dp
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(x = 7.dp, y = 5.dp).size(30.4.dp).clip(CircleShape)
                        .background(c.bg)
                        .then(
                            if (seenFlags.isNotEmpty() || uploading) Modifier
                            else if (seen) Modifier.border(ring, if (c.dark) Color(0xFF505052) else Color(0xFFCACACA), CircleShape)
                            else Modifier.border(ring, UnseenRing, CircleShape),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Avatar(authorPhoto, 26.dp, squircle = squircle, kind = com.fariin.app.ui.components.AvatarKind.of(squircle, false))
                    if (uploading) UploadSpinRing(Modifier.fillMaxSize(), UnseenRing, 1.2.dp)
                    else if (seenFlags.isNotEmpty()) StoryRing(seenFlags, Modifier.fillMaxSize(), lineWidth = 1.2.dp)
                }
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (badgeUid != null) RowVerifiedMark(badgeUid)
            }
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 15.sp, color = c.secondary, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (camera) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(44.dp).floating().clickable { onCamera() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_story_camera), contentDescription = "Add Story", tint = c.label,
                    modifier = Modifier.size(22.dp))
            }
        }
    }
    androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
        offset = androidx.compose.ui.unit.DpOffset(96.dp, 0.dp)) {
        menu.forEach { a ->
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(a.title, color = if (a.destructive) c.red else c.label) },
                leadingIcon = { Icon(painterResource(a.icon), contentDescription = null, tint = if (a.destructive) c.red else c.label, modifier = Modifier.size(22.dp)) },
                onClick = { menuOpen = false; a.run() },
            )
        }
    }
    }
}


private fun demoColor(url: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(url.removePrefix("demo:"))) }.getOrDefault(Color.Gray)

/** The last New Story press (chat list button, launcher shortcut) the Stories tab acted on. */
private object AddRequests { var handled = 0 }
