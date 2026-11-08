package com.fariin.app.ui.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
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

/**
 * The report sheet: the reasons (page 0), the confirm page with the note and Block (page 1), and
 * the thanks (page 2), sliding inside one sheet that fits its content, with the grabber and no X.
 * Back in the sheet and the system Back go one page back; from the reasons or the thanks they close.
 */
@Composable
private fun ReportSheet(t: ReportTarget, me: String, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val motion = rememberSheetMotion(onDismiss)
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(0) }
    var reason by remember { mutableStateOf<ReportReason?>(null) }
    var note by remember { mutableStateOf("") }
    var block by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
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

    fun submit() {
        val r = reason ?: return
        if (busy) return
        busy = true; error = null
        scope.launch {
            val refused = withContext(NonCancellable) {
                ReportSender.send(t, r, ReportRules.note(r, note), canBlock && block)
            }
            busy = false
            if (refused == null) {
                t.onSent(canBlock && block)
                if (leave) t.onLeave?.invoke()
                page = 2
            } else error = refused
        }
    }

    FariinSheetDialog(motion, handle = true, fill = c.bgSecondary, typing = true) { _ ->
        BackHandler(enabled = page == 1) { if (!busy) { page = 0; error = null } }
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
                            0 -> ReasonsPage(t) { picked ->
                                if (picked != reason) note = ""
                                reason = picked; error = null; page = 1
                            }
                            1 -> ConfirmPage(t, reason ?: ReportReason.OTHER, note, { note = it.take(ReportRules.NOTE_MAX) },
                                canBlock, block, { block = it }, leave, { leave = it }, busy, error,
                                onBack = { if (!busy) { page = 0; error = null } },
                                onTyping = { motion.expand() },
                                onSubmit = { submit() })
                            else -> ThanksPage { motion.close() }
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
            Icon(Icons.AutoMirrored.Filled.ArrowBackIos, contentDescription = "Back", tint = c.label,
                modifier = Modifier.padding(start = 6.dp).size(20.dp))
        }
        Text("Report", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun ReasonsPage(t: ReportTarget, onPick: (ReportReason) -> Unit) {
    val c = Fariin.colors
    ReportBar(onBack = null)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp)) {
        Text(ReportRules.header(t.kind), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.label)
        Text(ReportRules.privacy(t.name), fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(top = 6.dp))
    }
    Spacer(Modifier.height(14.dp))
    ReportCard {
        ReportReason.entries.forEach { r ->
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(r) }.heightIn(min = 50.dp)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.label, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.secondary.copy(alpha = 0.55f),
                    modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun ConfirmPage(
    t: ReportTarget, reason: ReportReason, note: String, onNote: (String) -> Unit,
    canBlock: Boolean, block: Boolean, onBlock: (Boolean) -> Unit, leave: Boolean, onLeave: (Boolean) -> Unit,
    busy: Boolean, error: String?,
    onBack: () -> Unit, onTyping: () -> Unit, onSubmit: () -> Unit,
) {
    val c = Fariin.colors
    val other = reason == ReportReason.OTHER
    val ready = !other || note.isNotBlank()
    ReportBar(onBack = onBack)
    Text(ReportRules.confirmTitle(t.kind), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.label,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp))
    Spacer(Modifier.height(14.dp))
    ReportCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Reason", fontSize = 13.sp, color = c.secondary)
            Text(reason.label, fontSize = 17.sp, color = c.label, modifier = Modifier.padding(top = 2.dp))
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
        Text(label, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
        FariinSwitch(on, onCheckedChange = if (busy) null else onChange)
    }
}

@Composable
private fun ThanksPage(onDone: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Color(0xFF34C759)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
        }
        Text("Thanks for letting us know", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 18.dp))
        Text("We'll review it. If it breaks our rules, we'll take it down.", fontSize = 15.sp, color = c.secondary,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
    }
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
