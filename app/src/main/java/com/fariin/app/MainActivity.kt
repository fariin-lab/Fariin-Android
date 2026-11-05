package com.fariin.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.runtime.State
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Transition
import androidx.compose.animation.EnterExitState
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.OnBackPressedDispatcher
import coil3.request.allowRgb565
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil3.ImageLoader
import okio.Path.Companion.toOkioPath
import coil3.compose.setSingletonImageLoaderFactory
import com.fariin.app.data.AuthRepository
import com.fariin.app.data.CryptoDecryptor
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.Message
import com.fariin.app.ui.auth.SignInFlow
import com.fariin.app.ui.chats.ChatsUiState
import com.fariin.app.ui.chats.ChatsViewModel
import com.fariin.app.ui.debug.DemoChats
import com.fariin.app.ui.debug.DemoThread
import com.fariin.app.ui.photo.FariinPhotoFetcher
import com.fariin.app.ui.shell.MainShell
import com.fariin.app.ui.theme.FariinTheme
import com.fariin.app.ui.thread.LocalOpenChat
import com.fariin.app.ui.thread.ThreadRoute
import com.fariin.app.ui.thread.ThreadScreen
import com.fariin.app.ui.people.peopleRoutes
import com.fariin.app.ui.channels.channelRoutes
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.currentBackStackEntryAsState
import com.fariin.app.device.DeviceRecord
import com.fariin.app.device.DeviceRegistry
import com.fariin.app.device.SessionEnd
import com.fariin.app.device.SignedOutNotice
import com.fariin.app.push.NotificationPoster
import com.fariin.app.push.NotificationsExplainer
import com.fariin.app.push.NotifStore
import com.fariin.app.push.Push
import com.fariin.app.push.PushRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val auth by lazy { AuthRepository() }

    /** The launch screen stays until the first real frame: the chat list (saved or live) for a
     *  signed-in phone, the front door otherwise; never more than [SPLASH_MAX_MS]. */
    @Volatile private var keepSplash = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = with(androidx.core.splashscreen.SplashScreen) { installSplashScreen() }
        super.onCreate(savedInstanceState)
        com.fariin.app.data.LeaveUndo.onFailure = { com.fariin.app.ui.components.Snack.show(it, long = true) }
        splash.setKeepOnScreenCondition { keepSplash }
        window.decorView.postDelayed({ keepSplash = false }, SPLASH_MAX_MS)
        enableEdgeToEdge()
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // Debug builds only: `adb shell am start -n com.fariin.app/.MainActivity --ez demo true`
        // shows the shell with made-up chats, no sign-in and no Firebase. Add `--es thread d1`
        // (a 1:1) or `--es thread d3` (a group) to open straight into a made-up chat.
        val demo = debuggable && intent.getBooleanExtra("demo", false)
        val demoThread = if (demo) intent.getStringExtra("thread") else null
        DeviceRegistry.init(this)
        NotificationPoster.ensureChannels(this)
        takeOpenCid(intent)
        takeCallLink(intent)
        com.fariin.app.system.SystemRouter.take(intent)
        // Debug builds only: `--ez notifDemo true` posts made-up notifications through the real
        // builder (push/DebugNotifs), no sign-in and no Firebase.
        if (debuggable && intent.getBooleanExtra("notifDemo", false)) com.fariin.app.push.DebugNotifs.post(this)
        // Debug builds only: `--es notifChat <cid>` = a real notification for a real chat (local backend).
        if (debuggable) intent.getStringExtra("notifChat")?.let { com.fariin.app.push.DebugNotifs.postReal(this, it) }
        // Debug builds only: `--ez callDemo true --es callScreen incoming|calling|voice|video|ended|group`
        // shows a made-up call screen (ui/call/CallActivity), no Firebase and no WebRTC.
        if (debuggable && intent.getBooleanExtra("callDemo", false)) {
            com.fariin.app.ui.call.CallActivity.startDemo(this, intent.getStringExtra("callScreen") ?: "voice")
        }
        setContent {
            FariinTheme {
              androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                // Debug builds only: `--es authPage <name>` draws one sign-in page with made-up state (ui/auth/AuthDemo.kt).
                if (debuggable && intent.getStringExtra("authPage") != null) {
                    LaunchedEffect(Unit) { keepSplash = false }
                    com.fariin.app.ui.auth.AuthDemo(intent.getStringExtra("authPage")!!)
                } else if (demo) {
                    LaunchedEffect(Unit) { keepSplash = false }
                    DemoApp(demoThread)
                } else {
                    val initial = remember { auth.currentUser?.uid }
                    val uid by auth.uid.collectAsState(initial = initial)
                    val me = uid
                    // Sign-out: the keys leave memory (the stored key stays, keys are per uid).
                    LaunchedEffect(me) {
                        if (me == null) {
                            CryptoHub.signedOut(); com.fariin.app.call.IncomingCallWatcher.stop()
                            com.fariin.app.data.HiddenMessages.clear(applicationContext)
                            com.fariin.app.data.HiddenCalls.clear(applicationContext)
                            com.fariin.app.data.DeliveryReceipts.reset()
                        }
                    }
                    // The server removed this phone's device record: the same teardown as Log out,
                    // then the front door with the iOS notice saying why.
                    val revoked by DeviceRegistry.revoked.collectAsState()
                    var notice by remember { mutableStateOf<DeviceRecord.Reason?>(null) }
                    LaunchedEffect(revoked) {
                        val r = revoked ?: return@LaunchedEffect
                        SessionEnd.revokedSignOut(applicationContext)
                        DeviceRegistry.clearRevoked()
                        notice = r
                    }
                    if (me == null) { LaunchedEffect(Unit) { keepSplash = false }; SignInFlow(auth) } else com.fariin.app.ui.auth.AccountGate(me, auth, onOwnPage = { keepSplash = false }) { SignedIn(me) }
                    notice?.let { SignedOutNotice(it) { notice = null } }
                    // A message for another chat while the app is open (push/InAppNotify).
                    if (me != null) com.fariin.app.ui.components.InAppBannerHost() else LaunchedEffect(Unit) { com.fariin.app.push.InAppNotify.reset() }
                    // A required or suggested update from the server covers everything (iOS AppUpdateGate).
                    com.fariin.app.system.AppUpdateLayer()
                    // Log Out under way (iOS BusyOverlay "Signing Out…"): the app says so and takes no taps.
                    val signingOut by com.fariin.app.device.SessionEnd.signingOut.collectAsState()
                    if (signingOut) androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f))
                        .clickable(enabled = true, onClick = {}), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier
                            .background(com.fariin.app.ui.theme.Fariin.colors.card, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                            .padding(horizontal = 28.dp, vertical = 22.dp),
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            androidx.compose.material3.CircularProgressIndicator(color = com.fariin.app.ui.theme.Fariin.colors.accent)
                            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(14.dp))
                            androidx.compose.material3.Text("Logging out…", color = com.fariin.app.ui.theme.Fariin.colors.label)
                        }
                    }
                    if (me != null) {
                        com.fariin.app.system.LowStorageSheet()
                        LaunchedEffect(Unit) { kotlinx.coroutines.delay(2_000); com.fariin.app.system.LowStorage.checkAndWarn(applicationContext) }
                    }
                }
                // One snackbar for the whole app (Android toasts and banners).
                com.fariin.app.ui.components.SnackHost()
              }
            }
        }
    }

    @Composable
    private fun SignedIn(me: String) {
        val crypto = remember { CryptoHub.get(applicationContext) }
        // Key bootstrap as iOS RootView does it: load (or, only when this phone has none, make and
        // store) the keypair, then publish it if the server copy differs. A key store that cannot be
        // read is never taken as "no key", so a readable key is never replaced.
        // Channels I follow (their own list: followers are not in a channel's `users`).
        androidx.compose.runtime.DisposableEffect(me) {
            com.fariin.app.channel.ChannelFollows.start(me)
            onDispose { com.fariin.app.channel.ChannelFollows.stop() }
        }
        // Feature switches (config/features) and the glow relationship (iOS RootView).
        androidx.compose.runtime.DisposableEffect(me) {
            com.fariin.app.data.AppFeatures.start()
            com.fariin.app.data.Glow.start(applicationContext, me)
            onDispose { com.fariin.app.data.Glow.stop(); com.fariin.app.data.AppFeatures.stop() }
        }
        // The official Fariin chat (announcements and my read state).
        androidx.compose.runtime.DisposableEffect(me) {
            val build = try {
                val p = packageManager.getPackageInfo(packageName, 0)
                if (android.os.Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else @Suppress("DEPRECATION") p.versionCode
            } catch (_: Exception) { 0 }
            com.fariin.app.data.OfficialChannel.start(applicationContext, me, build)
            onDispose { com.fariin.app.data.OfficialChannel.stop() }
        }
        // Favorites, the stickers I made and saved (iOS StickerStore + StickerBackup).
        LaunchedEffect(me) { com.fariin.app.data.StickerFavorites.start(applicationContext, me) }
        LaunchedEffect(me) {
            try { crypto.ensureReady() } catch (_: Exception) {}
            crypto.publishPublicKey()
            CryptoHub.bumpKeys()
            // iOS RootView order: push token, then this phone's device record (and its watcher).
            Push.register(applicationContext)
            DeviceRegistry.start(applicationContext)   // its first beat starts the heartbeat
            // Calls ringing me while the app is alive (iOS observeIncoming), and a warm relay list.
            com.fariin.app.call.IncomingCallWatcher.start(applicationContext, me)
            // Presence "online" and the texts still owed from before (send queue).
            AppLifecycle.signedIn()
        }
        // The first-run notifications page, right after sign-in, as iOS asks.
        val ctx = LocalContext.current
        var explain by remember { mutableStateOf(Push.needsExplainer(ctx)) }
        if (explain) { LaunchedEffect(Unit) { keepSplash = false }; NotificationsExplainer { explain = false }; return }
        // Android 14+: a call can only take over the screen (lock screen, app closed) when the person
        // has allowed "full screen notifications" for Fariin; outside the Play Store it starts off,
        // and a call then rang with nothing to answer (owner's Pixel 2026-11-05). Asked once, the way
        // calling apps do; "Not now" is remembered.
        var askFullScreen by remember { mutableStateOf(com.fariin.app.call.CallScreenAccess.shouldAsk(ctx)) }
        if (askFullScreen) androidx.compose.material3.AlertDialog(
            onDismissRequest = { com.fariin.app.call.CallScreenAccess.later(ctx); askFullScreen = false },
            title = { androidx.compose.material3.Text("Show calls on your screen") },
            text = { androidx.compose.material3.Text("Allow Fariin to show incoming calls full screen, so you can answer when your phone is locked or Fariin is closed.") },
            confirmButton = { androidx.compose.material3.TextButton({ askFullScreen = false; com.fariin.app.call.CallScreenAccess.openSettings(ctx) }) { androidx.compose.material3.Text("Allow") } },
            dismissButton = { androidx.compose.material3.TextButton({ com.fariin.app.call.CallScreenAccess.later(ctx); askFullScreen = false }) { androidx.compose.material3.Text("Not Now") } },
        )
        val decryptor = remember(crypto) { CryptoDecryptor(crypto) }
        val vm: ChatsViewModel = viewModel(key = "chats-$me") { ChatsViewModel(me, crypto = crypto) }
        val chats by vm.state.collectAsState()
        val activity = LocalContext.current as? android.app.Activity
        LaunchedEffect(chats.loading) {
            if (!chats.loading) {
                keepSplash = false
                // "Fully drawn" in the system log = the chat list is up (the start-up number we track).
                activity?.reportFullyDrawn()
            }
        }
        val nav = rememberNavController()
        // Notifications: the chat list's names and photos for the push service, the open chat
        // (no notification for it), and a tapped notification opening its chat.
        LaunchedEffect(chats.chats) {
            val list = chats.chats
            if (list.isNotEmpty()) withContext(Dispatchers.Default) { NotifStore.saveChats(applicationContext, me, list) }
            // Recent chats as conversation shortcuts: share sheet, launcher, notification settings.
            if (list.isNotEmpty()) com.fariin.app.system.ChatShortcuts.publish(applicationContext, me, list)
            // The chats about to be opened, warmed (iOS ChatHistoryPreloader).
            com.fariin.app.data.ChatHistoryPreloader.refresh(list, me, openChat)
        }
        DisposableEffect(me) { onDispose { com.fariin.app.data.ChatHistoryPreloader.stopAll() } }
        // The home screen widget: the same list, its previews opened again once a missing key lands.
        // Collected here, not keyed on the drawn state: the list keeps changing while the app is
        // behind the home screen, where nothing is drawn.
        LaunchedEffect(vm, decryptor) {
            vm.state.combine(com.fariin.app.data.CryptoHub.keysEpoch) { s, _ -> s }.collect { s ->
                if (!s.loading) com.fariin.app.system.FariinWidget.publish(applicationContext, me, s.chats, decryptor)
            }
        }
        val entry by nav.currentBackStackEntryAsState()
        val openCid = if (entry?.destination?.route == "thread/{cid}") entry?.arguments?.getString("cid") else null
        LaunchedEffect(openCid) {
            openChat = openCid
            PushRouter.activeChatId = if (started) openCid else null
            openCid?.let { NotificationPoster.clear(applicationContext, it) }
        }
        val pending by PushRouter.pendingOpen.collectAsState()
        LaunchedEffect(pending) {
            val cid = pending ?: return@LaunchedEffect
            PushRouter.pendingOpen.value = null
            nav.navigate("thread/$cid") { launchSingleTop = true }
        }
        // The launcher's long-press New Chat / New Story (system/SystemRouter).
        val launcherAsk by com.fariin.app.system.SystemRouter.pending.collectAsState()
        LaunchedEffect(launcherAsk) {
            val ask = launcherAsk ?: return@LaunchedEffect
            com.fariin.app.system.SystemRouter.pending.value = null
            nav.popBackStack("main", inclusive = false)
            if (ask == com.fariin.app.system.SystemRouter.NEW_CHAT) nav.navigate("newchat") { launchSingleTop = true }
            else if (ask == com.fariin.app.system.SystemRouter.OPEN_STORIES) com.fariin.app.system.SystemRouter.storiesTabRequests.value += 1
            else com.fariin.app.system.SystemRouter.storyRequests.value += 1
        }
        val linkAsk by com.fariin.app.call.CallLinkRouter.pending.collectAsState()
        LaunchedEffect(linkAsk) {
            val (key, video) = linkAsk ?: return@LaunchedEffect
            com.fariin.app.call.CallLinkRouter.pending.value = null
            nav.navigate("joinlink/$key/${if (video) "video" else "voice"}") { launchSingleTop = true }
        }
        val people = remember(me) { com.fariin.app.data.FirestorePeople(me, crypto) }
        CompositionLocalProvider(LocalOpenChat provides { conv -> nav.navigate("thread/${conv.id}") { launchSingleTop = true } },
            com.fariin.app.ui.people.LocalPeopleNav provides com.fariin.app.ui.people.peopleNav(nav, people),
            com.fariin.app.ui.channels.LocalChannelNav provides com.fariin.app.ui.channels.channelNav(nav)) {
            // A profile, invite or story link opened from outside the app (App Links).
            com.fariin.app.ui.thread.ExternalLinkHost(me)
            // The main shell is built once and kept under every page, the way large messengers keep
            // their list alive, so coming back to it costs no rebuild (that was ~50 ms of main-thread
            // work, two dropped frames, on the Pixel 5). The "main" page in the host is see-through;
            // its own transition (predictive back included) drives the shell's slide and fade, and
            // while another page is up the shell takes no touches, back presses or accessibility focus.
            val shell = remember { ShellMotion() }
            val top by nav.currentBackStackEntryAsState()
            val onTop = top?.destination?.route.let { it == null || it == "main" }
            val inertBack = remember { object : OnBackPressedDispatcherOwner {
                override val onBackPressedDispatcher = OnBackPressedDispatcher()
                override val lifecycle get() = this@MainActivity.lifecycle
            } }
            Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = if (shell.shown) shell.alpha else 0f
                translationX = shell.shift * size.width
            }.then(if (onTop) Modifier else Modifier.clearAndSetSemantics { })) {
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides if (onTop) this@MainActivity else inertBack) {
                    MainShell(me, chats, decryptor, onSignOut = { lifecycleScope.launch { SessionEnd.signOut(applicationContext) } })
                }
                if (!onTop) Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                })
            }
            NavHost(nav, startDestination = "main",
                enterTransition = com.fariin.app.ui.components.Motion.enter(), exitTransition = com.fariin.app.ui.components.Motion.exit(),
                popEnterTransition = com.fariin.app.ui.components.Motion.popEnter(), popExitTransition = com.fariin.app.ui.components.Motion.popExit()) {
                peopleRoutes(nav, people, chats = { chats.chats }, requests = { chats.requests },
                    groupDoc = { com.fariin.app.ui.people.liveGroupDoc(it) })
                channelRoutes(nav, me, chats = { chats.chats })
                // A real (see-through) box, never an empty page: with nothing to measure, the page
                // change back to it could freeze half way (owner 2026-11-03, the stuck back arrow).
                composable("main") { Box(Modifier.fillMaxSize()); shell.Follow(transition) }
                composable("thread/{cid}") { entry ->
                    val cid = entry.arguments?.getString("cid") ?: return@composable
                    ThreadRoute(cid, me, crypto, onBack = { nav.popBackStack() })
                }
            }
            }
        }
    }

    private companion object { const val SPLASH_MAX_MS = 1500L }

    @Volatile private var started = false

    @Volatile private var openChat: String? = null

    override fun onStart() {
        super.onStart()
        started = true
        com.fariin.app.system.AppUpdateGate.onForeground(this)
        com.fariin.app.system.LowStorage.checkAndWarn(applicationContext)
        PushRouter.activeChatId = openChat
        DeviceRegistry.startHeartbeat()
    }

    override fun onStop() {
        started = false
        PushRouter.activeChatId = null   // a chat open behind the home screen still gets its notification
        DeviceRegistry.stopHeartbeat()
        // A leave still waiting for its Undo runs now, so leaving the app cannot lose it (iOS LeaveGroupUndo).
        com.fariin.app.data.LeaveUndo.commit()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeOpenCid(intent)
        takeCallLink(intent)
        com.fariin.app.system.SystemRouter.take(intent)
        // Debug builds only: `--es notifChat <cid>` to the running app = that push arriving in front (the in-app banner).
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) intent.getStringExtra("notifChat")?.let { com.fariin.app.push.DebugNotifs.postReal(this, it) }
    }

    /** A tapped call link (https://call.fariin.com/video/..., fariin.com/call/#key=...). */
    private fun takeCallLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        if (com.fariin.app.call.CallLinkRouter.take(intent.dataString)) intent.data = null
        else if (com.fariin.app.ui.thread.ExternalLinks.take(intent.dataString)) intent.data = null
        else if (intent.data != null) android.widget.Toast.makeText(this, "This call link is no longer valid", android.widget.Toast.LENGTH_LONG).show()
    }

    /** A notification tap (or its conversation shortcut) carries the chat to open. */
    private fun takeOpenCid(intent: Intent?) {
        val cid = intent?.getStringExtra(PushRouter.EXTRA_OPEN_CID)?.takeIf { it.isNotEmpty() } ?: return
        intent.removeExtra(PushRouter.EXTRA_OPEN_CID)
        PushRouter.pendingOpen.value = cid
    }

    /** The made-up shell, with its chats opening made-up threads. */
    @Composable
    private fun DemoApp(startThread: String?) {
        val nav = rememberNavController()
        val demoPeople = com.fariin.app.ui.debug.DemoPeople
        CompositionLocalProvider(LocalOpenChat provides { conv -> nav.navigate("thread/${conv.id}") { launchSingleTop = true } },
            com.fariin.app.ui.people.LocalPeopleNav provides com.fariin.app.ui.people.peopleNav(nav)) {
            NavHost(nav, startDestination = startThread?.let { if (it.startsWith("p:")) it.removePrefix("p:") else "thread/$it" } ?: "main",
                enterTransition = com.fariin.app.ui.components.Motion.enter(), exitTransition = com.fariin.app.ui.components.Motion.exit(),
                popEnterTransition = com.fariin.app.ui.components.Motion.popEnter(), popExitTransition = com.fariin.app.ui.components.Motion.popExit()) {
                peopleRoutes(nav, demoPeople, chats = { DemoChats.chats() }, requests = { demoPeople.requests() },
                    groupDoc = { demoPeople.groupDoc(it) })
                composable("main") {
                    MainShell(DemoChats.ME, ChatsUiState(loading = false, chats = DemoChats.chats(), requests = demoPeople.requests()),
                        DemoChats.decryptor, onSignOut = { finish() })
                }
                composable("thread/{cid}") { entry ->
                    val cid = entry.arguments?.getString("cid") ?: return@composable
                    var ui by remember(cid) { mutableStateOf(DemoThread.ui(cid)?.let(demoPeople::decorate)) }
                    val u = ui ?: return@composable
                    val acts = remember(cid) { demoPeople.threadActions({ ui!! }, { ui = it }) {
                        val conv = DemoChats.chats().firstOrNull { it.id == cid }
                        if (conv?.isGroup == true) nav.navigate("group/$cid") else conv?.let { nav.navigate("contact/${it.otherUid(DemoChats.ME)}") }
                    } }
                    ThreadScreen(u, actions = acts, onBack = { if (!nav.popBackStack()) nav.navigate("main") }, onSend = { t ->
                        val now = System.currentTimeMillis().toDouble()
                        ui = u.copy(messages = u.messages + Message(
                            id = "local-$now", authorId = DemoChats.ME, text = t.trim(), createdAtMillis = now, clientTs = now,
                        ))
                    })
                }
            }
        }
    }
}

