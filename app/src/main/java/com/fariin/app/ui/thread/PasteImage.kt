package com.fariin.app.ui.thread

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.fariin.app.data.PasteImageRules
import com.fariin.app.media.MediaRules

/**
 * Pictures on the clipboard for the composer's Paste (iOS ChatComposerView pastedImages).
 * [hasPicture] reads only the clip's description, so opening the menu does not make Android
 * say the clipboard was read; [pictures] reads the clip itself, on the Paste tap.
 */
internal object PasteImage {
    private fun board(c: Context) = c.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /** The clip says it holds a picture (its words, if any, are only read on the tap). */
    fun hasPicture(c: Context): Boolean = runCatching {
        val d = board(c)?.primaryClipDescription ?: return false
        (0 until d.mimeTypeCount).any { PasteImageRules.accepts(d.getMimeType(it)) }
    }.getOrDefault(false)

    /**
     * The pictures to paste, at most [perPick], or null when the paste is words: no picture of a
     * kind we take, or real words beside it (an address alone does not count, as iOS).
     */
    fun pictures(c: Context, perPick: Int): List<Pair<Uri, String>>? = runCatching {
        val clip = board(c)?.primaryClip ?: return null
        val r = c.contentResolver
        val pics = mutableListOf<Pair<Uri, String>>()
        val words = StringBuilder()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            item.text?.let { words.append(it).append('\n') }
            val u = item.uri ?: continue
            val type = r.getType(u) ?: (0 until clip.description.mimeTypeCount)
                .map { clip.description.getMimeType(it) }.firstOrNull { it.startsWith("image/") }
            if (PasteImageRules.accepts(type)) pics += u to type!!
        }
        if (pics.isEmpty() || !PasteImageRules.pictureWins(words.toString())) null
        else pics.take(PasteImageRules.take(pics.size, perPick))
    }.getOrNull()
}

private object PastePictureKey

/**
 * The composer's Paste when the clipboard holds a picture (iOS paste(_:)): the field's own Paste
 * pastes only words and is not even offered for a picture alone, so ours takes its place. On the
 * tap a picture goes to [onPictures]; words beside it (not just an address) paste as words, through
 * the field's own Paste. Ctrl+V on a hardware keyboard does the same. Null [onPictures]: words only
 * (a first message to someone new, as iOS hides Paste there).
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.pastePictures(context: Context, onPictures: ((List<Pair<Uri, String>>) -> Unit)?): Modifier {
    if (onPictures == null) return this
    val fieldPaste = arrayOfNulls<TextContextMenuItem>(1)
    return this
        .filterTextContextMenuComponents { comp ->
            if (comp.key == TextContextMenuKeys.PasteKey && PasteImage.hasPicture(context)) {
                fieldPaste[0] = comp as? TextContextMenuItem
                false
            } else true
        }
        .appendTextContextMenuComponents {
            // Builders run before the filters, so this clears what an earlier menu kept.
            fieldPaste[0] = null
            if (PasteImage.hasPicture(context)) item(PastePictureKey, "Paste") { session ->
                val pics = PasteImage.pictures(context, MediaRules.MEDIA_PER_PICK)
                val words = fieldPaste[0]
                if (pics != null) { session.close(); onPictures(pics) }
                else if (words != null) words.onClick.invoke(session)
                else session.close()
            }
        }
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed || e.key != Key.V || !PasteImage.hasPicture(context)) false
            else PasteImage.pictures(context, MediaRules.MEDIA_PER_PICK)?.let { onPictures(it); true } ?: false
        }
}
