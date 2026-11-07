package com.fariin.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.FariinSheetDialog
import com.fariin.app.ui.components.FariinSpinner
import com.fariin.app.ui.components.rememberSheetMotion
import com.fariin.app.ui.components.sheetDrag
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/**
 * One action of a [PersonConfirmSheet]. [run] does the work and answers true when it worked; the
 * button spins while it runs and the sheet slides away after. [tonal] = red words on a light red
 * (a second red choice, as Block and Report under Block).
 */
internal class PersonAction(val label: String, val tonal: Boolean = false, val run: suspend () -> Boolean)

/**
 * The contact page's careful questions (Report, Block, Unblock, Delete Chat, Clear my messages) as a
 * bottom sheet that fits its words (owner 2026-11-07: not a centred dialog): the person's photo and
 * name, the title, one line, full-width red buttons (a spinner while one works), then a plain Cancel.
 * Back, a tap on the dim or a swipe down leave everything as it was.
 */
@Composable
internal fun PersonConfirmSheet(
    photo: String?,
    name: String,
    title: String,
    body: String,
    actions: List<PersonAction>,
    onDismiss: () -> Unit,
) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val motion = rememberSheetMotion(onDismiss)
    var busy by remember { mutableStateOf<String?>(null) }
    // Rest at the content's own height: measured once laid out, then the sheet settles there.
    var contentH by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val windowH = LocalWindowInfo.current.containerSize.height
    val statusTop = WindowInsets.statusBars.getTop(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    LaunchedEffect(contentH) {
        if (contentH <= 0) return@LaunchedEffect
        motion.mid = with(density) { windowH - statusTop - 10.dp.toPx() - contentH - navBottom }.coerceAtLeast(0f)
        motion.open()
    }
    FariinSheetDialog(motion, fill = c.bgSecondary) { _ ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).onSizeChanged { if (contentH == 0) contentH = it.height }
                .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(photo, 72.dp)
                if (name.isNotEmpty()) Text(name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                    textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.padding(top = 10.dp))
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp))
                Text(body, fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Spacer(Modifier.height(22.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    actions.forEach { a ->
                        val (fill, ink) = if (a.tonal) c.red.copy(alpha = 0.12f) to c.red else c.red to Color.White
                        SheetPill(a.label, fill, ink, enabled = busy == null, spinning = busy == a.label) {
                            busy = a.label
                            scope.launch {
                                a.run()
                                busy = null
                                motion.close()
                            }
                        }
                    }
                    SheetPill("Cancel", Color.Transparent, c.label, enabled = busy == null, spinning = false) { motion.close() }
                }
            }
            // The top strip drags the sheet.
            Box(Modifier.fillMaxWidth().height(28.dp).sheetDrag(motion))
        }
    }
}

@Composable
private fun SheetPill(label: String, fill: Color, ink: Color, enabled: Boolean, spinning: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(CircleShape).background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (enabled || spinning) Modifier else Modifier.alpha(0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        if (spinning) FariinSpinner(size = 22.dp, color = ink)
        else Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ink, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
    }
}
