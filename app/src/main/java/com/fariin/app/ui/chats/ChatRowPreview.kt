package com.fariin.app.ui.chats

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.call.CallManager
import com.fariin.app.call.CallPhase
import com.fariin.app.call.CallWire
import com.fariin.app.data.ChatList
import com.fariin.app.data.ChatList.PreviewIcon
import com.fariin.app.data.Conversation
import com.fariin.app.data.VerifiedPeople
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The 1:1 call that is up right now, as the chat list needs it (iOS liveCallCid): the other person
 * and whether it is video. One shared reading of the call state for every row, not one per row.
 */
internal object LiveCallRow {
    data class Live(val peer: String, val video: Boolean)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val live: StateFlow<Live?> = CallManager.ui.map { u ->
        val up = u.status.phase == CallPhase.ACTIVE || u.status.phase == CallPhase.RECONNECTING ||
            (u.status.phase == CallPhase.OUTGOING && u.status.isCaller)
        if (up && u.peerUid.isNotEmpty()) Live(u.peerUid, u.status.video) else null
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(), null)

    fun cid(me: String, live: Live?): String? = live?.let { if (me.isEmpty()) null else CallWire.convId(me, it.peer) }
}

/** iOS `.green`: what "live" reads as across the app (the call bar, the answer button). */
private fun liveGreen(dark: Boolean) = if (dark) Color(0xFF30D158) else Color(0xFF34C759)

/** iOS VerifiedMark.blue / .gold. */
private val SealBlue = Color(0xFF2392FE)
private val SealGold = Color(0xFFE8B21E)

/**
 * The mark after the chat's name (iOS ChatRow): a person's tick in a 1:1 (gold for a company),
 * a group's or channel's by its kind, or Fariin's red outlined SCAM / FAKE in its place. Draws
 * nothing when there is nothing to say.
 */
