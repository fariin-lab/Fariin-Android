package com.fariin.app.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.fariin.app.data.AwakeReasons
import java.lang.ref.WeakReference

/**
 * The one owner of the keep-screen-on window flag (iOS SleepBlocker). Voice playback, voice
 * recording and the chat transfer each hold their own reason; the flag goes off only when the
 * last one is released. Main thread only (Compose effects).
 */
object ScreenAwake {
    const val VOICE_PLAY = "voice-play"
    const val VOICE_RECORD = "voice-record"
    const val CHAT_TRANSFER = "chat-transfer"

    private val reasons = AwakeReasons()
    private val windows = mutableListOf<WeakReference<Window>>()

    fun hold(reason: String, view: View) {
        reasons.hold(reason)
        val w = windowOf(view) ?: return
        if (windows.none { it.get() === w }) windows.add(WeakReference(w))
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun release(reason: String) {
        if (!reasons.release(reason)) return
        windows.forEach { it.get()?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        windows.clear()
    }

    private fun windowOf(view: View): Window? {
        var c: Context? = view.context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        return (c as? Activity)?.window
    }
}
