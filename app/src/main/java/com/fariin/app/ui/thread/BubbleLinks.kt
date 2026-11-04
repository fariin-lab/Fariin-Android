package com.fariin.app.ui.thread

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import com.fariin.app.data.TextLinks
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** What a tapped run in a bubble does; provided once per chat ([rememberBubbleLinkTap]). Null: plain text. */
internal val LocalBubbleLinkTap = staticCompositionLocalOf<((TextLinks.Span) -> Unit)?> { null }

/** The links' tap where there is no chat to route them (the long-press menu's copy of the row). */
internal val NoLinkTap: (TextLinks.Span) -> Unit = {}

/**
 * iOS BubbleText.body's links on top of the already marked text: white on my bubble (blue on blue
 * could not be read), system blue on theirs; web links and emails underlined, @usernames not. A
 * username inside a group @mention is left to the mention (iOS drops that link, 2026-10-19).
 * Taps on the rest of the text still reach the row (double tap, long press, swipe).
 */
internal fun linkMarked(
    s: AnnotatedString, bold: List<IntRange>, color: Color, tap: (TextLinks.Span) -> Unit,
): AnnotatedString {
    val text = s.text
    if (!text.contains("http", true) && !text.contains("www.", true) && !text.contains("fariin.com", true) &&
        !text.contains('@') && text.count { it.isDigit() } < 7) return s
    val spans = TextLinks.find(text).filter { sp ->
        sp.kind != TextLinks.Kind.HANDLE || bold.none { it.first < sp.end && sp.start <= it.last }
    }
    if (spans.isEmpty()) return s
    return AnnotatedString.Builder(s).apply {
        for (sp in spans) {
            val style = SpanStyle(color = color,
                textDecoration = if (sp.kind == TextLinks.Kind.HANDLE) null else TextDecoration.Underline)
            addLink(LinkAnnotation.Clickable(sp.kind.name, TextLinkStyles(style)) { tap(sp) }, sp.start, sp.end)
        }
    }.toAnnotatedString()
}

/**
 * iOS ThreadView.routeThreadURL for a run tapped in a bubble. A call link opens its join screen;
 * a username (fariin.com/u/<h> or @h) opens my own profile when it is mine, the profile of the
 * person this chat is with, else that person's chat, or says the user does not exist; any other
 * web address opens straight away with no "Open link?" (owner, 2026-10-13) in the in-app browser
 * (a Custom Tab), a video link in its own app when one is installed. An email opens the mail app,
 * a number the dialler with it filled in (nothing is dialled).
 * Group, story and public-name links have no page of their own here yet, so they open the site.
 */
@Composable
internal fun rememberBubbleLinkTap(ui: ThreadUi, onNotice: (String) -> Unit): (TextLinks.Span) -> Unit {
    val ctx = LocalContext.current
    val nav = com.fariin.app.ui.people.LocalPeopleNav.current
    val scope = rememberCoroutineScope()
    val now by rememberUpdatedState(ui)
    val notice by rememberUpdatedState(onNotice)
    var notFound by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf<String?>(null) }
    // A channel admin invite link opens its page here, not the website (iOS ChannelAdminInviteView).
    var invite by remember { mutableStateOf<String?>(null) }
    val channelNav = com.fariin.app.ui.channels.LocalChannelNav.current
    invite?.let { code -> com.fariin.app.ui.channels.AdminInviteSheet(code, onClose = { invite = null }, onOpenChannel = { channelNav.open(it) }) }
    if (notFound) AlertDialog(
        onDismissRequest = { notFound = false },
        title = { Text("Sorry, this user doesn't seem to exist.") },
        confirmButton = { TextButton(onClick = { notFound = false }) { Text("OK") } },
    )
    return remember(ctx, nav, scope) {
        fun handle(h: String) {
            val key = h.lowercase()
            if (opening == key) return   // a second tap while the first is still opening
            opening = key
            scope.launch {
                try {
                    val u = now
                    val owner = handleOwner(h)
                    when {
                        owner == null -> notFound = true
                        owner == u.me -> nav.myProfile()
                        !u.isGroup && u.cid.isNotEmpty() && owner == com.fariin.app.crypto.Wire.otherUid(u.cid, u.me) -> nav.contact(owner)
                        else -> {
                            val p = runCatching { nav.searchPeople(h) }.getOrNull()?.firstOrNull { it.uid == owner }
                            val cid = p?.let { runCatching { nav.startChat(it) }.getOrNull() }
                            if (cid != null) nav.chat(cid) else notFound = true
                        }
                    }
                } finally { opening = null }
            }
        }
        val tap: (TextLinks.Span) -> Unit = { sp ->
            when (sp.kind) {
                TextLinks.Kind.HANDLE -> handle(sp.target)
                TextLinks.Kind.EMAIL -> start(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + sp.target))) { notice("No app here can send email") }
                TextLinks.Kind.PHONE -> start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + sp.target.filter { it.isDigit() || it == '+' }))) { notice("No app here can make calls") }
                TextLinks.Kind.WEB -> {
                    val call = com.fariin.app.call.CallLinkKey.fromLink(sp.target)
                    val h = com.fariin.app.data.QrRules.handleFrom(sp.target)
                    val adminCode = com.fariin.app.ui.channels.AdminInviteLink.code(sp.target)
                    when {
                        adminCode != null -> invite = adminCode
                        call != null -> nav.joinLink(call.text, com.fariin.app.call.CallLinkKey.linkSaysVideo(sp.target))
                        h != null -> handle(h)
                        else -> openWeb(ctx, sp.target) { notice("No app here can open this link") }
                    }
                }
            }
        }
        tap
    }
}

/** Who holds a username now (`usernames/{h}`, a released name is nobody's); null when nobody or unreadable. */
private suspend fun handleOwner(h: String): String? {
    val key = com.fariin.app.data.Usernames.lookupKey(h) ?: return null
    return runCatching {
        val d = com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("usernames").document(key).get().await()
        d.getString("uid")?.takeIf { it.isNotEmpty() && d.get("releasedAt") == null }
    }.getOrNull()
}

/** iOS WebLink.open: a video site in its app when one is installed, store and map pages in their
 *  apps, everything else in the in-app browser. */
private fun openWeb(ctx: Context, url: String, onFail: () -> Unit) {
    val uri = Uri.parse(url)
    val host = (uri.host ?: "").lowercase()
    val view = Intent(Intent.ACTION_VIEW, uri)
    if (host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try { ctx.startActivity(Intent(view).addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)); return }
            catch (_: ActivityNotFoundException) {}
        }
    }
    if (host == "play.google.com" || host == "maps.google.com" || host == "maps.app.goo.gl") { start(ctx, view, onFail); return }
    try { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(ctx, uri) }
    catch (_: Exception) { start(ctx, view, onFail) }
}

private fun start(ctx: Context, intent: Intent, onFail: () -> Unit) {
    try { ctx.startActivity(intent) } catch (_: ActivityNotFoundException) { onFail() } catch (_: SecurityException) { onFail() }
}

/** A web address from outside a bubble (a profile link): the same opener as a tapped link. */
internal fun openWebLink(ctx: Context, uri: Uri) = openWeb(ctx, uri.toString()) {}
