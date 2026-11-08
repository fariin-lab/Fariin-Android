package com.fariin.app.ui.auth

import androidx.compose.ui.res.vectorResource
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.CodeEntry
import com.fariin.app.data.PasswordRules
import com.fariin.app.ui.theme.Fariin

/*
 * The entry flow's shared pieces, one look for every page (Kulan-iOS AuthFlowViews.swift
 * AuthPalette and the auth* pill modifiers, LoginCodeView CodeBoxes, PasswordView PasswordChecklist).
 */

internal val RedHint = Color(0xFFE5484D)
private val Orange = Color(0xFFF5A524)
private val BlueGood = Color(0xFF0A84FF)
internal val GreenOk = Color(0xFF30A46C)

@Composable
internal fun Logo(side: Int, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.launch_logo), contentDescription = "Fariin", modifier = modifier.size(side.dp))
}

@Composable
internal fun BackRow(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_chevron_left), contentDescription = "Back", tint = Fariin.colors.label)
        }
    }
}

@Composable
internal fun Busy(color: Color, side: Int = 22) {
    // The app's one ring (ui/components/FariinSpinner.kt); button size is iOS BusyArc.small.
    com.fariin.app.ui.components.FariinSpinner(size = side.dp, stroke = if (side <= 24) 2.5.dp else (side / 8f).dp, color = color)
}

/** authPrimaryPill: the Fariin-blue pill, white words, 50 high. */
@Composable
internal fun PrimaryPill(text: String, onClick: () -> Unit) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(c.accent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
    }
}

/** authActionPill: the email pages' main button, 54 high, softened until it can be pressed. Busy is not faded. */
@Composable
internal fun ActionPill(text: String, enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    val c = Fariin.colors
    val on = enabled || busy
    Box(
        Modifier.fillMaxWidth().height(54.dp).clip(CircleShape)
            .background(c.accent.copy(alpha = if (on) 1f else 0.35f))
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) Busy(c.onAccent) else Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            color = c.onAccent.copy(alpha = if (on) 1f else 0.85f))
    }
}

/** authRaisedPill: a sign-in door (a glass capsule on iOS, a soft grey one here). */
@Composable
internal fun RaisedPill(text: String, enabled: Boolean = true, busy: Boolean = false, lastUsed: Boolean = false,
                        icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val c = Fariin.colors
    Box {
        Row(
            Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(c.fieldFill)
                .clickable(enabled = enabled && !busy, onClick = onClick),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) Busy(c.label) else {
                if (icon != null) { icon(); Spacer(Modifier.width(10.dp)) }
                Text(text, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = c.label)
            }
        }
        if (lastUsed) LastUsedBadge(Modifier.align(Alignment.TopEnd))
    }
}

/** The dark door (the Apple button): black with white words on a light phone, the inverse on a dark one. */
@Composable
internal fun DoorPill(text: String, enabled: Boolean = true, busy: Boolean = false, lastUsed: Boolean = false,
                      icon: (@Composable (Color) -> Unit)? = null, onClick: () -> Unit) {
    val c = Fariin.colors
    Box {
        Row(
            Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(c.label)
                .clickable(enabled = enabled && !busy, onClick = onClick),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) Busy(c.bg) else {
                if (icon != null) { icon(c.bg); Spacer(Modifier.width(8.dp)) }
                Text(text, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = c.bg)
            }
        }
        if (lastUsed) LastUsedBadge(Modifier.align(Alignment.TopEnd))
    }
}

/** "Last used": the small badge half out of the door's top right corner. Says the method, never an address. */
@Composable
private fun LastUsedBadge(modifier: Modifier) {
    val c = Fariin.colors
    Text("Last used", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent,
        modifier = modifier.padding(end = 10.dp).offset(y = (-6).dp).clip(CircleShape).background(c.accent)
            .padding(horizontal = 8.dp, vertical = 3.dp))
}

/** authSecondaryPill: outlined, not filled. */
@Composable
internal fun SecondaryPill(text: String, height: Int = 50, strong: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Fariin.colors
    Box(
        Modifier.fillMaxWidth().height(height.dp).clip(CircleShape)
            .border(BorderStroke(1.5.dp, if (strong) c.label else c.label.copy(alpha = 0.22f)), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 17.sp, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Medium, color = c.label)
    }
}

/** "Don't have an account? Sign up" */
@Composable
internal fun SwapLine(question: String, action: String, onClick: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.clickable(onClick = onClick).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(question, fontSize = 15.sp, color = c.secondary)
        Spacer(Modifier.width(5.dp))
        Text(action, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
    }
}

@Composable
internal fun PageTitle(title: String, subtitle: String = "", size: Int = 30, top: Int = 20) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().padding(top = top.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = size.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center, lineHeight = (size + 6).sp)
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}