/**
 * The kept-alive shell's motion, read from the see-through "main" page's own enter/exit transition
 * so it matches Motion's shared X axis (a short slide and a fade) and follows a predictive back.
 */
private class ShellMotion {
    var shown by mutableStateOf(true)
    private var t by mutableStateOf<Transition<EnterExitState>?>(null)

    // READ ONLY: the shell's slide and fade are worked out from how far the "main" page's own
    // transition has played. Nothing is added to that transition (it used to carry two
    // animateFloat children), so the nav host's page change always finishes on its own: a page
    // change frozen half way left a chat on screen over a back stack already at "main", where the
    // arrow did nothing and the back gesture left the app (owner 2026-11-03).
    private fun progress(): Pair<Boolean, Float>? {
        val tr = t ?: return null
        val entering = tr.targetState == EnterExitState.Visible
        if (tr.currentState == tr.targetState) return entering to 1f
        val total = tr.totalDurationNanos
        val p = if (total <= 0L) 1f else (tr.playTimeNanos.toFloat() / total).coerceIn(0f, 1f)
        return entering to p
    }

    /** The list under a page stands still while the page on top moves (Motion: the reference app's rule). */
    val alpha: Float get() = 1f
    val shift: Float get() = 0f

    @Composable
    fun Follow(tr: Transition<EnterExitState>) {
        DisposableEffect(tr) {
            t = tr; shown = true
            onDispose { if (t === tr) { shown = false; t = null } }
        }
    }
}
