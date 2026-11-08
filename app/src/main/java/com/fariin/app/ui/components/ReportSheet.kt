package com.fariin.app.ui.components

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.ReportFlow
import com.fariin.app.data.ReportReason
import com.fariin.app.data.ReportRules
import com.fariin.app.data.ReportSender
import com.fariin.app.data.ReportSub
import com.fariin.app.data.ReportSubs
import com.fariin.app.data.ReportTarget
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Put once at the app's root: whatever screen asks [ReportFlow], the one report sheet opens. */
@Composable
fun ReportSheetHost(me: String) {
    val t by ReportFlow.current.collectAsState()
    t?.let { target -> key(target) { ReportSheet(target, me, onDismiss = { ReportFlow.done() }) } }
}

private const val P_REASONS = 0
private const val P_SUBS = 1
private const val P_CONFIRM = 2
private const val P_THANKS = 3

/**
 * The report sheet: the reasons, a reason's sub-reasons, the confirm page (note, Block, Leave, the
 * message share switch), then the thanks page with what can still be done (Block, Leave, Mute) and,
 * for the gravest reasons, a safety box. The pages slide inside one sheet that fits its content,
 * with the grabber and no X. Back in the sheet and the system Back go one page back; from the
 * reasons or the thanks they close.
 */
@Composable
private fun ReportSheet(t: ReportTarget, me: String, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val motion = rememberSheetMotion(onDismiss)
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(P_REASONS) }
    var reason by remember { mutableStateOf<ReportReason?>(null) }
    var sub by remember { mutableStateOf<ReportSub?>(null) }
    var note by remember { mutableStateOf("") }
    var block by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    var shareText by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val canBlock = ReportRules.canBlock(t, me)
    val animate = animationsOn()
    val density = LocalDensity.current
    // Each page's own height, so the sheet settles to the page now showing (not mid-slide sizes).
    val heights = remember { mutableStateMapOf<Int, Float>() }
    val guess = reportRestStop(560f)
    if (motion.mid <= 0f) motion.mid = guess
    heights[page]?.let { h -> val stop = reportRestStop(h); SideEffect { motion.fitMid(stop) } }

    fun back() {
        if (busy) return
        error = null
        page = when (page) {
            P_CONFIRM -> if (reason?.let { ReportSubs.of(it).isNotEmpty() } == true) P_SUBS else P_REASONS
            else -> P_REASONS
        }
    }

    fun submit() {
        val r = reason ?: return
        if (busy) return
        busy = true; error = null
        scope.launch {
            val blockNow = canBlock && block
            val refused = withContext(NonCancellable) {
                ReportSender.send(t, r, ReportRules.note(r, note), blockNow, sub, shareText)
            }
            busy = false
            if (refused == null) {
                t.onSent(blockNow)
                if (leave) t.onLeave?.invoke()
                page = P_THANKS
            } else error = refused
        }
    }

    FariinSheetDialog(motion, handle = true, fill = c.bgSecondary, typing = true) { _ ->
        BackHandler(enabled = page == P_SUBS || page == P_CONFIRM) { back() }
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize().imePadding()) {
            // A pull down while the page is at its top moves the sheet (the reasons list can be taller than the screen).
            Column(Modifier.fillMaxWidth().sheetDrag(motion, atTop = { scroll.value == 0 }).verticalScroll(scroll)) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        if (!animate) ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
                        else {
                            val forward = targetState > initialState
                            (slideInHorizontally(tween(260)) { w -> if (forward) w else -w } + fadeIn(tween(200)))
                                .togetherWith(slideOutHorizontally(tween(260)) { w -> if (forward) -w / 3 else w / 3 } + fadeOut(tween(160)))
                                .using(SizeTransform(clip = false))
                        }
                    },
                    label = "report-page",
                ) { p ->
                    Column(Modifier.fillMaxWidth()
                        .onSizeChanged { heights[p] = with(density) { it.height.toDp().value } }
                        .padding(top = SheetHandleStrip, bottom = 16.dp)) {
                        when (p) {
                            P_REASONS -> ReasonsPage(t) { picked ->
                                if (picked != reason) { note = ""; sub = null }
                                reason = picked; error = null
                                page = if (ReportSubs.of(picked).isEmpty()) P_CONFIRM else P_SUBS
                            }
                            P_SUBS -> SubsPage(reason ?: ReportReason.OTHER, onBack = { back() }) { picked ->
                                sub = picked; error = null; page = P_CONFIRM
                            }
                            P_CONFIRM -> ConfirmPage(t, reason ?: ReportReason.OTHER, sub, note, { note = it.take(ReportRules.NOTE_MAX) },
                                canBlock, block, { block = it }, leave, { leave = it }, shareText, { shareText = it }, busy, error,
                                onBack = { back() },
                                onTyping = { motion.expand() },
                                onSubmit = { submit() })
                            else -> ThanksPage(t, reason ?: ReportReason.OTHER, sub,
                                offerBlock = canBlock && !block, offerLeave = t.onLeave != null && !leave) { motion.close() }
                        }
                    }
                }
                // Clear of the navigation bar when a tall page opens the sheet to full height.
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

