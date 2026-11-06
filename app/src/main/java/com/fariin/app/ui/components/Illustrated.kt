package com.fariin.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/*
 * Fariin's friendly pages: a big line drawing in a soft blue circle, a short bold title, one plain
 * sentence, one blue round button and maybe a quiet text button under it. Two shapes share it:
 * [IllustratedSheet] (asks for something: notifications, App Lock) and [IllustratedEmpty] (a list
 * with nothing in it yet). The drawings are [LineArt], drawn here in strokes only, so they tint.
 * [CarefulSheet] stays the sheet for steps that are hard to take back.
 */

/** The line drawings: 48 x 48, round strokes, tinted by whoever draws them. */
object LineArt {
    private fun art(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 48.dp, 48.dp, 48f, 48f).apply {
            for (d in paths) addPath(
                addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2.4f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    val Bell: ImageVector by lazy {
        art("bell", "M24 9v3", "M15 33V22a9 9 0 0 1 18 0v11", "M11 33h26", "M20.5 37.5a3.5 3.5 0 0 0 7 0")
    }
    val Lock: ImageVector by lazy {
        art("lock", "M16 21v-5a8 8 0 0 1 16 0v5",
            "M14 21h20a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H14a3 3 0 0 1-3-3V24a3 3 0 0 1 3-3z", "M24 28v4")
    }
    val Blocked: ImageVector by lazy {
        art("blocked", "M13 17a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0-13 0", "M7 38c0-6.5 5.5-11 12.5-11c1.8 0 3.4 0.3 4.8 0.8",
            "M27 34a7 7 0 1 0 14 0a7 7 0 1 0-14 0", "M29.1 29.1l9.8 9.8")
    }
    val Archive: ImageVector by lazy {
        art("archive", "M9 11h30v8H9z", "M11 19v16a3 3 0 0 0 3 3h20a3 3 0 0 0 3-3V19", "M20 26h8")
    }
    val People: ImageVector by lazy {
        art("people", "M12.5 18a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0-13 0", "M7 38c0-6.5 5.4-11 12-11s12 4.5 12 11",
            "M29.5 11.6a5.5 5.5 0 1 1 0 10.3", "M34 27.5c4.2 1.2 7 4.8 7 9.5")
    }
    val Phone: ImageVector by lazy {
        art("phone", "M17 9h-4a3 3 0 0 0-3 3c0 14.4 11.6 26 26 26a3 3 0 0 0 3-3v-4l-7-3l-3.5 3.5c-4-2-7-5-9-9L23 19z")
    }
}

/** The drawing in its soft blue circle; pops in gently unless Power Saving has animations off. */
@Composable
fun LineArtBadge(art: ImageVector, size: Dp = 96.dp) {
    val c = Fariin.colors
    val motion = animationsOn()
    val scale = remember { Animatable(if (motion) 0.8f else 1f) }
    LaunchedEffect(Unit) { if (motion) scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) }
    Box(
        Modifier.size(size).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(CircleShape).background(c.blue.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) { Icon(art, contentDescription = null, tint = c.blue, modifier = Modifier.size(size * 0.56f)) }
}

/**
 * A friendly ask. Each choice slides the sheet away first, then runs. [onDismiss] = gone without a
 * choice (swiped down, Back). The caller stops showing the sheet in every one of the three.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IllustratedSheet(
    art: ImageVector,
    title: String,
    body: String,
    primary: String,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit,
    secondary: String? = null,
    onSecondary: () -> Unit = onDismiss,
) {
    val c = Fariin.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) { scope.launch { state.hide() }.invokeOnCompletion { then() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), containerColor = c.bgSecondary,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(c.tertiary))
        },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LineArtBadge(art)
            Spacer(Modifier.height(20.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(body, fontSize = 15.sp, lineHeight = 21.sp, color = c.secondary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(26.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(CircleShape).background(c.blue)
                    .clickable(role = Role.Button) { close(onPrimary) },
                contentAlignment = Alignment.Center,
            ) { Text(primary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
            if (secondary != null) {
                Box(
                    Modifier.padding(top = 4.dp).fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape)
                        .clickable(role = Role.Button) { close(onSecondary) },
                    contentAlignment = Alignment.Center,
                ) { Text(secondary, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.secondary) }
            } else Spacer(Modifier.height(8.dp))
        }
    }
}

/** A list with nothing in it yet: the drawing, the title, one sentence, and the next step if there is one. */
@Composable
fun IllustratedEmpty(
    art: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val c = Fariin.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LineArtBadge(art, 84.dp)
        Spacer(Modifier.height(18.dp))
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, fontSize = 15.sp, lineHeight = 21.sp, color = c.secondary, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.heightIn(min = 46.dp).clip(CircleShape).background(c.blue)
                    .clickable(role = Role.Button, onClick = onAction).padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) { Text(action, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
        }
    }
}