@Composable
internal fun ErrorLine(text: String?) {
    if (text == null) return
    Text(text, fontSize = 13.sp, color = Fariin.colors.red, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

/**
 * authField: the glyph inside, no label above, a hairline at rest, dark while focused, red after a
 * bad address. White (the page colour) with an edge, 56 high, 18 corners.
 */
@Composable
internal fun AuthField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    actions: KeyboardActions = KeyboardActions.Default,
    invalid: Boolean = false,
    secret: Boolean = false,
    reveal: Boolean = false,
    onReveal: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocus: (Boolean) -> Unit = {},
    accessory: (@Composable () -> Unit)? = null,
) {
    val c = Fariin.colors
    var focused by remember { mutableStateOf(false) }
    val edge = if (invalid) c.red else c.label.copy(alpha = if (focused) 0.75f else 0.18f)
    Row(
        modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(c.bg)
            .border(BorderStroke(if (focused || invalid) 1.2.dp else 1.dp, edge), RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.secondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, fontSize = 17.sp, color = c.secondary)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                cursorBrush = SolidColor(c.label),
                keyboardOptions = keyboard, keyboardActions = actions,
                visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth()
                    .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                    .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
                    .semantics { contentDescription = placeholder },
            )
        }
        if (onReveal != null) {
            IconButton(onClick = onReveal, modifier = Modifier.size(30.dp)) {
                Icon(if (reveal) ImageVector.vectorResource(com.fariin.app.R.drawable.ic_views_off) else ImageVector.vectorResource(com.fariin.app.R.drawable.ic_views),
                    contentDescription = if (reveal) "Hide password" else "Show password", tint = c.secondary, modifier = Modifier.size(18.dp))
            }
        }
        accessory?.invoke()
    }
}

internal val EmailKeyboard = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false)
internal fun passwordKeyboard(last: Boolean) = KeyboardOptions(keyboardType = KeyboardType.Password,
    imeAction = if (last) ImeAction.Go else ImeAction.Next, autoCorrectEnabled = false)

/** PasswordChecklist: four bars, the next hint at the left and the strength word at the right. */
@Composable
internal fun PasswordChecklist(rules: PasswordRules) {
    val c = Fariin.colors
    val empty = rules.password.isEmpty()
    val color = when (rules.level) { 1 -> RedHint; 2 -> Orange; 3 -> BlueGood; 4 -> GreenOk; else -> c.secondary }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(top = 2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(4) { i ->
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape)
                    .background(if (!empty && rules.level > 0 && i < rules.level) color else c.fieldFill))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().heightIn(min = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            rules.hint?.let { h ->
                if (h.ok) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), null, tint = GreenOk, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(5.dp)) }
                Text(h.text, fontSize = 13.sp, color = if (h.ok) c.label else if (h.bad) RedHint else c.secondary)
            }
            Spacer(Modifier.weight(1f))
            Text(rules.word, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

/**
 * CodeBoxes: six boxes over one hidden number field; the next box has the dark edge and a
 * blinking caret, red edges after a wrong code. Pasting a whole code fills it.
 */
@Composable
internal fun CodeBoxes(code: String, onChange: (String) -> Unit, invalid: Boolean, focusRequester: FocusRequester, autoFocus: Boolean = true) {
    val c = Fariin.colors
    var focused by remember { mutableStateOf(false) }
    val blink = rememberInfiniteTransition(label = "caret")
    val caretAlpha by blink.animateFloat(1f, 0f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "caretAlpha")
    LaunchedEffect(Unit) { if (autoFocus) try { focusRequester.requestFocus() } catch (_: Exception) {} }
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until CodeEntry.LENGTH) {
                val isNext = focused && CodeEntry.caretIndex(code) == i
                val edge = if (invalid) c.red else if (isNext) c.label.copy(alpha = 0.75f) else c.label.copy(alpha = 0.18f)
                Box(
                    Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(14.dp)).background(c.bg)
                        .border(BorderStroke(if (isNext || invalid) 1.4.dp else 1.dp, edge), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (i < code.length) Text(code[i].toString(), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                    else if (isNext) Box(Modifier.width(2.dp).height(26.dp).alpha(caretAlpha).background(c.label))
                }
            }
        }
        BasicTextField(
            value = code, onValueChange = { onChange(CodeEntry.clean(it)) }, singleLine = true,
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp), cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            modifier = Modifier.matchParentSize().alpha(0.01f).focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }.semantics { contentDescription = "Code" },
        )
        Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        })
    }
}

/** The plain text button under a pill ("Forgot password?", "Not now", "Use a different email"). */
@Composable
internal fun TextLink(text: String, strong: Boolean = true, enabled: Boolean = true, color: Color? = null, onClick: () -> Unit) {
    val c = Fariin.colors
    Text(text, fontSize = 15.sp, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
        color = color ?: if (!enabled) c.secondary else if (strong) c.label else c.secondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp))
}
