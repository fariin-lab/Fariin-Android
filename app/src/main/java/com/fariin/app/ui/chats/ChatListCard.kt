package com.fariin.app.ui.chats

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.fariin.app.R
import com.fariin.app.data.ChatCard
import com.fariin.app.data.ChatCardsStore
import com.fariin.app.ui.components.cachedVector
import com.fariin.app.ui.theme.Fariin

/** The one card for the top of the All chats list now, or null (iOS ChatCards.pick). */
@Composable
internal fun rememberChatCard(me: String): ChatCard? {
    val ctx = LocalContext.current
    val repo = remember { com.fariin.app.data.MyProfileRepository() }
    val profile by remember(me) { repo.listen(me) }.collectAsState(initial = null)
    // Coming back from the system settings is how a denied permission gets turned on.
    var notifOn by remember { mutableStateOf<Boolean?>(null) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        notifOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    val p = profile
    val joined = com.fariin.app.Fb.auth.currentUser?.metadata?.creationTimestamp?.toDouble()?.takeIf { it > 0 }
    return ChatCard.pick(
        admin = ChatCardsStore.admin, closed = ChatCardsStore.closed(me), uid = me,
        build = remember { runCatching { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)).toInt() }.getOrDefault(0) }, region = java.util.Locale.getDefault().country.ifEmpty { null },
        now = System.currentTimeMillis().toDouble(), joinedAt = joined,
        // Not read yet: no built-in card (it would flash and go).
        photoUrl = if (p == null) "?" else p.photoUrl, handle = p?.handle, notificationsOn = notifOn,
    )
}

/**
 * The card (iOS ChatCardView): 16 in from each side, radius 20, an optional 40 dp picture, the
 * title over up to three lines of subtitle, and the X (or an arrow for a card that cannot be closed
 * but goes somewhere). The whole card is the tap.
 */
@Composable
internal fun ChatListCard(card: ChatCard, me: String) {
    val c = Fariin.colors
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.fillMaxWidth().background(c.bg).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
        ChatCardContent(card, me, Modifier.clip(shape).background(c.bgSecondary))
    }
}

/**
 * The card's inside with no frame or outer margin: picture, words, X or arrow (iOS ChatCardContent).
 * Drawn on its own frame by [ChatListCard], or in the folder tabs' panel under the tab row
 * ([ChatFolderBar] card, owner 2026-11-20: one panel).
 */
@Composable
internal fun ChatCardContent(card: ChatCard, me: String, modifier: Modifier = Modifier) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val nav = com.fariin.app.ui.people.LocalPeopleNav.current
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val onTap = {
        when (card.builtin) {
            ChatCard.Builtin.PHOTO, ChatCard.Builtin.USERNAME -> nav.editProfile()
            ChatCard.Builtin.NOTIFICATIONS -> {
                val asked = ctx.getSharedPreferences("chat_cards", 0).getBoolean("askedNotif", false)
                if (Build.VERSION.SDK_INT >= 33 && !asked) {
                    ctx.getSharedPreferences("chat_cards", 0).edit().putBoolean("askedNotif", true).apply()
                    askNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            null -> card.link?.let { l ->
                // https in the in-app browser; fariin:// as the app's own route, whatever takes it.
                if (l.startsWith("https://", ignoreCase = true)) com.fariin.app.ui.thread.openWebLink(ctx, Uri.parse(l))
                else runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l)).setPackage(ctx.packageName)) }
            }
        }
        Unit
    }
    run {
        Row(modifier.fillMaxWidth().clickable(onClick = onTap)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            card.image?.let { url ->
                coil3.compose.AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(c.placeholderFill))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(card.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (card.subtitle.isNotEmpty()) Text(card.subtitle, fontSize = 15.sp, color = c.secondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            when {
                card.dismissible -> Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp))
                    .clickable(onClickLabel = "Close") { ChatCardsStore.dismiss(card.id, me) }, contentAlignment = Alignment.Center) {
                    Icon(ImageVector.cachedVector(R.drawable.ic_sf_xmark), "Close", tint = c.secondary, modifier = Modifier.size(13.dp))
                }
                card.link != null -> Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(ImageVector.cachedVector(R.drawable.ic_sf_chevron_right), null, tint = c.secondary, modifier = Modifier.size(13.dp))
                }
                else -> Spacer(Modifier.width(12.dp))
            }
        }
    }
}
