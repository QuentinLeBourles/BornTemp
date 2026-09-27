package com.borntemp.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTest {

    private val ok = UdsResult(ReadStatus.OK, null, null)

    @Test
    fun `OK reply that parses is an OK reading`() {
        val r = readingOf(ok, 30.5f, 1_000L)
        assertEquals(ReadStatus.OK, r.status)
        assertEquals(30.5f, r.value)
        assertEquals(1_000L, r.timestampMs)
        assertTrue(r.isOk)
    }

    @Test
    fun `OK reply that does not parse is a parse error with no value`() {
        val r = readingOf<Float>(ok, null, 1_000L)
        assertEquals(ReadStatus.PARSE_ERROR, r.status)
        assertNull(r.value)
        assertFalse(r.isOk)
    }

    @Test
    fun `failed reply keeps its status and detail and drops any value`() {
        val nrc = UdsResult(ReadStatus.NRC, 0x31, "requestOutOfRange")
        val r = readingOf(nrc, 12f, 1_000L)
        assertEquals(ReadStatus.NRC, r.status)
        assertEquals("requestOutOfRange", r.detail)
        assertNull(r.value)
    }

    @Test
    fun `only timeouts are retried, and only within the policy`() {
        val p = PidPolicy(timeoutMs = 2_000L, retries = 1)
        assertTrue(shouldRetry(ReadStatus.TIMEOUT, attempt = 0, policy = p))
        assertFalse(shouldRetry(ReadStatus.TIMEOUT, attempt = 1, policy = p))
        // An NRC is the ECU's definitive answer; asking again changes nothing.
        assertFalse(shouldRetry(ReadStatus.NRC, attempt = 0, policy = p))
        assertFalse(shouldRetry(ReadStatus.PARSE_ERROR, attempt = 0, policy = p))
        assertFalse(shouldRetry(ReadStatus.OK, attempt = 0, policy = p))
    }

    @Test
    fun `a held reading keeps its original timestamp`() {
        val r = readingOf(ok, 26f, 1_000L)
        val held = r.heldOr(Reading.notSupported(5_000L))
        assertEquals(1_000L, held.timestampMs)
    }

    @Test
    fun `poll interval is tight in charge and looser otherwise`() {
        val cfg = PollIntervals(chargingMs = 5_000L, otherMs = 15_000L)
        assertEquals(5_000L, cfg.forMode(charging = true))
        assertEquals(15_000L, cfg.forMode(charging = false))
    }

    @Test
    fun `charge interval is clamped to 5-10 s`() {
        assertEquals(5_000L, PollIntervals(chargingMs = 1_000L, otherMs = 15_000L).chargingMs)
        assertEquals(10_000L, PollIntervals(chargingMs = 30_000L, otherMs = 15_000L).chargingMs)
        assertEquals(60_000L, PollIntervals(chargingMs = 5_000L, otherMs = 120_000L).otherMs)
    }
}
