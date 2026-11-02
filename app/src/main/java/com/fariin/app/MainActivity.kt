package com.fariin.app

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val auth by lazy { AuthRepository() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        // Debug builds only: `--ez notifDemo true` posts made-up notifications through the real
        // builder (push/DebugNotifs), no sign-in and no Firebase.
        if (debuggable && intent.getBooleanExtra("notifDemo", false)) com.fariin.app.push.DebugNotifs.post(this)
        setContent {
            setSingletonImageLoaderFactory { ctx ->
                ImageLoader.Builder(ctx)
                    .components { add(FariinPhotoFetcher.Factory()) }
                    .build()
            }
            FariinTheme {
                if (demo) {
                    DemoApp(demoThread)
                } else {
                    val initial = remember { auth.currentUser?.uid }
                    val uid by auth.uid.collectAsState(initial = initial)
                    val me = uid
                    // Sign-out: the keys leave memory (the stored key stays, keys are per uid).
                    LaunchedEffect(me) {
                        if (me == null) {
                            CryptoHub.signedOut()
                            com.fariin.app.data.HiddenMessages.clear(applicationContext)
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
                    if (me == null) SignInFlow(auth) else SignedIn(me)
                    notice?.let { SignedOutNotice(it) { notice = null } }
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
        LaunchedEffect(me) {
            try { crypto.ensureReady() } catch (_: Exception) {}
            crypto.publishPublicKey()
            CryptoHub.bumpKeys()
            // iOS RootView order: push token, then this phone's device record (and its watcher).
            Push.register(applicationContext)
            DeviceRegistry.start(applicationContext)   // its first beat starts the heartbeat
        }
        // The first-run notifications page, right after sign-in, as iOS asks.
        val ctx = LocalContext.current
        var explain by remember { mutableStateOf(Push.needsExplainer(ctx)) }
        if (explain) { NotificationsExplainer { explain = false }; return }
        val decryptor = remember(crypto) { CryptoDecryptor(crypto) }
        val vm: ChatsViewModel = viewModel(key = "chats-$me") { ChatsViewModel(me, crypto = crypto) }
        val chats by vm.state.collectAsState()
        val nav = rememberNavController()
        // Notifications: the chat list's names and photos for the push service, the open chat
        // (no notification for it), and a tapped notification opening its chat.
        LaunchedEffect(chats.chats) {
            val list = chats.chats
            if (list.isNotEmpty()) withContext(Dispatchers.Default) { NotifStore.saveChats(applicationContext, me, list) }
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
        val people = remember(me) { com.fariin.app.data.FirestorePeople(me, crypto) }
        CompositionLocalProvider(LocalOpenChat provides { conv -> nav.navigate("thread/${conv.id}") { launchSingleTop = true } },
            com.fariin.app.ui.people.LocalPeopleNav provides com.fariin.app.ui.people.peopleNav(nav)) {
            NavHost(nav, startDestination = "main") {
                peopleRoutes(nav, people, chats = { chats.chats }, requests = { chats.requests },
                    groupDoc = { com.fariin.app.ui.people.liveGroupDoc(it) })
                composable("main") {
                    MainShell(me, chats, decryptor, onSignOut = { lifecycleScope.launch { SessionEnd.signOut(applicationContext) } })
                }
                composable("thread/{cid}") { entry ->
                    val cid = entry.arguments?.getString("cid") ?: return@composable
                    ThreadRoute(cid, me, crypto, onBack = { nav.popBackStack() })
                }
            }
        }
    }

    @Volatile private var started = false
    @Volatile private var openChat: String? = null

    override fun onStart() {
        super.onStart()
        started = true
        PushRouter.activeChatId = openChat
        DeviceRegistry.startHeartbeat()
    }

    override fun onStop() {
        started = false
        PushRouter.activeChatId = null   // a chat open behind the home screen still gets its notification
        DeviceRegistry.stopHeartbeat()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeOpenCid(intent)
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
            NavHost(nav, startDestination = startThread?.let { if (it.startsWith("p:")) it.removePrefix("p:") else "thread/$it" } ?: "main") {
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
