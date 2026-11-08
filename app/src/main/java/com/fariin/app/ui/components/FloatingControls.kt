package com.fariin.app.ui.components

import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.theme.Fariin

/*
 * The tab pages' floating chrome, built on [floating]: bar buttons in their own circles, the search
 * capsule and the segmented pill. iOS puts these in glass on its bars (ChatListTable, MainShell
 * Calls, StoriesTabView); the chat screen's Header is the Android reference.
 */

/**
 * One bar icon, 44 to touch. PLAIN on the page (owner 2026-11-04: "remove that white background");
 * [circle] keeps the floating circle where an icon sits on a photo or video. A [fill] colour always
 * draws its circle.
 */
@Composable
fun FloatingIcon(painter: Painter, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier,
                 tint: Color = Fariin.colors.label, fill: Color? = null, circle: Boolean = false) {
    IconButton(onClick = onClick, modifier = modifier.size(44.dp).then(if (circle || fill != null || glassBar()) Modifier.floating(fill = fill) else Modifier)) {
        Icon(painter, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun FloatingIcon(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier,
                 tint: Color = Fariin.colors.label, fill: Color? = null, circle: Boolean = false) =
    FloatingIcon(rememberVectorPainter(icon), contentDescription, onClick, modifier, tint, fill, circle)

/**
 * On a [GlassBarPage]'s bar with glass on: the bar's icons and back stand in glass circles, as the
 * chat header's do. Elsewhere they stay plain (owner 2026-11-04: no white circles on the pages).
 */
val LocalGlassBar = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
internal fun glassBar(): Boolean {
    if (!LocalGlassBar.current || LocalLiquidBackdrop.current == null) return false
    // A menu or sheet opened from the bar is its own window: there the buttons stay as they were.
    val window = LocalGlassWindow.current ?: return true
    return window.rootView === androidx.compose.ui.platform.LocalView.current.rootView
}

/** Back: the chevron, plain on the page; [circle] on a photo or video (see [FloatingIcon]). */
@Composable
fun FloatingBack(onBack: () -> Unit, modifier: Modifier = Modifier, contentDescription: String = "Back", circle: Boolean = false) {
    val c = Fariin.colors
    IconButton(onClick = onBack, modifier = modifier.size(44.dp).then(if (circle || glassBar()) Modifier.floating() else Modifier)) {
        Icon(ImageVector.vectorResource(R.drawable.ic_sf_chevron_left), contentDescription = contentDescription, tint = c.label,
            modifier = Modifier.padding(start = 6.dp).size(20.dp))
    }
}

/**
 * A tab or pushed page's bar: an optional floating back, the title at the start, the actions as
 * floating circles at the end (8 apart). Replaces the flat Material app bar on my pages.
 */
@Composable
fun FloatingTopBar(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null,
                   container: Color = Fariin.colors.bg, tabRoot: Boolean = false, actions: @Composable RowScope.() -> Unit = {}) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().background(container).height(64.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) { FloatingBack(onBack); Spacer(Modifier.width(12.dp)) } else Spacer(Modifier.width(4.dp))
        Box(Modifier.weight(1f)) {
            androidx.compose.foundation.layout.Column {
                // A tab's own title (Stories, Calls) wears the Chats tab's bold "Fariin" so the three
                // tabs read as one set; pushed pages keep the plain title.
                if (tabRoot) Text(title, fontSize = 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    letterSpacing = (-0.3).sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else Text(title, style = MaterialTheme.typography.titleLarge, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.secondary, maxLines = 1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * The search capsule (iOS `.searchable` field): a floating pill, the magnifier, the prompt, a clear
 * x once there is text. Pass `modifier` for focus or weight; the caller sets the outer padding.
 */
@Composable
fun FloatingSearchField(value: String, onChange: (String) -> Unit, prompt: String, modifier: Modifier = Modifier, fill: Color? = null,
                        /** The keyboard's Search key; null keeps the plain key. */
                        onSearch: (() -> Unit)? = null) {
    val c = Fariin.colors
    Row(
        // Every search field is a grey capsule (owner 2026-11-03: white on the white page was not seen).
        modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(fill ?: c.fieldFill)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ImageVector.vectorResource(R.drawable.ic_search_ios), contentDescription = null, tint = c.secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(prompt, fontSize = 17.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The field keeps its own cursor; when the words are set from outside ("Did you mean",
            // a link), the cursor goes to the end instead of staying where it was in the old words.
            var field by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(
                androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))) }
            if (field.text != value) field = androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
            BasicTextField(field, { v -> field = v; if (v.text != value) onChange(v.text) }, singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                cursorBrush = SolidColor(c.accent),
                // Screen readers: the prompt is drawn as a separate Text, so the field names itself.
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = prompt },
                keyboardOptions = if (onSearch != null) androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search) else androidx.compose.foundation.text.KeyboardOptions.Default,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch?.invoke() }))
        }
        if (value.isNotEmpty()) Box(Modifier.size(36.dp).clip(CircleShape).clickable { onChange("") }, contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), contentDescription = "Clear", tint = c.secondary, modifier = Modifier.size(18.dp))
        }
        if (value.isEmpty()) Spacer(Modifier.width(10.dp))
    }
}

/** A segmented pill (iOS Calls' All / Missed picker): a floating capsule, the chosen part filled blue. */
@Composable
fun FloatingSegments(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Fariin.colors
    Row(modifier.height(36.dp).floating(CircleShape).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.height(30.dp).clip(CircleShape).background(if (on) c.accent else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) c.onAccent else c.label)
            }
        }
    }
}

/** A page bar's word button (Edit, Save, Add, Next) in a floating capsule, as iOS's glass bar buttons. */
@Composable
fun FloatingTextAction(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, busy: Boolean = false, onClick: () -> Unit) {
    val c = Fariin.colors
    // Busy: the ring takes the word's place inside the same capsule (iOS), the capsule keeps its width.
    Box(modifier.padding(end = 4.dp).height(44.dp).floating(CircleShape)
        .clickable(enabled = enabled && !busy, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center) {
        BusyContent(busy, c.label, size = 20.dp) {
            androidx.compose.material3.Text(text, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = if (enabled) c.label else c.secondary)
        }
    }
}
