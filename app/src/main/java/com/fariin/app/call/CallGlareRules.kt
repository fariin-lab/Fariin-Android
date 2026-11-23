package com.fariin.app.call

/**
 * A new 1:1 ring that lands while this phone already has a call (iOS settleAgainstCurrentCall).
 * Pure, for the tests.
 */
object CallGlareRules {
    enum class Verdict {
        /** Free: ring it. */
        RING,
        /** An older leftover doc from the person I am already with: end it as missed, leave my call. */
        END_OLD,
        /** The same person again while I am with them (their side died, or they redialled a ring): drop mine, ring theirs. */
        REPLACE,
        /** We dialled each other and my uid is lower: busy theirs, marked as glare. */
        GLARE_WIN,
        /**
         * I win, but my create is still in flight: their busy is held until my doc exists, and
         * if my create fails or my dial ends first, theirs rings here (iOS pendingGlareBusy).
         */
        GLARE_WIN_HOLD,
        /** We dialled each other and theirs wins (or my dial is not out yet): my dial stands down quietly, theirs rings. */
        GLARE_YIELD,
        /** In another call: busy. */
        BUSY,
    }

    /**
     * [newer]: the ring was made after my current call started. [myDocCreated]: my own dial's doc
     * exists. [myCreateStarted]: its create write has gone out (iOS dialCreateStarted); before
     * that the other phone can never see my dial, so it yields (iOS M-112).
     *
     * Why one call always survives: the higher uid always yields. The lower uid yields only when
     * its create has not gone out, and then the higher phone can not have seen its dial, so the
     * higher phone's call rings on there.
     */
    fun decide(phase: CallPhase, accepted: Boolean, inGroupCall: Boolean, me: String, peer: String, caller: String,
               newer: Boolean, myDocCreated: Boolean, myCreateStarted: Boolean = myDocCreated): Verdict {
        val same = caller.isNotEmpty() && caller == peer
        val live = phase == CallPhase.OUTGOING || phase == CallPhase.INCOMING || phase == CallPhase.ACTIVE || phase == CallPhase.RECONNECTING
        if (same && !newer && (phase == CallPhase.INCOMING || phase == CallPhase.ACTIVE || phase == CallPhase.RECONNECTING)) return Verdict.END_OLD
        if (same && (phase == CallPhase.ACTIVE || phase == CallPhase.RECONNECTING)) return Verdict.REPLACE
        if (same && phase == CallPhase.INCOMING && !accepted) return Verdict.REPLACE
        if (!live && !inGroupCall) return Verdict.RING
        if (same && phase == CallPhase.OUTGOING && !inGroupCall) {
            if (me.isEmpty() || me >= caller) return Verdict.GLARE_YIELD
            return when {
                myDocCreated -> Verdict.GLARE_WIN
                myCreateStarted -> Verdict.GLARE_WIN_HOLD
                else -> Verdict.GLARE_YIELD
            }
        }
        return Verdict.BUSY
    }
}
