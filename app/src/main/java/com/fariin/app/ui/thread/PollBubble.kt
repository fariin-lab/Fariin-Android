package com.fariin.app.ui.thread

import com.fariin.app.ui.components.GlassDropdown
import com.fariin.app.ui.components.GlassAlert
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fariin.app.data.Message
import com.fariin.app.data.PollCard
import com.fariin.app.data.PollRules
import com.fariin.app.data.PollVotes
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.Snack
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.asImageBitmap
import java.util.UUID
import androidx.compose.ui.graphics.vector.ImageVector
import com.fariin.app.ui.components.cachedVector
import com.fariin.app.R

/** What a poll bubble needs from the chat (ThreadUi), kept small so the bubble stays reusable. */
internal data class PollContext(
    val cid: String,
    val me: String,
    /** A channel: counts from the post, never who voted (iOS 2026-10-29, S4). */
    val channel: Boolean,
    val names: Map<String, String>,
    val photos: Map<String, String>,
)

/** The live votes for one poll (shared listener, PollVotes), as a uid map either way. */
@Composable
private fun rememberPollVotes(m: Message, ctx: PollContext): Pair<Map<String, List<Int>>, List<Int>> {
    val flow = remember(ctx.cid, m.id, ctx.channel) { PollVotes.subscribe(ctx.cid, m.id, ctx.me, ctx.channel) }
    DisposableEffect(ctx.cid, m.id, ctx.channel) { onDispose { PollVotes.release(ctx.cid, m.id, ctx.channel) } }
    val v by flow.collectAsState()
    val mine = v.byUid[ctx.me] ?: emptyList()
    val votes = if (ctx.channel) PollRules.channelVotes(m.pollCounts, m.pollVoters, m.pollTallyAt, mine, v.mineAt, ctx.me, v.prevMine, v.prevAt)
        else v.byUid
    return votes to mine
}

/** Now, ticking over once when the poll's end time passes, so the bubble closes by itself. */
@Composable
private fun rememberPollNow(p: PollCard): Double {
    var now by remember { mutableStateOf(System.currentTimeMillis().toDouble()) }
    LaunchedEffect(p.endsAt) {
        val ends = p.endsAt ?: return@LaunchedEffect
        val wait = ends - System.currentTimeMillis()
        if (wait > 0) { delay(wait.toLong() + 50); now = System.currentTimeMillis().toDouble() }
    }
    return now
}

/**
 * A poll in a bubble (iOS PollBubbleView, the "thick bars" look): the question, "Select one" or
 * "Select one or more" (a quiz says so), one row per option, and the footer that opens the results.
 * Rows are plain until I vote or the poll is over; then each shows its bar and percentage, and a
 * quiz marks right and wrong. A tap votes: single choice replaces (tapping my answer takes it
 * back), multiple toggles, a quiz answer is final. No votes on a bubble still being sent.
 */
/** The card look (iOS AppFeatures.pollCard, owner 2026-11-14). False = the old thick bars (kept, not deleted). */
internal val POLL_CARD: Boolean get() = com.fariin.app.data.AppFeatures.offOnly("pollCard", true)

/**
 * The card's colours, iOS PollCardColors "soft ink" (owner picked it 2026-11-14): see-through black
 * (light) / white (dark) so the track reads on any bubble or wallpaper; my pick darker with a ring
 * and tick in the same ink; every other option one grey. On my own bubble, the bubble's text colour.
 */
private class PollInk(val dark: Boolean, val isMe: Boolean, val ink: Color) {
    private fun soft(d: Float, l: Float) = if (dark) Color.White.copy(alpha = d) else Color.Black.copy(alpha = l)
    val track = if (isMe) ink.copy(alpha = 0.14f) else soft(0.08f, 0.06f)
    val mine = if (isMe) ink.copy(alpha = 0.34f) else soft(0.28f, 0.28f)
    val other = if (isMe) ink.copy(alpha = 0.22f) else soft(0.12f, 0.09f)
    val ring = if (isMe) ink.copy(alpha = 0.9f) else soft(0.9f, 0.9f)
    val check = ink
    val wrong = if (isMe) ink else if (dark) Color(0xFFFF7B72) else Color(0xFFC5221F)
    val emptyRing = if (isMe) ink.copy(alpha = 0.7f) else if (dark) Color(0xFF6B7079) else Color(0xFFA3A8B0)
    val secondary = ink.copy(alpha = if (isMe) 0.8f else 0.62f)
}

