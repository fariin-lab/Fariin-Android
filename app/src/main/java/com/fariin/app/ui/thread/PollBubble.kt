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
import java.util.UUID
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
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
    val votes = if (ctx.channel) PollRules.channelVotes(m.pollCounts, m.pollVoters, m.pollTallyAt, mine, v.mineAt, ctx.me)
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
@Composable
internal fun PollBody(m: Message, poll: PollCard, ctx: PollContext, isMe: Boolean, width: Dp, meta: @Composable () -> Unit) {
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
        mark == PollRules.QuizMark.RIGHT -> green to ImageVector.vectorResource(R.drawable.ic_sf_checkmark)
        mark == PollRules.QuizMark.WRONG -> c.red to ImageVector.vectorResource(R.drawable.ic_sf_xmark)
        chosen -> (if (isMe) Color.White else c.blue) to ImageVector.vectorResource(R.drawable.ic_sf_checkmark)
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
                        Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), null, tint = Color(0xFF34C759), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
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

/**
 * Compose a new poll (iOS PollComposerSheet): a question, 2 to 10 options (typing into the last
 * one adds the next by itself), then Multiple answers, Hide who voted, Quiz (tap an option's circle
 * to mark it right; a quiz takes one answer) and an end time. [onSend] gets the poll's marker text,
 * which goes out sealed like any message.
 */
@Composable
internal fun PollComposer(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    val c = Fariin.colors
    var question by remember { mutableStateOf("") }
    val texts = remember { mutableStateListOf("", "") }
    val rights = remember { mutableStateListOf(false, false) }
    var multiple by remember { mutableStateOf(false) }
    var hideVoters by remember { mutableStateOf(false) }
    var quiz by remember { mutableStateOf(false) }
    var ends by remember { mutableStateOf(PollRules.EndChoice.NEVER) }
    var endsMenu by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    val canSend = PollRules.canSend(question, texts, quiz, rights)

    fun tidy() {
        val next = PollRules.tidyOptions(texts.toList())
        while (texts.size < next.size) { texts.add(""); rights.add(false) }
        while (texts.size > next.size) { texts.removeAt(texts.size - 1); rights.removeAt(rights.size - 1) }
    }
    fun send() {
        // One send per open: a second tap while the page goes must not send it twice (iOS audit).
        if (sent || !canSend) return
        sent = true
        onSend(PollRules.compose(UUID.randomUUID().toString().take(12).lowercase(), question, texts.toList(),
            multiple, hideVoters, quiz, rights.toList(), ends, System.currentTimeMillis().toDouble()))
        onDismiss()
    }
    val footer = buildList {
        if (hideVoters) add("Results show only the numbers, not names.")
        if (quiz) add("Voters see if they were right after they answer, and cannot change it.")
        if (ends != PollRules.EndChoice.NEVER) add("Voting stops automatically.")
    }.ifEmpty { listOf("Anyone in the chat can vote and see who voted.") }.joinToString(" ")

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        com.fariin.app.ui.components.AppPageDialogWindow()
        Column(Modifier.fillMaxSize().background(c.bgSecondary).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), "Close", tint = c.label) }
                Text("New Poll", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.weight(1f))
                TextButton(onClick = ::send, enabled = canSend) {
                    Text("Send", fontWeight = FontWeight.SemiBold, color = if (canSend) c.blue else c.secondary)
                }
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                SectionTitle("Question")
                Group {
                    Field(question, "Ask a question", maxLines = 3) { question = it }
                }
                Spacer(Modifier.height(20.dp))
                SectionTitle("Options")
                Group {
                    texts.forEachIndexed { i, t ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (quiz) {
                                val right = rights.getOrElse(i) { false }
                                Box(Modifier.size(24.dp).clip(CircleShape)
                                    .then(if (right) Modifier.background(Color(0xFF34C759)) else Modifier.border(1.5.dp, c.secondary, CircleShape))
                                    .clickable {
                                        // A quiz has one right answer.
                                        for (k in rights.indices) rights[k] = if (k == i) !rights[k] else false
                                    }, contentAlignment = Alignment.Center) {
                                    if (right) Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), "Right answer", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                            }
                            Box(Modifier.weight(1f)) {
                                Field(t, "Option ${i + 1}", maxLines = 2) { v -> texts[i] = v; tidy() }
                            }
                            // Remove only an option that has words, and never below two rows.
                            if (texts.size > 2 && t.isNotEmpty()) {
                                IconButton(onClick = { texts.removeAt(i); rights.removeAt(i); tidy() }, modifier = Modifier.size(36.dp)) {
                                    Icon(ImageVector.vectorResource(R.drawable.ic_sf_minus_circle_fill), "Remove option", tint = c.red, modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                }
                if (quiz) Footnote("Tap the circle next to the right answer.")
                Spacer(Modifier.height(20.dp))
                Group {
                    Setting("Multiple answers", multiple, enabled = !quiz) { on ->
                        multiple = on
                        if (!on) { var seen = false; for (k in rights.indices) if (rights[k]) { if (seen) rights[k] = false else seen = true } }
                    }
                    Setting("Hide who voted", hideVoters) { hideVoters = it }
                    Setting("Quiz", quiz) { on -> quiz = on; if (on) multiple = false }
                    Box {
                        Row(Modifier.fillMaxWidth().clickable { endsMenu = true }.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Ends", fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                            Text(ends.label, fontSize = 17.sp, color = c.secondary)
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

@Composable
private fun Setting(title: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, color = if (enabled) c.label else c.secondary, modifier = Modifier.weight(1f))
        com.fariin.app.ui.components.FariinSwitch(checked = on, onCheckedChange = onChange, enabled = enabled)
    }
}
