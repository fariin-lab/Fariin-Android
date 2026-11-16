package com.fariin.app.perf

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * One background thread for the heavy app-wide listeners (stories, channel documents, activity,
 * block list, poll votes, ...). Built the way ThreadRepository's listenExec is: a single thread, so
 * every listener's answers still arrive in the order the database sent them, but the parsing no longer
 * runs on the main thread where it made every tab stutter on a 2-core phone.
 *
 * A separate thread from the open chat's own listener thread, so a big stories answer never holds up
 * a message landing in the chat on screen.
 *
 * Rules for a callback that runs here: a StateFlow may be set straight away (it is safe from any
 * thread); Compose snapshot state and anything else the UI reads without a lock goes through [toMain].
 */
object ListenThread {
    val exec: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fariin-app-listen").apply { isDaemon = true } }

    /** The same thread as a coroutine dispatcher, for a flow whose listeners use [exec] (flowOn). */
    val dispatcher by lazy { exec.asCoroutineDispatcher() }

    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun toMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun later(ms: Long, block: () -> Unit) { exec.schedule(block, ms, TimeUnit.MILLISECONDS) }
}

/**
 * Leading and trailing throttle, pure so it is unit tested. The first change after a quiet spell is
 * published at once (no added wait on the first frame); changes inside [windowMs] after a publish are
 * held and published together when the window ends. Not thread safe: use it from one thread.
 */
class Throttle(private val windowMs: Long) {
    private var lastRun = Long.MIN_VALUE / 2
    private var scheduled = false

    /** A change came in at [now]. Returns 0 = publish now, > 0 = publish after that many ms,
     *  -1 = a publish is already on its way and will carry this change. */
    fun offer(now: Long): Long {
        if (scheduled) return -1
        scheduled = true
        return maxOf(0L, lastRun + windowMs - now)
    }

    /** The publish ran at [now]. */
    fun ran(now: Long) { lastRun = now; scheduled = false }

    /** Forget everything (sign-out). */
    fun reset() { lastRun = Long.MIN_VALUE / 2; scheduled = false }
}
