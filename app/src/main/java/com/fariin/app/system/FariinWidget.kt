package com.fariin.app.system

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.fariin.app.MainActivity
import com.fariin.app.R
import com.fariin.app.data.ChatList
import com.fariin.app.data.Conversation
import com.fariin.app.data.MessageDecryptor
import com.fariin.app.local.AtRest
import com.fariin.app.push.FariinMessagingService
import com.fariin.app.push.NotifStore
import com.fariin.app.push.NotificationPoster
import com.fariin.app.push.PushRouter
import com.fariin.app.security.AppLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The home screen widget "Recent" (Kulan-iOS FariinWidget ChatsWidget, with Android's own header):
 * "Fariin" and the number of unread chats, a New Chat button, and the newest chats (4 on the large
 * size, 2 on the small one) with face, name, a short line and the unread count.
 *
 * Fed only by what the app already has: the live chat list (MainActivity's chat list hook, the
 * same place ChatShortcuts is published) and a chat push that arrives while the list is not live.
 * The words are opened by the chat list's own decryptor on a background thread, never by the
 * widget's drawing. Kept on disk sealed with the Keystore key ([AtRest]) so a reboot still draws
 * it; with App Lock on nothing but the count is written. No polling: the widget redraws only when
 * the app has something new (updatePeriodMillis 0).
 */
object FariinWidget {
    private const val FILE = "widget-v1.bin"
    private const val PREFS_PUSH = "fariin_push"
    private const val PREVIEW_KEY = "notif.preview"
    private const val PAUSE_MS = 1_500L
    private const val FACE_PX = 96

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null
    @Volatile private var current: HomeWidgetRules.Snapshot? = null
    @Volatile private var lastShown: String? = null
    /** The last live list, to redraw at once when App Lock or Message Preview is switched. */
    @Volatile private var lastInput: Triple<String, List<Conversation>, MessageDecryptor>? = null
    private val faces = LruCache<String, Bitmap>(12)
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    fun showPreview(c: Context): Boolean =
        c.applicationContext.getSharedPreferences(PREFS_PUSH, Context.MODE_PRIVATE).getBoolean(PREVIEW_KEY, true)

    /** App start: follow the two switches the widget obeys (Settings writes them, we only listen). */
    fun init(c: Context) {
        val app = c.applicationContext
        if (listeners.isNotEmpty()) return
        val onPush = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == PREVIEW_KEY) switched(app) }
        val onLock = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == AppLock.ENABLED) switched(app) }
        listeners += onPush; listeners += onLock   // held here: the prefs keep only weak references
        app.getSharedPreferences(PREFS_PUSH, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(onPush)
        app.getSharedPreferences("fariin_security", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(onLock)
        // Dark mode switched: the cards follow on their own, but text colours are fixed when the
        // widget is drawn (before Android 12), so draw it again.
        var night = app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        app.registerComponentCallbacks(object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(cfg: android.content.res.Configuration) {
                val now = cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                if (now == night) return
                night = now
                scope.launch { if (placed(app)) redraw(app, load(app), force = true) }
            }
            override fun onLowMemory() { faces.evictAll() }
        })
    }

    fun placedNow(c: Context) {
        val input = lastInput ?: return
        lastShown = null
        publish(c.applicationContext, input.first, input.second, input.third, pause = 0)
    }

    private fun switched(app: Context) {
        val input = lastInput
        if (input != null) { publish(app, input.first, input.second, input.third, pause = 0); return }
        // No live list in this run: hide on disk what the switch now hides, then redraw.
        scope.launch {
            val s = HomeWidgetRules.masked(load(app), AppLock.enabled(app), showPreview(app))
            save(app, s); redraw(app, s, force = true)
        }
    }

    private suspend fun placed(app: Context): Boolean = try {
        GlanceAppWidgetManager(app).getGlanceIds(HomeWidget::class.java).isNotEmpty()
    } catch (_: Exception) { false }

    /** The chat list changed (MainActivity). A short pause first: the list moves in small steps. */
    fun publish(c: Context, me: String, convs: List<Conversation>, decryptor: MessageDecryptor, pause: Long = PAUSE_MS) {
        val app = c.applicationContext
        lastInput = Triple(me, convs, decryptor)
        pending?.cancel()
        pending = scope.launch {
            delay(pause)
            if (!placed(app)) return@launch   // no widget on the home screen: no work at all
            val now = System.currentTimeMillis()
            val s = withContext(Dispatchers.Default) {
                HomeWidgetRules.snapshot(convs, me, AppLock.enabled(app), showPreview(app), now) { conv ->
                    ChatList.previewLine(conv, me, decryptor) { null }
                }
            }
            current = s
            if (HomeWidgetRules.signature(s) == lastShown) return@launch
            // Faces first, so the redraw has them (saved for the notifications too).
            for (r in s.rows) r.photo?.let { url -> runCatching { FariinMessagingService.loadAvatar(app, url) } }
            save(app, s)
            redraw(app, s)
        }
    }

    /** A chat push was shown (FariinMessagingService). [line] = the words the notification showed, or null. */
    fun notePush(c: Context, cid: String, line: String?) {
        val app = c.applicationContext
        scope.launch {
            if (!placed(app)) return@launch
            val s = HomeWidgetRules.bump(load(app), cid, line, showPreview(app) && !AppLock.enabled(app), System.currentTimeMillis())
                ?: return@launch
            current = s
            save(app, s)
            redraw(app, s)
        }
    }

    /** Sign-out: nothing of this account stays on the home screen. */
    fun clear(c: Context) {
        val app = c.applicationContext
        pending?.cancel()
        lastInput = null
        current = HomeWidgetRules.Snapshot()
        faces.evictAll()
        runCatching { File(app.noBackupFilesDir, FILE).delete() }
        scope.launch { redraw(app, HomeWidgetRules.Snapshot(), force = true) }
    }

    private suspend fun redraw(app: Context, s: HomeWidgetRules.Snapshot, force: Boolean = false) {
        val sig = HomeWidgetRules.signature(s)
        if (!force && sig == lastShown) return
        lastShown = sig
        shown.value = render(app)
        runCatching { HomeWidget().updateAll(app) }
    }

    internal fun load(app: Context): HomeWidgetRules.Snapshot {
        current?.let { return it }
        val f = File(app.noBackupFilesDir, FILE)
        val s = try {
            if (!f.exists()) HomeWidgetRules.Snapshot()
            else AtRest.open(f.readBytes())?.toString(Charsets.UTF_8)?.let(HomeWidgetRules::decode) ?: HomeWidgetRules.Snapshot()
        } catch (_: Exception) { HomeWidgetRules.Snapshot() }
        current = s
        return s
    }

    private fun save(app: Context, s: HomeWidgetRules.Snapshot) {
        try {
            val sealed = AtRest.seal(HomeWidgetRules.encode(s).toByteArray(Charsets.UTF_8)) ?: return
            val f = File(app.noBackupFilesDir, FILE)
            val tmp = File(app.noBackupFilesDir, "$FILE.tmp")
            tmp.writeBytes(sealed)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Exception) {}
    }

    /** What the widget draws: the kept snapshot as the switches allow it now, with its faces. */
    internal class Shown(val snap: HomeWidgetRules.Snapshot, val faces: List<Bitmap>)
    internal val shown = MutableStateFlow<Shown?>(null)

    internal fun render(app: Context): Shown {
        val kept = load(app)
        // Signed in but nothing kept yet (placed before the app opened its list): ask to open it.
        val known = if (!kept.signedIn && com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null)
            HomeWidgetRules.Snapshot(signedIn = true, locked = true) else kept
        val s = HomeWidgetRules.masked(known, AppLock.enabled(app), showPreview(app))
        return Shown(s, s.rows.map { face(app, it.photo, it.group) })
    }

    /** A small round face (96 px), from the copy the notifications keep; cached in memory. */
    internal fun face(app: Context, url: String?, group: Boolean): Bitmap {
        val key = "${url ?: ""}#$group"
        faces.get(key)?.let { return it }
        val bytes = url?.let { NotifStore.cachedAvatar(app, it) }
        val big = NotificationPoster.circle(bytes, group)
        val small = Bitmap.createScaledBitmap(big, FACE_PX, FACE_PX, true)
        if (small !== big) big.recycle()
        // A face that is only the grey placeholder is not kept, so a photo saved later shows.
        if (bytes != null) faces.put(key, small)
        return small
    }

    // ------------------------------------------------------------------ taps

    /** Opens a chat: the same path as a notification or a chat shortcut (PushRouter.EXTRA_OPEN_CID).
     *  Each chat's intent has its own action, so Android keeps one pending intent per row. */
    internal fun openChat(c: Context, cid: String): Intent = Intent(c, MainActivity::class.java)
        .setAction("com.fariin.app.widget.CHAT.$cid")
        .putExtra(PushRouter.EXTRA_OPEN_CID, cid)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** New Chat, the launcher shortcut's route (SystemRouter). */
    internal fun newChat(c: Context): Intent = Intent(c, MainActivity::class.java)
        .setAction("com.fariin.app.widget.NEW_CHAT")
        .putExtra(SystemRouter.EXTRA_ACTION, SystemRouter.NEW_CHAT)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    internal fun openApp(c: Context): Intent = Intent(c, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

class HomeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HomeWidget()

    /** Just placed on the home screen: fill it from the live list when the app has one. */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        FariinWidget.placedNow(context)
    }
}

