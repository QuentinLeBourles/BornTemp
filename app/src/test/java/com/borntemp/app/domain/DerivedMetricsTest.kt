package com.borntemp.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-function tests; the replay against the real session is in [ReferenceSessionReplayTest]. */
class DerivedMetricsTest {

    @Test
    fun `thermal metrics from the four temperatures`() {
        val m = thermalMetrics(tMin = 34.3f, tMax = 35.1f, coolantIn = 26f, coolantOut = 28f)
        assertEquals(9.1f, m.headroomC!!, 0.001f)
        assertEquals(2.0f, m.extractionC!!, 0.001f)
        assertEquals(0.8f, m.spreadC!!, 0.001f)
        assertEquals(true, m.activeCooling)
    }

    @Test
    fun `each metric is null when one of its inputs is missing`() {
        val m = thermalMetrics(tMin = null, tMax = 35.1f, coolantIn = null, coolantOut = 28f)
        assertNull(m.headroomC)
        assertNull(m.extractionC)
        assertNull(m.spreadC)
        assertNull(m.activeCooling)
    }

    @Test
    fun `equal coolant temperatures are not active cooling`() {
        assertEquals(false, thermalMetrics(30f, 31f, 28f, 28f).activeCooling)
    }

    @Test
    fun `rate per minute is a least-squares slope over the window`() {
        // +0.5 °C every 30 s = 1 °C/min, plus an old point outside the window.
        val pts = listOf(0L to 0f, 600_000L to 10f, 630_000L to 10.5f, 660_000L to 11f, 690_000L to 11.5f)
        assertEquals(1.0f, ratePerMin(pts, windowMs = 120_000L)!!, 0.001f)
    }

    @Test
    fun `rate needs two points spanning at least 30 s`() {
        assertNull(ratePerMin(listOf(0L to 1f), 120_000L))
        assertNull(ratePerMin(listOf(0L to 1f, 10_000L to 2f), 120_000L))
        assertNotNull(ratePerMin(listOf(0L to 1f, 30_000L to 2f), 120_000L))
    }

    @Test
    fun `measured power wins when current is present`() {
        val p = resolvePower(voltageV = 370f, currentA = 250f, socSlopePctPerMin = 2f, capacityKwh = 72f)
        assertEquals(92.5f, p!!.kw, 0.01f)
        assertEquals(PowerSource.MEASURED, p.source)
    }

    @Test
    fun `without current power is estimated from the SOC slope`() {
        // 2 %/min of 72 kWh = 1.44 kWh/min = 86.4 kW.
        val p = resolvePower(voltageV = 370f, currentA = null, socSlopePctPerMin = 2f, capacityKwh = 72f)
        assertEquals(86.4f, p!!.kw, 0.01f)
        assertEquals(PowerSource.ESTIMATED, p.source)
    }

    @Test
    fun `no current and no slope means no power`() {
        assertNull(resolvePower(370f, null, null, 72f))
    }

    @Test
    fun `first active cooling is the first sample with outlet above inlet`() {
        val s = listOf(
            sample(0L, cIn = 28f, cOut = 28f),
            sample(1L, cIn = null, cOut = 29f),
            sample(2L, cIn = 27f, cOut = 29f),
            sample(3L, cIn = 26f, cOut = 28f),
        )
        assertEquals(2L, firstActiveCoolingMs(s))
        assertNull(firstActiveCoolingMs(s.take(2)))
    }

    @Test
    fun `session summary integrates power and keeps the weakest source`() {
        val s = listOf(
            sample(0L, powerKw = 100f, source = PowerSource.MEASURED, tMax = 30f),
            sample(1_800_000L, powerKw = 100f, source = PowerSource.ESTIMATED, tMax = 35f),
            sample(3_600_000L, powerKw = 50f, source = PowerSource.MEASURED, tMax = 36f),
        )
        val sum = summarizeSession(s)!!
        assertEquals(3_600_000L, sum.durationMs)
        // Trapezoids: 0.5 h × 100 + 0.5 h × 75 = 87.5 kWh.
        assertEquals(87.5f, sum.energyKwh!!, 0.01f)
        assertEquals(87.5f, sum.avgKw!!, 0.01f)
        assertEquals(100f, sum.peakKw!!, 0.01f)
        assertEquals(PowerSource.ESTIMATED, sum.energySource)
        assertEquals(30f, sum.tMaxStartC)
        assertEquals(36f, sum.tMaxEndC)
    }

    @Test
    fun `empty session has no summary`() {
        assertNull(summarizeSession(emptyList()))
        assertFalse(summarizeSession(listOf(sample(0L)))!!.energyKwh != null)
    }

    private fun sample(
        t: Long,
        tMax: Float? = null,
        cIn: Float? = null,
        cOut: Float? = null,
        powerKw: Float? = null,
        source: PowerSource? = null,
    ) = SessionSample(
        timestampMs = t, socHmi = null, tMin = null, tMax = tMax,
        coolantIn = cIn, coolantOut = cOut,
        power = powerKw?.let { PowerReading(it, source ?: PowerSource.MEASURED) },
    )

    @Test
    fun `sanity - sample helper builds power only when given`() {
        assertTrue(sample(0L, powerKw = 1f).power != null)
        assertNull(sample(0L).power)
    }
}
