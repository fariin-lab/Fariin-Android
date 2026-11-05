package com.fariin.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SwitchColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fariin.app.ui.theme.Fariin

/** iOS's green when on, whatever the accent (KulanApp SystemGreenSwitch). */
private val SwitchGreen = Color(0xFF34C759)

/**
 * The iOS switch (owner: the iOS look everywhere): 51 x 31, green track when on, grey when off, a
 * white knob with a soft shadow, no outline. Takes the same arguments as Material's Switch so a
 * call site only changes its name; `colors` is accepted and ignored. A null onCheckedChange makes
 * it a picture of the state, the row around it taking the tap.
 */
@Composable
fun FariinSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") colors: SwitchColors? = null,
) {
    val c = Fariin.colors
    val track by animateColorAsState(if (checked) SwitchGreen else c.label.copy(alpha = 0.16f), label = "switchTrack")
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, spring(dampingRatio = 0.75f, stiffness = 600f), label = "switchKnob")
    val tap = if (onCheckedChange != null)
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange,
            // No ripple: it drew a grey square around the round switch on every flip (owner 2026-11-04).
            interactionSource = null, indication = null) else Modifier
    Box(modifier.then(tap).padding(4.dp).alpha(if (enabled) 1f else 0.45f), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.size(width = 51.dp, height = 31.dp).clip(CircleShape).background(track))
        Box(Modifier.offset(x = x).size(27.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White))
    }
}
