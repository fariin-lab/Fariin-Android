package com.fariin.app.ui.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bubble row modifier (combinedClickableLong in ThreadScreen.kt) on a tiny stand-in row:
 * the sender, the words and the time read as ONE item, that item offers the long press that
 * opens the message menu, and a play button inside stays its own item.
 */
@RunWith(AndroidJUnit4::class)
class BubbleRowSemanticsTest {

    @get:Rule
    val rule = createComposeRule()

    private var menuOpened = 0
    private var playTapped = 0

    private fun setRow() {
        rule.setContent {
            Column(Modifier.combinedClickableLong { menuOpened++ }) {
                Text("Amina")
                Text("See you at noon")
                Text("10:42")
                Box(
                    Modifier
                        .size(40.dp)
                        .semantics { contentDescription = "Play" }
                        .clickable { playTapped++ }
                )
            }
        }
    }

    private val row = hasText("See you at noon") and hasText("10:42") and hasText("Amina")

    @Test
    fun rowIsOneMergedItemWithWordsAndTime() {
        setRow()
        // One node carries all three texts, so the words are not separate items.
        rule.onAllNodes(row).assertCountEquals(1)
        rule.onAllNodesWithText("See you at noon").assertCountEquals(1)
    }

    @Test
    fun longPressActionOpensTheMenu() {
        setRow()
        val node = rule.onNode(row)
        node.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        node.performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForIdle()
        assertEquals(1, menuOpened)
        assertEquals(0, playTapped)
    }

    @Test
    fun playButtonStaysItsOwnItem() {
        setRow()
        // The row does not swallow the play button's label.
        rule.onNode(row).assert(!hasContentDescription("Play"))
        val play = rule.onNodeWithContentDescription("Play")
        play.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        play.performClick()
        rule.waitForIdle()
        assertEquals(1, playTapped)
        assertEquals(0, menuOpened)
    }
}