/** The sheet's top line: the title in the middle, Back at the start when there is a page behind. */
@Composable
private fun ReportBar(onBack: (() -> Unit)?) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp)) {
        if (onBack != null) Box(Modifier.align(Alignment.CenterStart).size(44.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_chevron_left), contentDescription = "Back", tint = c.label,
                modifier = Modifier.padding(start = 6.dp).size(20.dp))
        }
        Text("Report", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.align(Alignment.Center))
    }
}

/** One reason row: the words and a chevron. */
@Composable
private fun ChevronRow(label: String, onClick: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 50.dp)
        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
        Icon(ImageVector.vectorResource(R.drawable.ic_sf_chevron_right), contentDescription = null, tint = c.secondary.copy(alpha = 0.55f),
            modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun PageHeader(title: String, sub: String? = null) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.label)
        if (sub != null) Text(sub, fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(top = 6.dp))
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun ReasonsPage(t: ReportTarget, onPick: (ReportReason) -> Unit) {
    ReportBar(onBack = null)
    PageHeader(ReportRules.header(t.kind), ReportRules.privacy(t.name))
    ReportCard { ReportReason.entries.forEach { r -> ChevronRow(r.label) { onPick(r) } } }
}

@Composable
private fun SubsPage(reason: ReportReason, onBack: () -> Unit, onPick: (ReportSub) -> Unit) {
    ReportBar(onBack = onBack)
    PageHeader(ReportSubs.title(reason) ?: reason.label)
    ReportCard { ReportSubs.of(reason).forEach { s -> ChevronRow(s.label) { onPick(s) } } }
}

@Composable
private fun ConfirmPage(
    t: ReportTarget, reason: ReportReason, sub: ReportSub?, note: String, onNote: (String) -> Unit,
    canBlock: Boolean, block: Boolean, onBlock: (Boolean) -> Unit, leave: Boolean, onLeave: (Boolean) -> Unit,
    shareText: Boolean, onShareText: (Boolean) -> Unit, busy: Boolean, error: String?,
    onBack: () -> Unit, onTyping: () -> Unit, onSubmit: () -> Unit,
) {
    val c = Fariin.colors
    val other = reason == ReportReason.OTHER
    val ready = !other || note.isNotBlank()
    ReportBar(onBack = onBack)
    PageHeader(ReportRules.confirmTitle(t.kind))
    ReportCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Reason", fontSize = 13.sp, color = c.secondary)
            Text(ReportRules.reasonLine(reason, sub), fontSize = 17.sp, color = c.label, modifier = Modifier.padding(top = 2.dp))
        }
    }
    if (other) {
        ReportCard {
            Box(Modifier.fillMaxWidth().heightIn(min = 110.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (note.isEmpty()) Text("Tell us what's wrong", fontSize = 17.sp, color = c.tertiary)
                BasicTextField(note, onNote, enabled = !busy,
                    textStyle = TextStyle(fontSize = 17.sp, color = c.label), cursorBrush = SolidColor(c.label),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onTyping() })
            }
        }
        Text(ReportRules.counter(note), fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp))
    }
    // A single group message: its words go to the review team only with this on (contract v2.1).
    if (ReportRules.canShareText(t)) {
        ReportCard { SwitchRow(ReportRules.SHARE_TEXT_LABEL, shareText, busy, onShareText) }
        Text(ReportRules.SHARE_TEXT_NOTE, fontSize = 13.sp, color = c.secondary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp))
    }
    if (canBlock) ReportCard { SwitchRow(ReportRules.blockLabel(t.name), block, busy, onBlock) }
    // A member reporting a group or channel can leave it too (the old Report and Leave).
    if (t.onLeave != null) ReportCard { SwitchRow(ReportRules.leaveLabel(t), leave, busy, onLeave) }
    if (error != null) Text(error, fontSize = 15.sp, color = c.red, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp))
    Spacer(Modifier.height(18.dp))
    ReportPill(if (busy) null else "Submit report", enabled = ready && !busy, modifier = Modifier.padding(horizontal = 20.dp)) { onSubmit() }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, busy: Boolean, onChange: (Boolean) -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Switch) { onChange(!on) }.heightIn(min = 52.dp)
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        FariinSwitch(on, onCheckedChange = if (busy) null else onChange)
    }
}

