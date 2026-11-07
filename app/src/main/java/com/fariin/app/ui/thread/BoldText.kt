package com.fariin.app.ui.thread

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.fariin.app.data.TextFormat

/** A channel post's bold runs (TextFormat) in the bold weight, same size, on top of the marked text. */
internal fun boldMarked(s: AnnotatedString, bold: List<IntRange>): AnnotatedString {
    if (bold.isEmpty()) return s
    return AnnotatedString.Builder(s).apply {
        for (r in bold) if (r.first >= 0 && r.last < s.length) addStyle(SpanStyle(fontWeight = FontWeight.Bold), r.first, r.last + 1)
    }.toAnnotatedString()
}

/** The composer draws its bold runs where they are; the text and the cursor positions are unchanged. */
internal data class BoldRunsTransformation(val spans: List<TextFormat.Span>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val runs = TextFormat.ranges(TextFormat.normalize(spans, text.length))
        return TransformedText(boldMarked(text, runs), OffsetMapping.Identity)
    }
}

private object BoldMenuKey

/**
 * "Bold" in the text field's own menu, after Cut/Copy/Paste. Compose 1.9 (the version this app
 * resolves) draws the field's menu itself and no longer asks LocalTextToolbar, so the toolbar
 * below alone never showed Bold on the phone (2026-11-07). Shown only over a selection.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun androidx.compose.ui.Modifier.boldMenuItem(
    enabled: Boolean,
    hasSelection: () -> Boolean,
    onBold: () -> Unit,
): androidx.compose.ui.Modifier = if (!enabled) this else
    androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents(this) {
        if (hasSelection()) {
            separator()
            item(BoldMenuKey, "Bold") { onBold(); close() }
        }
    }

/**
 * The composer's selection toolbar with "Bold" after the usual items (iOS: Format > Bold in the
 * text menu). Android's floating action mode, as Compose's own toolbar uses, with one more item;
 * Bold shows only over a selection (Compose offers Copy only then).
 */
internal class BoldTextToolbar(private val view: View) : TextToolbar {
    /** Set by the composer on every pass: bolds or un-bolds the current selection. */
    var onBold: () -> Unit = {}

    private var mode: ActionMode? = null
    private var rect: Rect = Rect.Zero
    private var copy: (() -> Unit)? = null
    private var paste: (() -> Unit)? = null
    private var cut: (() -> Unit)? = null
    private var selectAll: (() -> Unit)? = null

    private var shown: TextToolbarStatus = TextToolbarStatus.Hidden
    override val status: TextToolbarStatus get() = shown

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        this.rect = rect
        copy = onCopyRequested; paste = onPasteRequested; cut = onCutRequested; selectAll = onSelectAllRequested
        val m = mode
        if (m == null) {
            shown = TextToolbarStatus.Shown
            mode = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
        } else m.invalidate()
    }

    override fun hide() {
        shown = TextToolbarStatus.Hidden
        mode?.finish()
        mode = null
    }

    private fun fill(menu: Menu) {
        menu.clear()
        var order = 0
        cut?.let { menu.add(0, ID_CUT, order++, android.R.string.cut).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS) }
        copy?.let { menu.add(0, ID_COPY, order++, android.R.string.copy).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS) }
        paste?.let { menu.add(0, ID_PASTE, order++, android.R.string.paste).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS) }
        selectAll?.let { menu.add(0, ID_SELECT_ALL, order++, android.R.string.selectAll).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM) }
        if (copy != null) menu.add(0, ID_BOLD, order, "Bold").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    private val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { fill(menu); return true }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean { fill(menu); return true }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                ID_COPY -> copy?.invoke()
                ID_PASTE -> paste?.invoke()
                ID_CUT -> cut?.invoke()
                ID_SELECT_ALL -> selectAll?.invoke()
                ID_BOLD -> onBold()
                else -> return false
            }
            // Select All keeps the menu up (Compose shows it again for the new selection).
            if (item.itemId != ID_SELECT_ALL && this@BoldTextToolbar.mode === mode) hide()
            return true
        }
        override fun onDestroyActionMode(mode: ActionMode) {
            if (this@BoldTextToolbar.mode === mode) { this@BoldTextToolbar.mode = null; shown = TextToolbarStatus.Hidden }
        }
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
            outRect.set(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        }
    }

    private companion object {
        const val ID_COPY = 1
        const val ID_PASTE = 2
        const val ID_CUT = 3
        const val ID_SELECT_ALL = 4
        const val ID_BOLD = 5
    }
}
