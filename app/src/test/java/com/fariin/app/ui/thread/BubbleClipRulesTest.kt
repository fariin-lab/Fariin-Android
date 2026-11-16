package com.fariin.app.ui.thread

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleClipRulesTest {
    @Test fun androidTenAndOlderClipInTheBubblesOwnDrawing() {
        for (sdk in 21..29) assertTrue("sdk $sdk", BubbleClipRules.clipInDraw(sdk))
    }

    @Test fun androidElevenAndNewerKeepTheLayerClip() {
        for (sdk in 30..36) assertFalse("sdk $sdk", BubbleClipRules.clipInDraw(sdk))
    }
}