/** A thanks-page action: the words, a spinner while it runs, then what it did in grey. */
private class AfterAction(val label: String, val doneLabel: String, val run: suspend () -> Boolean)

@Composable
private fun ThanksPage(t: ReportTarget, reason: ReportReason, sub: ReportSub?, offerBlock: Boolean, offerLeave: Boolean, onDone: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val actions = remember(offerBlock, offerLeave) {
        buildList {
            if (offerBlock) add(AfterAction(ReportRules.blockLabel(t.name), "Blocked") {
                ReportSender.block(t).also { if (it) t.onSent(true) }
            })
            if (offerLeave) add(AfterAction(ReportRules.leaveLabel(t), "Left") { t.onLeave?.invoke(); true })
            if (ReportRules.canMute(t)) add(AfterAction(ReportRules.muteLabel(t.name), "Muted") { ReportSender.mute(t) })
        }
    }
    val running = remember { mutableStateMapOf<String, Boolean>() }
    val done = remember { mutableStateMapOf<String, Boolean>() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Color(0xFF34C759)), contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
        }
        Text(ReportRules.THANKS_TITLE, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 18.dp))
        Text(ReportRules.THANKS_BODY, fontSize = 15.sp, color = c.secondary,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
    Spacer(Modifier.height(18.dp))
    ReportRules.safetyBox(reason, sub)?.let { box ->
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp))
            .background(c.red.copy(alpha = 0.10f)).padding(16.dp)) {
            Text(box, fontSize = 15.sp, color = c.label)
        }
    }
    if (actions.isNotEmpty()) ReportCard {
        actions.forEach { a ->
            val isDone = done[a.label] == true
            val isRunning = running[a.label] == true
            Row(Modifier.fillMaxWidth().clickable(enabled = !isDone && !isRunning, role = Role.Button) {
                running[a.label] = true
                scope.launch {
                    val ok = withContext(NonCancellable) { a.run() }
                    running[a.label] = false
                    if (ok) done[a.label] = true else Snack.show("Couldn't do that. Check your connection and try again.")
                }
            }.heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (isDone) a.doneLabel else a.label, fontSize = 17.sp, color = if (isDone) c.secondary else c.red,
                    modifier = Modifier.weight(1f))
                if (isRunning) FariinSpinner(size = 20.dp)
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    ReportPill("Done", enabled = true, modifier = Modifier.padding(horizontal = 20.dp), onClick = onDone)
}

@Composable
private fun ReportCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp)).background(Fariin.colors.card)) {
        content()
    }
}

/** The full-width main button; [label] null spins. */
@Composable
private fun ReportPill(label: String?, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Fariin.colors
    Box(modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(c.accent)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .then(if (enabled || label == null) Modifier else Modifier.alpha(0.45f)),
        contentAlignment = Alignment.Center) {
        if (label == null) FariinSpinner(size = 22.dp, color = c.onAccent)
        else Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
    }
}

/** The middle stop that leaves [heightDp] of sheet showing above the navigation bar. */
@Composable
private fun reportRestStop(heightDp: Float): Float {
    val density = LocalDensity.current
    val windowH = LocalWindowInfo.current.containerSize.height
    val statusTop = WindowInsets.statusBars.getTop(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    return with(density) { windowH - statusTop - 10.dp.toPx() - heightDp.dp.toPx() - navBottom }.coerceAtLeast(0f)
}