@Composable
internal fun ChatRowVerifiedMark(conv: Conversation, me: String) {
    if (!conv.isGroup) {
        val other = conv.otherUid(me)
        if (other.isEmpty() || conv.isDeletedAccount(other)) return
        val ctx = LocalContext.current
        // Looked up while the row is on screen, at most once a day (VerifiedPeople).
        LaunchedEffect(other) { VerifiedPeople.warm(ctx.applicationContext, other) }
        val kinds by VerifiedPeople.kinds.collectAsState()
        val kind = VerifiedPeople.badge(kinds, other) ?: return
        Seal(if (VerifiedPeople.isGold(kind)) SealGold else SealBlue, "Verified")
        return
    }
    if (conv.modLabel == "scam" || conv.modLabel == "fake") {
        val red = Fariin.colors.red
        Box(Modifier.padding(start = 4.dp).height(15.dp).border(1.dp, red, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center) {
            Text(if (conv.modLabel == "scam") "SCAM" else "FAKE", fontSize = 9.5.sp, fontWeight = FontWeight.Bold,
                color = red, maxLines = 1, lineHeight = 10.sp)
        }
        return
    }
    if (conv.verified) Seal(if (VerifiedPeople.isGold(conv.verifiedType)) SealGold else SealBlue,
        if (conv.isChannel) "Verified" else "Verified group")
}

@Composable
private fun Seal(tint: Color, label: String) {
    Box(Modifier.padding(start = 4.dp).size(16.dp).background(Color.White, CircleShape).padding(1.dp)) {
        Icon(Icons.Filled.Verified, contentDescription = label, tint = tint, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun iconFor(icon: PreviewIcon): ImageVector = when (icon) {
    PreviewIcon.PIN -> Icons.Filled.PushPin
    PreviewIcon.CONTACT -> Icons.Filled.AccountCircle
    PreviewIcon.LOCATION -> Icons.Filled.LocationOn
    PreviewIcon.POLL -> Icons.Filled.BarChart
    PreviewIcon.MIC -> Icons.Filled.Mic
    PreviewIcon.DELETED -> Icons.Filled.Block
    PreviewIcon.VIDEO, PreviewIcon.VIDEO_CALL, PreviewIcon.VIDEO_CALL_OUT, PreviewIcon.VIDEO_CALL_IN -> Icons.Filled.Videocam
    PreviewIcon.PHOTO -> Icons.Filled.Photo
    PreviewIcon.ALBUM -> Icons.Filled.PhotoLibrary
    PreviewIcon.FILE, PreviewIcon.VIEW_ONCE -> Icons.Filled.Description
    PreviewIcon.FILE_ZIP -> Icons.Filled.FolderZip
    PreviewIcon.FILE_DOC -> Icons.AutoMirrored.Filled.Article
    PreviewIcon.FILE_AUDIO -> Icons.Filled.MusicNote
    PreviewIcon.GIF -> ImageVector.vectorResource(R.drawable.ic_gif)
    PreviewIcon.CALL, PreviewIcon.CALL_OUT, PreviewIcon.CALL_IN -> Icons.Filled.Call
    PreviewIcon.NEWER -> Icons.Filled.ArrowCircleUp
    PreviewIcon.HELLO -> Icons.Filled.WavingHand
    PreviewIcon.BLOCKED -> Icons.Filled.PanTool
}

/** One preview icon, as iOS previewRow draws it: a directional arrow on a call, the "1" circle on a view-once. */
@Composable
private fun PreviewGlyph(icon: PreviewIcon, tint: Color) {
    when (icon) {
        PreviewIcon.VIEW_ONCE -> Box(Modifier.fillMaxSize().padding(1.dp).background(tint, CircleShape), contentAlignment = Alignment.Center) {
            Text("1", fontSize = 9.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold, color = Fariin.colors.bg)
        }
        PreviewIcon.CALL_OUT, PreviewIcon.CALL_IN, PreviewIcon.VIDEO_CALL_OUT, PreviewIcon.VIDEO_CALL_IN -> Box(Modifier.fillMaxSize()) {
            val out = icon == PreviewIcon.CALL_OUT || icon == PreviewIcon.VIDEO_CALL_OUT
            val video = icon == PreviewIcon.VIDEO_CALL_OUT || icon == PreviewIcon.VIDEO_CALL_IN
            Icon(iconFor(icon), null, tint = tint,
                modifier = Modifier.align(Alignment.BottomStart).size(if (video) 13.dp else 11.dp))
            Icon(if (out) Icons.AutoMirrored.Filled.CallMade else Icons.AutoMirrored.Filled.CallReceived, null, tint = tint,
                modifier = Modifier.align(Alignment.TopEnd).size(8.dp))
        }
        else -> Icon(iconFor(icon), null, tint = tint, modifier = Modifier.fillMaxSize())
    }
}

/** One small picture of this chat, opened with its keys (as the list's old single thumb). */
@Composable
private fun Thumb(conv: Conversation, url: String, enc: com.fariin.app.crypto.EncMeta?, side: Dp, corner: Dp) {
    val ctx = LocalContext.current
    val media = remember(conv.id) {
        com.fariin.app.media.ChatMedia(ctx.applicationContext, conv.id, com.fariin.app.data.CryptoHub.get(ctx.applicationContext))
    }
    val file by produceState<java.io.File?>(null, url) { value = media.file(url, enc) }
    val bmp = com.fariin.app.ui.thread.rememberBitmap(file, 80)
    Box(Modifier.size(side).clip(RoundedCornerShape(corner)).background(Fariin.colors.placeholderFill)) {
        if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/**
 * The row's preview, iOS ChatRow.previewContent in its order: a call going on, blocked, recording,
 * typing, a draft, a reaction with its pictures or icon, the photo (an album as a stack of three),
 * a marker's icon and words, then the words. Always two lines tall so every row is the same height.
 */
@Composable
internal fun ChatRowPreviewText(
    conv: Conversation,
    me: String,
    preview: ChatList.RowPreview,
    draft: String?,
    live: LiveCallRow.Live?,
    recording: String?,
    typing: String?,
    voiceUnplayed: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = Fariin.colors
    val density = LocalDensity.current
    fun sp(d: Dp) = with(density) { d.toSp() }
    val inline = HashMap<String, InlineTextContent>()
    fun glyph(key: String, icon: PreviewIcon, tint: Color, size: Dp = 14.dp) {
        inline[key] = InlineTextContent(Placeholder(sp(size), sp(size), PlaceholderVerticalAlign.TextCenter)) { PreviewGlyph(icon, tint) }
    }
    val secondary = SpanStyle(color = c.secondary)
    val text: AnnotatedString = when {
        live != null -> buildAnnotatedString {
            // owner 2026-10-27: the kind of call in bold green, then " · In call" in grey.
            val green = liveGreen(c.dark)
            inline["call"] = InlineTextContent(Placeholder(sp(14.dp), sp(14.dp), PlaceholderVerticalAlign.TextCenter)) {
                Icon(if (live.video) Icons.Filled.Videocam else Icons.Filled.Call, null, tint = green, modifier = Modifier.fillMaxSize())
            }
            appendInlineContent("call", "[call]"); append(" ")
            pushStyle(SpanStyle(color = green, fontWeight = FontWeight.SemiBold)); append(if (live.video) "Video call" else "Voice call"); pop()
            pushStyle(secondary); append(" · In call"); pop()
        }
        preview.icon == PreviewIcon.BLOCKED -> buildAnnotatedString {
            glyph("g", PreviewIcon.BLOCKED, c.secondary)
            appendInlineContent("g", "[icon]"); append(" ")
            pushStyle(secondary); append(preview.text); pop()
        }
        recording != null -> buildAnnotatedString {
            glyph("g", PreviewIcon.MIC, c.accent, 13.dp)
            appendInlineContent("g", "[mic]")
            pushStyle(SpanStyle(color = c.accent)); append(" $recording"); pop()
        }
        typing != null -> buildAnnotatedString { pushStyle(SpanStyle(color = c.accent)); append(typing); pop() }
        !draft.isNullOrEmpty() -> buildAnnotatedString {
            pushStyle(SpanStyle(color = c.red)); append("Draft: "); pop()
            pushStyle(secondary); append(draft.replace('\n', ' ')); pop()
        }
        preview.reactionLead != null -> buildAnnotatedString {
            pushStyle(secondary); append(preview.reactionLead + " to "); pop()
            if (preview.reactionPictures) {
                // The real pictures, overlapping like a group's faces, the first on top.
                val pics = conv.lastReactionToImages
                val w = 20.dp + 12.dp * (pics.size - 1)
                inline["pics"] = InlineTextContent(Placeholder(sp(w), sp(20.dp), PlaceholderVerticalAlign.TextCenter)) {
                    Box(Modifier.width(w).height(20.dp)) {
                        for (i in pics.indices.reversed()) Box(Modifier.offset(x = 12.dp * i).size(20.dp)
                            .border(1.5.dp, c.bg, RoundedCornerShape(5.dp)).padding(1.dp)) {
                            Thumb(conv, pics[i].url, pics[i].enc, 18.dp, 4.dp)
                        }
                    }
                }
                appendInlineContent("pics", "[pictures]")
            } else if (preview.icon != null) {
                glyph("g", preview.icon, c.secondary)
                appendInlineContent("g", "[icon]")
            }
            pushStyle(secondary); append(" " + preview.text); pop()
        }
        preview.photo -> buildAnnotatedString {
            // An album is a stack (owner 2026-10-04): up to three, the first in front on the right,
            // only while its first picture IS the current thumbnail.
            val stack = if (conv.lastImages.firstOrNull()?.url == conv.lastImageUrl) conv.lastImages else emptyList()
            if (stack.size > 1) {
                val w = 22.dp + 8.dp * (stack.size - 1)
                inline["thumb"] = InlineTextContent(Placeholder(sp(w + 5.dp), sp(22.dp), PlaceholderVerticalAlign.TextCenter)) {
                    Box(Modifier.width(w).height(22.dp)) {
                        for (i in stack.indices.reversed()) Box(Modifier.offset(x = 8.dp * (stack.size - 1 - i)).size(22.dp)
                            .background(Color.White, RoundedCornerShape(5.dp)).padding(1.dp)) {
                            Thumb(conv, stack[i].url, stack[i].enc, 20.dp, 4.dp)
                        }
                    }
                }
                appendInlineContent("thumb", "[pictures]")
            } else if (conv.lastImageEnc != null && !conv.lastImageUrl.isNullOrEmpty()) {
                val url = conv.lastImageUrl
                val enc = conv.lastImageEnc
                inline["thumb"] = InlineTextContent(Placeholder(sp(25.dp), sp(20.dp), PlaceholderVerticalAlign.TextCenter)) {
                    Box(Modifier.fillMaxSize()) { Thumb(conv, url, enc, 20.dp, 4.dp) }
                }
                appendInlineContent("thumb", "[picture]")
            }
            prefixed(preview, c.label, secondary)
        }
        preview.icon != null -> buildAnnotatedString {
            // A missed call's icon is red, the words grey; an unheard voice note's mic is the accent.
            val tint = when {
                preview.missed -> c.red
                preview.icon == PreviewIcon.MIC && voiceUnplayed -> c.accent
                else -> c.secondary
            }
            glyph("g", preview.icon, tint, if (preview.icon == PreviewIcon.MIC) 15.dp else 14.dp)
            appendInlineContent("g", "[icon]"); append(" ")
            prefixed(preview, c.label, secondary)
        }
        else -> buildAnnotatedString { prefixed(preview, c.label, secondary) }
    }
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = c.secondary,
        inlineContent = inline,
        maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** iOS senderStyled: "Name: " bold in the text colour, the rest grey. */
private fun AnnotatedString.Builder.prefixed(p: ChatList.RowPreview, label: Color, rest: SpanStyle) {
    if (p.prefix.isNotEmpty()) { pushStyle(SpanStyle(color = label, fontWeight = FontWeight.Bold)); append(p.prefix); pop() }
    pushStyle(rest); append(p.text); pop()
}