/** An option's picture, opened like a chat photo: this chat's cache, then the download and its keys. */
@Composable
internal fun PollThumb(img: com.fariin.app.data.PollImage, cid: String, side: Dp, corner: Dp, back: Color) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val media = LocalChatMedia.current ?: remember(cid) {
        com.fariin.app.media.ChatMedia(ctx.applicationContext, cid, com.fariin.app.data.CryptoHub.get(ctx.applicationContext))
    }
    val bmp by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, img.url) {
        val f = runCatching { media.file(img.url, img.enc) }.getOrNull() ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { decodeSampled(f, 120) }
    }
    Box(Modifier.size(side).clip(RoundedCornerShape(corner)).background(back)) {
        bmp?.let { androidx.compose.foundation.Image(it, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize()) }
    }
}

/** "3 days left", "5 h left", "20 min left", "Ended", or null for a poll with no end (iOS timeLeftWords). */
internal fun pollTimeLeft(endsAt: Double?, open: Boolean, now: Long = System.currentTimeMillis()): String? {
    if (!open) return "Ended"
    val ends = endsAt ?: return null
    val left = (ends - now) / 1000.0
    if (left <= 0) return "Ended"
    val days = (left / 86_400).toInt()
    if (days >= 1) return "$days day${if (days == 1) "" else "s"} left"
    val hours = (left / 3_600).toInt()
    if (hours >= 1) return "$hours h left"
    return "${maxOf(1, (left / 60).toInt())} min left"
}

/**
 * The poll card (iOS MessageRowLayout.pollCard + PollOptionRow.paintCard): the question, a subtitle
 * with the count (it opens the results), one capsule per option, a quiz line, and the footer
 * ("Public votes" / "Anonymous", the time left, then the time and ticks). An option with a picture
 * is a 54 dp capsule with a 38 dp thumbnail; a tap anywhere on a row (the picture too) votes.
 */