/** The drawing. Two sizes only, so a cheap phone renders at most two layouts. */
class HomeWidget : GlanceAppWidget() {
    // The real size (redrawn only when the widget is resized), so the rows fill the height it has.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext
        if (FariinWidget.shown.value == null) FariinWidget.shown.value = withContext(Dispatchers.IO) { FariinWidget.render(app) }
        // A running widget session is redrawn from this state (updateAll does not call provideGlance again).
        provideContent {
            val v by FariinWidget.shown.collectAsState()
            v?.let { Content(it.snap, it.faces) }
        }
    }
}

private val label = ColorProvider(R.color.widget_label)
private val secondary = ColorProvider(R.color.widget_secondary)

@Composable
private fun Content(s: HomeWidgetRules.Snapshot, faces: List<Bitmap>) {
    val c = LocalContext.current
    val h = LocalSize.current.height.value
    val compact = HomeWidgetRules.compact(h)
    val rows = HomeWidgetRules.rowsFor(h)
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground().background(ImageProvider(R.drawable.widget_bg))
            .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp),
    ) {
        Header(s, compact, open = actionStartActivity(FariinWidget.openApp(c)), newChat = actionStartActivity(FariinWidget.newChat(c)))
        Spacer(GlanceModifier.height(if (compact) 2.dp else 4.dp))
        val note = when {
            !s.signedIn -> "Sign in to Fariin"
            s.locked -> "Open Fariin to see this"
            s.rows.isEmpty() -> "Your chats show here"
            else -> null
        }
        if (note != null) {
            Box(GlanceModifier.fillMaxSize().clickable(actionStartActivity(FariinWidget.openApp(c))), contentAlignment = Alignment.Center) {
                Text(note, style = TextStyle(color = secondary, fontSize = 13.sp, textAlign = TextAlign.Center))
            }
            return@Column
        }
        s.rows.take(rows).forEachIndexed { i, r ->
            ChatRow(r, compact, faces.getOrNull(i), actionStartActivity(FariinWidget.openChat(c, r.cid)))
        }
    }
}

