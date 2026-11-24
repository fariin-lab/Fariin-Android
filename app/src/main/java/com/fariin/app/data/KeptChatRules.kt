package com.fariin.app.data

/**
 * The chat kept under the pages (MainActivity's KeptChatLayer). A chat closed with Back stays held
 * until its slide away lands; it is drawn only while that slide plays. Still and out of the back
 * stack, it is not drawn at all: one left drawn sat over the chat list with a cover that ate every
 * tap, and the phone's Back then closed the app from a stack of only "main" (tester after sign-in,
 * 2026-11-23).
 */
object KeptChatRules {
    /** A page change still "moving" after this long is stuck and is landed (hold 200 + slide 320 ms). */
    const val STUCK_MS = 1500L

    /** Whether a held chat is composed at all. */
    fun composed(inStack: Boolean, moving: Boolean): Boolean = inStack || moving
}