@Composable
private fun PollCardBody(m: Message, poll: PollCard, ctx: PollContext, isMe: Boolean, width: Dp, meta: @Composable () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val ink = if (isMe) Color.White else c.label
    val pal = PollInk(c.dark, isMe, ink)
    val (votes, mine) = rememberPollVotes(m, ctx)
    val now = rememberPollNow(poll)
    val open = PollRules.isOpen(poll, m.pollClosed, now)
    val canVote = open && m.sendState == null && m.hasServerTime
    val showResults = PollRules.showResults(mine, open)
    val quiz = poll.isQuiz
    val reveal = quiz && showResults
    val hideFaces = poll.hideVoters || ctx.channel
    var results by remember { mutableStateOf(false) }
    val tap = rememberLightTap()
    val voters = votes.size
    val count = PollRules.voteCount(voters)
    val sub = when {
        quiz -> "Quiz · $voters answer${if (voters == 1) "" else "s"}"
        !showResults -> "$count · Vote to see results"
        else -> "$count · " + (if (poll.multiple) "Multiple choice" else "Single choice")
    }

    Column(Modifier.width(width).padding(top = 6.dp, bottom = 2.dp)) {
        Text(poll.question, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = ink)
        Spacer(Modifier.height(4.dp))
        Text(sub, fontSize = 13.sp, color = pal.secondary, modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .clickable(enabled = m.sendState == null) { results = true })
        Spacer(Modifier.height(10.dp))
        poll.options.forEachIndexed { i, opt ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            val chose = PollRules.voters(votes, i)
            val fraction = if (showResults) PollRules.fraction(votes, i).toFloat() else 0f
            val state = when {
                reveal && i in poll.correct -> 1          // right
                reveal && i in mine -> 2                  // wrong
                !reveal && i in mine -> 1                 // mine
                else -> 0
            }
            val marked = showResults && state != 0
            val ringed = showResults && state == 1
            val pic = poll.imageAt(i)
            val grow by androidx.compose.animation.core.animateFloatAsState(fraction.coerceIn(0f, 1f),
                androidx.compose.animation.core.tween(250), label = "pollFill")
            Box(Modifier.fillMaxWidth().heightIn(min = if (pic != null) 54.dp else 46.dp)
                .clip(CircleShape).background(pal.track)
                .then(if (ringed) Modifier.border(1.5.dp, pal.ring, CircleShape) else Modifier)
                .clickable(enabled = canVote) {
                    val next = PollRules.toggle(poll, mine, i, canVote) ?: return@clickable
                    tap()
                    scope.launch {
                        try { PollVotes.setVote(ctx.cid, m.id, ctx.me, ctx.channel, next) }
                        catch (_: Exception) { Snack.show("Couldn't save your vote.") }
                    }
                }) {
                if (grow > 0f) Box(Modifier.matchParentSize().padding(end = 0.dp)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(grow).clip(CircleShape)
                        .background(if (state == 1) pal.mine else pal.other))
                }
                Row(Modifier.fillMaxWidth().heightIn(min = if (pic != null) 54.dp else 46.dp)
                    .padding(start = if (pic != null) 10.dp else 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (!showResults) {
                        val shape = if (poll.multiple) RoundedCornerShape(5.dp) else CircleShape
                        Box(Modifier.size(18.dp).border(1.5.dp, pal.emptyRing, shape))
                        Spacer(Modifier.width(10.dp))
                    }
                    if (pic != null) {
                        PollThumb(pic, ctx.cid, 38.dp, 10.dp, ink.copy(alpha = 0.12f))
                        Spacer(Modifier.width(10.dp))
                    }
                    if (marked) {
                        Icon(ImageVector.cachedVector(if (state == 2) R.drawable.ic_sf_xmark else R.drawable.ic_sf_checkmark), null,
                            tint = if (state == 2) pal.wrong else pal.check, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(opt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink, modifier = Modifier.weight(1f))
                    if (showResults) {
                        val faces = if (hideFaces) emptyList() else chose.sorted().take(3).map { ctx.photos[it] }
                        if (faces.isNotEmpty()) {
                            Box(Modifier.width((24 + 16 * (faces.size - 1)).dp)) {
                                faces.forEachIndexed { k, f ->
                                    Avatar(f, 24.dp, Modifier.offset(x = (16 * k).dp)
                                        .border(2.dp, if (isMe) ink.copy(alpha = 0.3f) else c.card, CircleShape))
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(PollRules.percent(votes, i), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ink)
                    }
                }
            }
        }
        if (reveal && mine.isNotEmpty()) {
            val right = poll.correct.containsAll(mine)
            Spacer(Modifier.height(10.dp))
            Text(if (right) "You got it right" else "Not this time", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (isMe) ink else if (right) c.label else pal.wrong)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (poll.hideVoters) "Anonymous" else "Public votes", fontSize = 12.sp, color = pal.secondary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = m.sendState == null) { results = true })
            pollTimeLeft(poll.endsAt, open)?.let {
                Spacer(Modifier.width(4.dp))
                Text("· $it", fontSize = 12.sp, color = pal.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            meta()
        }
    }
    if (results) PollResultsSheet(m, poll, ctx, votes, mine, open) { results = false }
}

@Composable
internal fun PollBody(m: Message, poll: PollCard, ctx: PollContext, isMe: Boolean, width: Dp, meta: @Composable () -> Unit) {
    if (POLL_CARD) { PollCardBody(m, poll, ctx, isMe, width, meta); return }
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val ink = if (isMe) Color.White else c.label
    val (votes, mine) = rememberPollVotes(m, ctx)
    val now = rememberPollNow(poll)
    val open = PollRules.isOpen(poll, m.pollClosed, now)
    val canVote = open && m.sendState == null && m.hasServerTime
    val showResults = PollRules.showResults(mine, open)
    val hideFaces = poll.hideVoters || ctx.channel
    var results by remember { mutableStateOf(false) }
    val tap = rememberLightTap()   // iOS: a light tap on each vote

    Column(Modifier.width(width).padding(top = 4.dp)) {
        Text(poll.question, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = ink)
        Spacer(Modifier.height(3.dp))
        Text(PollRules.subtitle(poll), fontSize = 13.sp, color = ink.copy(alpha = 0.75f))
        Spacer(Modifier.height(12.dp))
        poll.options.forEachIndexed { i, opt ->
            OptionRow(
                label = opt, chosen = i in mine, multiple = poll.multiple, isMe = isMe,
                fraction = PollRules.fraction(votes, i).toFloat(), percent = PollRules.percent(votes, i),
                showResults = showResults, mark = PollRules.quizMark(poll, mine, open, i),
                faces = if (showResults && !hideFaces) PollRules.voters(votes, i).take(3).map { ctx.photos[it] } else emptyList(),
                enabled = canVote,
            ) {
                val next = PollRules.toggle(poll, mine, i, canVote) ?: return@OptionRow
                tap()
                scope.launch {
                    try { PollVotes.setVote(ctx.cid, m.id, ctx.me, ctx.channel, next) }
                    catch (_: Exception) { Snack.show("Couldn't save your vote.") }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(PollRules.footer(votes.size, open), fontSize = 13.sp,
                fontWeight = if (votes.isNotEmpty()) FontWeight.SemiBold else FontWeight.Normal,
                color = ink.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = m.sendState == null) { results = true }.padding(vertical = 6.dp))
            Spacer(Modifier.width(8.dp))
            meta()
        }
    }
    if (results) PollResultsSheet(m, poll, ctx, votes, mine, open) { results = false }
}

@Composable
private fun OptionRow(
    label: String, chosen: Boolean, multiple: Boolean, isMe: Boolean, fraction: Float, percent: String,
    showResults: Boolean, mark: PollRules.QuizMark, faces: List<String?>, enabled: Boolean, onTap: () -> Unit,
) {
    val c = Fariin.colors
    val ink = if (isMe) Color.White else c.label
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onTap)
        .padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(chosen, multiple, isMe, mark)
            Spacer(Modifier.width(10.dp))
            Text(label, fontSize = 17.sp, color = ink, modifier = Modifier.weight(1f))
            if (showResults) {
                if (faces.isNotEmpty()) {
                    Box(Modifier.width((16 + 10 * (faces.size - 1)).dp)) {
                        faces.forEachIndexed { k, f -> Avatar(f, 16.dp, Modifier.offset(x = (10 * k).dp)) }
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(percent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = 0.85f))
            }
        }
        if (showResults) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.padding(start = 32.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                .background(if (isMe) Color.White.copy(alpha = 0.25f) else c.label.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(3.dp))
                    .background(if (isMe) Color.White.copy(alpha = 0.9f) else c.blue))
            }
        }
    }
}

/** The ring (or square) before an option: filled with a tick when chosen; a quiz paints right
 *  green and my wrong answer red once revealed. */
@Composable
private fun Glyph(chosen: Boolean, multiple: Boolean, isMe: Boolean, mark: PollRules.QuizMark) {
    val c = Fariin.colors
    val ring = if (isMe) Color.White.copy(alpha = 0.8f) else c.secondary
    val shape = if (multiple) RoundedCornerShape(5.dp) else CircleShape
    val green = Color(0xFF34C759)
    val (fill, icon) = when {
        mark == PollRules.QuizMark.RIGHT -> green to ImageVector.cachedVector(R.drawable.ic_sf_checkmark)
        mark == PollRules.QuizMark.WRONG -> c.red to ImageVector.cachedVector(R.drawable.ic_sf_xmark)
        chosen -> (if (isMe) Color.White else c.blue) to ImageVector.cachedVector(R.drawable.ic_sf_checkmark)
        else -> null to null
    }
    Box(
        Modifier.size(22.dp).clip(shape)
            .then(if (fill != null) Modifier.background(fill) else Modifier.border(2.dp, ring, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, null, modifier = Modifier.size(15.dp),
            tint = if (fill == Color.White) c.blue else Color.White)
    }
}

/**
 * The results page (iOS PollResultsSheet): each option with its count and, unless the poll hides
 * them, who chose it; the end time or "Poll ended"; Stop Poll for its creator while it is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PollResultsSheet(m: Message, poll: PollCard, ctx: PollContext, votes: Map<String, List<Int>>,
                             mine: List<Int>, open: Boolean, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmStop by remember { mutableStateOf(false) }
    var stopped by remember { mutableStateOf(false) }
    var stopError by remember { mutableStateOf<String?>(null) }
    val isOpen = open && !stopped
    val hidden = poll.hideVoters || ctx.channel
    fun name(uid: String) = if (uid == ctx.me) "You" else ctx.names[uid] ?: "Member"
    val now = System.currentTimeMillis()
    val endsIn = poll.endsAt?.let {
        DateUtils.getRelativeTimeSpanString(it.toLong(), now, DateUtils.MINUTE_IN_MILLIS).toString().lowercase()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet,
        containerColor = com.fariin.app.ui.components.sheetFill, shape = com.fariin.app.ui.components.SheetShape) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Poll Results", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Done", color = c.accent, fontWeight = FontWeight.SemiBold) }
            }
            if (poll.isQuiz) Text("Quiz", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.secondary)
            Text(poll.question, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label)
            Text(PollRules.resultsStatus(votes.size, isOpen, endsIn), fontSize = 15.sp, color = c.secondary)
            poll.options.forEachIndexed { i, opt ->
                val who = PollRules.voters(votes, i).sortedBy { name(it).lowercase() }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The right answer shows only after I voted or it ended.
                    if (i in poll.correct && (mine.isNotEmpty() || !isOpen)) {
                        Icon(ImageVector.cachedVector(R.drawable.ic_sf_checkmark), null, tint = Color(0xFF34C759), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    poll.imageAt(i)?.let { img ->
                        PollThumb(img, ctx.cid, 28.dp, 7.dp, c.placeholderFill)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(opt, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.secondary, modifier = Modifier.weight(1f))
                    Text(PollRules.percent(votes, i), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.secondary)
                }
                Spacer(Modifier.height(6.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card)
                    .padding(horizontal = 16.dp, vertical = 10.dp)) {
                    when {
                        hidden -> Text(if (who.isEmpty()) "No votes" else PollRules.voteCount(who.size), fontSize = 16.sp, color = c.secondary)
                        who.isEmpty() -> Text("No votes", fontSize = 16.sp, color = c.secondary)
                        else -> who.forEach { uid ->
                            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(if (uid == ctx.me) null else ctx.photos[uid], 30.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(name(uid), fontSize = 16.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (hidden) {
                Spacer(Modifier.height(10.dp))
                Text("Names are hidden for this poll.", fontSize = 13.sp, color = c.secondary)
            }
            if (PollRules.canStop(m.authorId, ctx.me, isOpen)) {
                Spacer(Modifier.height(22.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card)
                    .clickable { confirmStop = true }.padding(16.dp)) {
                    Text("Stop Poll", fontSize = 17.sp, color = c.red)
                }
                Spacer(Modifier.height(6.dp))
                Text("No one can vote after this. The results stay.", fontSize = 13.sp, color = c.secondary)
            }
        }
    }
    if (confirmStop) GlassAlert(
        onDismissRequest = { confirmStop = false },
        title = { Text("Stop this poll?") },
        text = { Text("No one will be able to vote anymore.") },
        dismissButton = { TextButton({ confirmStop = false }) { Text("Cancel") } },
        confirmButton = {
            TextButton({
                confirmStop = false
                stopped = true
                scope.launch {
                    try { PollVotes.stop(ctx.cid, m.id) } catch (x: Exception) {
                        stopped = false
                        stopError = x.localizedMessage ?: "Please try again."
                    }
                }
            }) { Text("Stop Poll", color = c.red) }
        },
    )
    stopError?.let { e ->
        GlassAlert(onDismissRequest = { stopError = null }, title = { Text("Could not stop the poll") },
            text = { Text(e) }, confirmButton = { TextButton({ stopError = null }) { Text("OK") } })
    }
}

/** One option row in the composer: a stable id (so a drag moves the row, not just its words). */
private data class PollOpt(val id: Long, val text: String = "", val right: Boolean = false)

/**
 * An option's picture in the composer (iOS poll-fast-1114): the 512 px JPEG and its thumbnail, and
 * the upload that started the moment it was picked. Send waits only for uploads still running.
 */
private class PollPic(val jpeg: ByteArray, val thumb: androidx.compose.ui.graphics.ImageBitmap) {
    var job: kotlinx.coroutines.Deferred<com.fariin.app.data.PollImage>? = null
}

/** The picked photo as the poll's 512 px JPEG at 0.75 (iOS uploadPollImage), plus its thumbnail. */
private fun pollPicOf(context: android.content.Context, uri: android.net.Uri): PollPic? = runCatching {
    val b = com.fariin.app.media.BitmapDecode.fromUri(context, uri, 512) ?: return null
    val (w, h) = com.fariin.app.media.MediaRules.fit(b.width, b.height, 512)
    val s = if (w != b.width || h != b.height) android.graphics.Bitmap.createScaledBitmap(b, w, h, true) else b
    val out = java.io.ByteArrayOutputStream()
    s.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
    val t = com.fariin.app.media.MediaRules.fit(s.width, s.height, 160)
    PollPic(out.toByteArray(), android.graphics.Bitmap.createScaledBitmap(s, t.first, t.second, true).asImageBitmap())
}.getOrNull()

/**
 * Compose a new poll (iOS PollComposerSheet): a question, 2 to 10 options (typing into the last
 * one adds the next by itself), then Multiple answers, Hide who voted, Quiz (tap an option's circle
 * to mark it right; a quiz takes one answer) and an end time. [onSend] gets the poll's marker text,
 * which goes out sealed like any message.
 *
 * iOS poll-media-1114: a filled option can carry a picture (the paperclip; tap the thumbnail to
 * replace or remove it) and is dragged by its handle to a new place; the empty row stays last.
 * A picture uploads the moment it is picked ([upload]); Send waits only for the ones still going,
 * retries a failed one, and never sends a poll that points at a missing file.
 */
@Composable
internal fun PollComposer(onDismiss: () -> Unit, upload: (suspend (ByteArray) -> com.fariin.app.data.PollImage)? = null,
                          onSend: (String) -> Unit) {
    val c = Fariin.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var nextId by remember { mutableStateOf(2L) }
    val opts = remember { mutableStateListOf(PollOpt(0), PollOpt(1)) }
    val pics = remember { androidx.compose.runtime.mutableStateMapOf<Long, PollPic>() }
    var multiple by remember { mutableStateOf(false) }
    var hideVoters by remember { mutableStateOf(false) }
    var quiz by remember { mutableStateOf(false) }
    var ends by remember { mutableStateOf(PollRules.EndChoice.NEVER) }
    var endsMenu by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var pickFor by remember { mutableStateOf<Long?>(null) }
    var picMenuFor by remember { mutableStateOf<Long?>(null) }
    val texts = opts.map { it.text }
    val rights = opts.map { it.right }
    val canSend = PollRules.canSend(question, texts, quiz, rights) && !sending

    fun tidy() {
        val next = PollRules.tidyOptions(opts.map { it.text })
        while (opts.size < next.size) opts.add(PollOpt(nextId++))
        while (opts.size > next.size) { pics.remove(opts.last().id)?.job?.cancel(); opts.removeAt(opts.size - 1) }
    }
    fun startUpload(p: PollPic) {
        val up = upload ?: return
        p.job = scope.async(kotlinx.coroutines.Dispatchers.IO) { up(p.jpeg) }
    }
    fun setPic(id: Long, p: PollPic?) {
        pics.remove(id)?.job?.cancel()
        if (p != null) { pics[id] = p; startUpload(p) }
    }
    // Closing without sending stops the uploads still going (the scope ends with the page).
    fun close() { if (!sending) onDismiss() }
    fun send() {
        // One send per open: a second tap while the page goes must not send it twice (iOS audit).
        if (sent || !canSend) return
        sending = true
        scope.launch {
            try {
                // Each kept option's picture, in screen order: still running = wait; failed = again.
                val images = opts.map { o ->
                    val p = pics[o.id]?.takeIf { o.text.isNotBlank() } ?: return@map null
                    val done = runCatching { p.job?.await() }.getOrNull()
                    done ?: run { startUpload(p); p.job?.await() ?: error("no upload") }
                }
                sent = true
                onSend(PollRules.compose(UUID.randomUUID().toString().take(12).lowercase(), question, texts,
                    multiple, hideVoters, quiz, rights, ends, System.currentTimeMillis().toDouble(), images))
                onDismiss()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                sendError = "A picture couldn't be uploaded. Check your connection and try again."
            } finally { sending = false }
        }
    }
    val footer = buildList {
        if (hideVoters) add("Results show only the numbers, not names.")
        if (quiz) add("Voters see if they were right after they answer, and cannot change it.")
        if (ends != PollRules.EndChoice.NEVER) add("Voting stops automatically.")
    }.ifEmpty { listOf("Anyone in the chat can vote and see who voted.") }.joinToString(" ")

    // Drag to reorder: the row held moves with the finger and trades places as it passes a neighbour.
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragDy by remember { mutableStateOf(0f) }
    val heights = remember { HashMap<Long, Int>() }
    fun dragBy(dy: Float) {
        val id = dragId ?: return
        dragDy += dy
        val lastEmpty = opts.lastOrNull()?.text?.isEmpty() == true
        while (true) {
            val i = opts.indexOfFirst { it.id == id }
            val limit = if (lastEmpty) opts.size - 2 else opts.size - 1
            if (dragDy > 0 && i < limit) {
                val h = heights[opts[i + 1].id] ?: break
                if (dragDy < h / 2f) break
                opts.add(i + 1, opts.removeAt(i)); dragDy -= h
            } else if (dragDy < 0 && i > 0) {
                val h = heights[opts[i - 1].id] ?: break
                if (-dragDy < h / 2f) break
                opts.add(i - 1, opts.removeAt(i)); dragDy += h
            } else break
        }
    }

    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        dismissOnBackPress = !sending)) {
        com.fariin.app.ui.components.AppPageDialogWindow()
        Column(Modifier.fillMaxSize().background(c.bgSecondary).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::close, enabled = !sending) { Icon(ImageVector.cachedVector(R.drawable.ic_sf_xmark), "Close", tint = c.label) }
                Text("New Poll", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.weight(1f))
                if (sending) Box(Modifier.size(64.dp, 40.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), color = c.blue, strokeWidth = 2.dp)
                } else TextButton(onClick = ::send, enabled = canSend) {
                    Text("Send", fontWeight = FontWeight.SemiBold, color = if (canSend) c.blue else c.secondary)
                }
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState(), enabled = dragId == null).padding(horizontal = 16.dp, vertical = 8.dp)) {
                SectionTitle("Question")
                Group {
                    Field(question, "Ask a question", maxLines = 3) { question = it }
                }
                Spacer(Modifier.height(20.dp))
                SectionTitle("Options")
                Group {
                    opts.forEachIndexed { i, o ->
                        androidx.compose.runtime.key(o.id) {
                            val dragging = dragId == o.id
                            Column(Modifier.zIndex(if (dragging) 1f else 0f)
                                .onSizeChanged { heights[o.id] = it.height }
                                .offset { androidx.compose.ui.unit.IntOffset(0, if (dragging) dragDy.toInt() else 0) }
                                .background(if (dragging) c.card else Color.Transparent)) {
                                if (i > 0 && !dragging) Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (quiz) {
                                        Box(Modifier.size(24.dp).clip(CircleShape)
                                            .then(if (o.right) Modifier.background(Color(0xFF34C759)) else Modifier.border(1.5.dp, c.secondary, CircleShape))
                                            .clickable {
                                                // A quiz has one right answer.
                                                for (k in opts.indices) opts[k] = opts[k].copy(right = if (opts[k].id == o.id) !opts[k].right else false)
                                            }, contentAlignment = Alignment.Center) {
                                            if (o.right) Icon(ImageVector.cachedVector(R.drawable.ic_sf_checkmark), "Right answer", tint = Color.White, modifier = Modifier.size(16.dp))
                                        }
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    Box(Modifier.weight(1f)) {
                                        Field(o.text, "Option ${i + 1}", maxLines = 2) { v ->
                                            val k = opts.indexOfFirst { it.id == o.id }
                                            if (k >= 0) opts[k] = opts[k].copy(text = v)
                                            // A picture rides only on a row with words.
                                            if (v.isBlank()) setPic(o.id, null)
                                            tidy()
                                        }
                                    }
                                    if (o.text.isNotEmpty()) {
                                        val pic = pics[o.id]
                                        if (pic != null) {
                                            Box {
                                                androidx.compose.foundation.Image(pic.thumb, "Option photo",
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable { picMenuFor = o.id })
                                                GlassDropdown(expanded = picMenuFor == o.id, onDismissRequest = { picMenuFor = null },
                                                    shape = com.fariin.app.ui.components.FariinMenu.shape, border = com.fariin.app.ui.components.FariinMenu.border,
                                                    containerColor = com.fariin.app.ui.components.FariinMenu.container) {
                                                    DropdownMenuItem(text = { Text("Replace Photo") }, onClick = { picMenuFor = null; pickFor = o.id })
                                                    DropdownMenuItem(text = { Text("Remove Photo", color = c.red) }, onClick = { picMenuFor = null; setPic(o.id, null) })
                                                }
                                            }
                                        } else if (upload != null) IconButton(onClick = { pickFor = o.id }, modifier = Modifier.size(36.dp)) {
                                            Icon(ImageVector.cachedVector(R.drawable.ic_sf_paperclip), "Add photo", tint = c.secondary, modifier = Modifier.size(22.dp))
                                        }
                                    }
                                    // Remove only an option that has words, and never below two rows.
                                    if (opts.size > 2 && o.text.isNotEmpty()) {
                                        IconButton(onClick = {
                                            setPic(o.id, null); opts.removeAll { it.id == o.id }; tidy()
                                        }, modifier = Modifier.size(36.dp)) {
                                            Icon(ImageVector.cachedVector(R.drawable.ic_sf_minus_circle_fill), "Remove option", tint = c.red, modifier = Modifier.size(22.dp))
                                        }
                                    }
                                    // The handle: a filled row only; the empty row at the end never moves.
                                    if (o.text.isNotEmpty()) {
                                        Box(Modifier.size(36.dp).pointerInput(o.id) {
                                            detectDragGestures(
                                                onDragStart = { dragId = o.id; dragDy = 0f },
                                                onDragEnd = { dragId = null; dragDy = 0f },
                                                onDragCancel = { dragId = null; dragDy = 0f },
                                            ) { change, amount -> change.consume(); dragBy(amount.y) }
                                        }, contentAlignment = Alignment.Center) {
                                            Icon(ImageVector.cachedVector(R.drawable.ic_sf_line3), "Reorder", tint = c.secondary, modifier = Modifier.size(22.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (quiz) Footnote("Tap the circle next to the right answer.")
                Spacer(Modifier.height(20.dp))
                Group {
                    Setting("Multiple answers", multiple, enabled = !quiz, icon = R.drawable.ic_sf_checklist) { on ->
                        multiple = on
                        if (!on) { var seen = false; for (k in opts.indices) if (opts[k].right) { if (seen) opts[k] = opts[k].copy(right = false) else seen = true } }
                    }
                    SettingLine()
                    Setting("Hide who voted", hideVoters, icon = R.drawable.ic_eye_off) { hideVoters = it }
                    SettingLine()
                    Setting("Quiz", quiz, icon = R.drawable.ic_sf_graduationcap) { on -> quiz = on; if (on) multiple = false }
                    SettingLine()
                    Box {
                        Row(Modifier.fillMaxWidth().clickable { endsMenu = true }.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(ImageVector.cachedVector(R.drawable.ic_sf_timer), null, tint = c.secondary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(14.dp))
                            Text("Ends", fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                            Text(ends.label, fontSize = 17.sp, color = c.label)
                            Spacer(Modifier.width(4.dp))
                            Icon(ImageVector.cachedVector(R.drawable.ic_sf_chevron_up_down), null, tint = c.label, modifier = Modifier.size(14.dp))
                        }
                        GlassDropdown(expanded = endsMenu, onDismissRequest = { endsMenu = false }, shape = com.fariin.app.ui.components.FariinMenu.shape, border = com.fariin.app.ui.components.FariinMenu.border, containerColor = com.fariin.app.ui.components.FariinMenu.container) {
                            PollRules.EndChoice.entries.forEach { e ->
                                DropdownMenuItem(text = { Text(e.label) }, onClick = { ends = e; endsMenu = false })
                            }
                        }
                    }
                }
                Footnote(footer)
            }
        }
        // Our own photo sheet (never the system picker), photos only; one picture per option.
        pickFor?.let { id ->
            com.fariin.app.ui.stories.StoryLibrarySheet(busy = false, photosOnly = true, onClose = { pickFor = null }, onPick = { uri ->
                pickFor = null
                scope.launch {
                    val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pollPicOf(context, uri) }
                    if (p == null) Snack.show("That picture could not be read.")
                    else if (opts.any { it.id == id && it.text.isNotEmpty() }) setPic(id, p)
                }
            })
        }
        sendError?.let { e ->
            GlassAlert(onDismissRequest = { sendError = null }, title = { Text("Couldn't send the poll") },
                text = { Text(e) }, confirmButton = { TextButton({ sendError = null }) { Text("OK") } })
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t.uppercase(), fontSize = 13.sp, color = Fariin.colors.secondary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
}

@Composable
private fun Footnote(t: String) {
    Text(t, fontSize = 13.sp, color = Fariin.colors.secondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Fariin.colors.card).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center) { content() }
}

@Composable
private fun Field(value: String, hint: String, maxLines: Int, onChange: (String) -> Unit) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(hint, fontSize = 17.sp, color = c.secondary)
        BasicTextField(value, onChange, textStyle = TextStyle(fontSize = 17.sp, color = c.label), cursorBrush = SolidColor(c.accent),
            maxLines = maxLines, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
    }
}

/** The thin line between two settings, starting under the words (iOS inset separator). */
@Composable
private fun SettingLine() {
    Box(Modifier.fillMaxWidth().padding(start = 36.dp).height(0.5.dp).background(Fariin.colors.separator))
}

@Composable
private fun Setting(title: String, on: Boolean, enabled: Boolean = true, icon: Int? = null, onChange: (Boolean) -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // Each setting wears its iOS outline icon (owner 2026-11-14, iPhone screenshot).
        if (icon != null) {
            Icon(ImageVector.cachedVector(icon), null, tint = c.secondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(title, fontSize = 17.sp, color = if (enabled) c.label else c.secondary, modifier = Modifier.weight(1f))
        com.fariin.app.ui.components.FariinSwitch(checked = on, onCheckedChange = onChange, enabled = enabled)
    }
}
