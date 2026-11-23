package com.fariin.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureShownRuleTest {
    @Test fun freeAlwaysDraws() {
        assertTrue(BubbleStyles.pictureShownRule(PlusStore.State.OFF) { false })
    }

    @Test fun plusNeedsTheAuthorsBadge() {
        assertFalse(BubbleStyles.pictureShownRule(PlusStore.State.LOCKED) { false })
        assertTrue(BubbleStyles.pictureShownRule(PlusStore.State.LOCKED) { true })
        assertFalse(BubbleStyles.pictureShownRule(PlusStore.State.ON) { false })
    }
}