@Composable
private fun Header(s: HomeWidgetRules.Snapshot, compact: Boolean, open: Action, newChat: Action) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(GlanceModifier.defaultWeight().clickable(open), verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.mipmap.ic_launcher_round), contentDescription = null, modifier = GlanceModifier.size(22.dp))
            Spacer(GlanceModifier.width(8.dp))
            Text("Fariin", style = TextStyle(color = label, fontSize = 16.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            if (s.signedIn && s.unreadChats > 0) {
                Spacer(GlanceModifier.width(6.dp))
                Badge(HomeWidgetRules.countLabel(s.unreadChats), muted = false)
            }
        }
        if (s.signedIn) {
            Box(GlanceModifier.size(if (compact) 32.dp else 36.dp).background(ImageProvider(R.drawable.widget_button)).clickable(newChat),
                contentAlignment = Alignment.Center) {
                Image(ImageProvider(R.drawable.widget_ic_new_chat), contentDescription = "New Chat", modifier = GlanceModifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ChatRow(r: HomeWidgetRules.Row, compact: Boolean, face: Bitmap?, open: Action) {
    Row(GlanceModifier.fillMaxWidth().padding(vertical = if (compact) 3.dp else 4.dp).clickable(open), verticalAlignment = Alignment.CenterVertically) {
        if (face != null) Image(ImageProvider(face), contentDescription = null, modifier = GlanceModifier.size(if (compact) 32.dp else 40.dp))
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(r.name, style = TextStyle(color = label, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            r.preview?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = TextStyle(color = secondary, fontSize = 13.sp), maxLines = 1)
            }
        }
        when {
            r.unread > 0 -> { Spacer(GlanceModifier.width(8.dp)); Badge(HomeWidgetRules.countLabel(r.unread), r.muted) }
            r.markedUnread -> { Spacer(GlanceModifier.width(8.dp)); Box(GlanceModifier.size(10.dp).background(ImageProvider(
                if (r.muted) R.drawable.widget_badge_muted else R.drawable.widget_badge))) {} }
        }
    }
}

@Composable
private fun Badge(text: String, muted: Boolean) {
    Box(GlanceModifier.height(20.dp).background(ImageProvider(if (muted) R.drawable.widget_badge_muted else R.drawable.widget_badge))
        .padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = ColorProvider(if (muted) R.color.widget_on_badge_muted else R.color.widget_on_badge), fontSize = 12.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}
