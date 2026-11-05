package com.fariin.app

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A refused or failed server call (Firestore, Functions, Storage, the network) that some screen's
 * coroutine forgot to catch must not close the app: that one action stops, the app carries on and
 * the error goes to Crashlytics as non-fatal. Mature messengers never crash on a "permission denied".
 *
 * How: kotlinx.coroutines calls every CoroutineExceptionHandler listed under META-INF/services before
 * it hands an uncaught coroutine error to the thread's handler BY A DIRECT CALL (no stack unwound,
 * the main looper keeps running). This handler marks such errors; [install] wraps the default
 * uncaught handler and skips the marked ones. Real crashes (anything else, or a server error thrown
 * outside a coroutine, where the looper is already gone) still crash and are reported as before.
 */
class CoroutineSafety : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {
    override fun handleException(context: CoroutineContext, exception: Throwable) {
        if (isServerFailure(exception)) marked.set(exception)
    }

    companion object {
        private val marked = ThreadLocal<Throwable?>()

        fun isServerFailure(e: Throwable): Boolean {
            var t: Throwable? = e
            var depth = 0
            while (t != null && depth++ < 8) {
                if (t is com.google.firebase.FirebaseException || t is java.io.IOException) return true
                t = t.cause
            }
            return false
        }

        /** Called once from FariinApp.onCreate, after Crashlytics has put its own handler in place. */
        fun install() {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, e ->
                if (marked.get() === e) {
                    marked.set(null)
                    android.util.Log.w("CoroutineSafety", "server call failed, app kept running", e)
                    runCatching { com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(e) }
                } else {
                    previous?.uncaughtException(thread, e)
                }
            }
        }
    }
}
