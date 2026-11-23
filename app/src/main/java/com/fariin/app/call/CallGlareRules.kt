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
        /** We dialled each other and theirs wins (or my dial is not out yet): my dial stands down quietly, theirs rings. */
        GLARE_YIELD,
        /** In another call: busy. */
        BUSY,
    }

    /**
     * [newer]: the ring was made after my current call started. [myDocCreated]: my own dial's doc
     * exists, so the other phone can see it (iOS M-112: before that my dial yields).
     */
    fun decide(phase: CallPhase, accepted: Boolean, inGroupCall: Boolean, me: String, peer: String, caller: String,
               newer: Boolean, myDocCreated: Boolean): Verdict {
        val same = caller.isNotEmpty() && caller == peer
        val live = phase == CallPhase.OUTGOING || phase == CallPhase.INCOMING || phase == CallPhase.ACTIVE || phase == CallPhase.RECONNECTING
        if (same && !newer && (phase == CallPhase.INCOMING || phase == CallPhase.ACTIVE || phase == CallPhase.RECONNECTING)) return Verdict.END_OLD
        if (same && (phase == CallPhase.ACTIVE || phase == CallPhase.RECONNECTING)) return Verdict.REPLACE
        if (same && phase == CallPhase.INCOMING && !accepted) return Verdict.REPLACE
        if (!live && !inGroupCall) return Verdict.RING
        if (same && phase == CallPhase.OUTGOING && !inGroupCall) {
            return if (me.isNotEmpty() && me < caller && myDocCreated) Verdict.GLARE_WIN else Verdict.GLARE_YIELD
        }
        return Verdict.BUSY
    }
}
