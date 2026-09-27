package com.borntemp.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvRowPolicyTest {

    @Test
    fun `average needs both extremes`() {
        assertEquals(34.7f, packAverageTemp(34.3f, 35.1f)!!, 0.001f)
        // 2026-09-26 11:19:22: T max missing, the old code wrote t_avg = t_min.
        assertNull(packAverageTemp(22.0f, null))
        assertNull(packAverageTemp(null, 35.1f))
    }

    @Test
    fun `charging writes a row every tick`() {
        assertTrue(shouldWriteCsvRow(lastRowMs = 0L, nowMs = 5_000L, charging = true))
        assertTrue(shouldWriteCsvRow(lastRowMs = 10_000L, nowMs = 15_000L, charging = true))
    }

    @Test
    fun `outside a charge rows stay 30 s apart`() {
        assertFalse(shouldWriteCsvRow(lastRowMs = 10_000L, nowMs = 25_000L, charging = false))
        assertTrue(shouldWriteCsvRow(lastRowMs = 10_000L, nowMs = 40_000L, charging = false))
    }

    @Test
    fun `status cell names the status and the NRC code`() {
        assertEquals("OK", statusCell(Reading.ok(1f, 0L)))
        assertEquals("NRC_31", statusCell(Reading<Float>(null, ReadStatus.NRC, 0L, "requestOutOfRange", 0x31)))
        assertEquals("TIMEOUT", statusCell(Reading<Float>(null, ReadStatus.TIMEOUT, 0L, "NO_DATA")))
        assertEquals("", statusCell(null))
    }

    @Test
    fun `readingOf keeps the NRC code`() {
        val r = readingOf<Float>(UdsResult(ReadStatus.NRC, 0x31, "requestOutOfRange"), null, 0L)
        assertEquals(0x31, r.nrc)
    }
}
