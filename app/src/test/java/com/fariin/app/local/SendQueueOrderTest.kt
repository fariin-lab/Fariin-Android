package com.fariin.app.local

import org.junit.Assert.assertEquals
import org.junit.Test

class SendQueueOrderTest {
    private fun e(id: String, ts: Double) = SendQueue.Entry(clientId = id, cid = "c", text = id, ts = ts)

    @Test fun oldestFirst() {
        val got = SendQueue.drainOrder(listOf(e("b", 2.0), e("c", 3.0), e("a", 1.0)))
        assertEquals(listOf("a", "b", "c"), got.map { it.clientId })
    }

    @Test fun equalTimesKeepQueueOrder() {
        val got = SendQueue.drainOrder(listOf(e("x", 5.0), e("y", 5.0), e("z", 4.0)))
        assertEquals(listOf("z", "x", "y"), got.map { it.clientId })
    }
}
