package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LoadFailureTest {
    @Test fun offlinePhoneSaysNoConnection() {
        assertEquals(LoadFailure.OFFLINE, LoadFailure.reason(offline = true, code = "PERMISSION_DENIED"))
    }

    @Test fun unreachableServerSaysNoConnection() {
        assertEquals(LoadFailure.OFFLINE, LoadFailure.reason(offline = false, code = "UNAVAILABLE"))
        assertEquals(LoadFailure.OFFLINE, LoadFailure.reason(offline = false, code = "DEADLINE_EXCEEDED"))
    }

    @Test fun anythingElseIsTheOtherReason() {
        assertEquals(LoadFailure.OTHER, LoadFailure.reason(offline = false, code = "PERMISSION_DENIED"))
        assertEquals(LoadFailure.OTHER, LoadFailure.reason(offline = false, code = null))
    }
}
