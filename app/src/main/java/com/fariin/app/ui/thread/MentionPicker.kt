package com.fariin.app.ui.thread

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fariin.app.data.MentionRules
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin

/** iOS BubbleText: each "@Name" of a message's mentions semibold, in the bubble's own colour. */
internal fun mentionMarked(s: AnnotatedString, bold: List<IntRange>): AnnotatedString {
    if (bold.isEmpty()) return s
    return AnnotatedString.Builder(s).apply {
        for (r in bold) if (r.last < s.length) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), r.first, r.last + 1)
    }.toAnnotatedString()
}

/** Who can be @-mentioned in this group: its members (iOS groupMembers), their names and photos. */
data class MentionSource(
    val members: List<String>,
    val me: String,
    val names: Map<String, String>,
    val photos: Map<String, String>,
)

/**
 * iOS ThreadView.mentionPicker: while an "@" is typed just before the cursor, up to five members
 * whose names hold what follows it, each a 30 pt picture and the name; a tap puts "@Name " in
 * place. The rounded floating card of the chat's other controls. System back closes it first
 * (it comes back on the next keystroke).
 */
@Composable
internal fun MentionPicker(src: MentionSource, box: TextFieldValue, onPick: (TextFieldValue, String, String) -> Unit) {
    val caret = box.selection.end
    val head = MentionRules.head(box.text, caret)
    val q = if (box.selection.collapsed) MentionRules.query(head) else null
    var closedAt by remember { mutableStateOf<String?>(null) }
    if (q == null || closedAt == head) return
    val people = remember(src, q) { MentionRules.candidates(src.members, src.me, src.names, q).take(MentionRules.MAX_ROWS) }
    if (people.isEmpty()) return
    BackHandler { closedAt = head }
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).floating(RoundedCornerShape(16.dp))) {
        for (p in people) {
            Row(
                Modifier.fillMaxWidth().clickable {
                    MentionRules.insert(box.text, caret, p.name)?.let { (t, at) ->
                        onPick(TextFieldValue(t, TextRange(at)), p.uid, p.name)
                    }
                }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(src.photos[p.uid], 30.dp)
                Spacer(Modifier.width(10.dp))
                Text(p.name, style = MaterialTheme.typography.bodyLarge, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
