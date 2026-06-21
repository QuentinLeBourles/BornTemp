package com.borntemp.app.viewmodel

import com.borntemp.app.obd.ObdPids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Handoff-2 rolling-window analytics.
 *
 * The tests use synthetic deterministic sample streams (no live OBD): we
 * stuff fake samples into the buffer at known timestamps and verify the
 * derived ETA, slopes, and integrator output.
 */
class ChargeAnalyticsTest {

    private val delta = 0.05f

    private fun sample(
        t: Long,
        soc: Float?,
        power: Float?,
        temp: Float? = 25f,
        mode: ObdPids.VehicleMode = ObdPids.VehicleMode.CHARGING_DC
    ) = ChargeAnalytics.Sample(
        t = t,
        socHmi = soc,
        socBms = soc,
        tempAvg = temp,
        powerKw = power,
        voltage = null,
        current = null,
        mode = mode,
    )

    @Test
    fun `avgPowerKw averages over the requested window only`() {
        val a = ChargeAnalytics()
        // 5 samples 12 s apart → 60 s total span.
        listOf(
            sample(0L,     20f,  60f),
            sample(12_000L, 22f,  80f),
            sample(24_000L, 24f, 100f),
            sample(36_000L, 26f,  80f),
            sample(48_000L, 28f,  60f),
        ).forEach { a.push(it) }
        // 60-second window: should include all five samples → mean = 76 kW.
        assertEquals(76f, a.avgPowerKw(60_000L)!!, 0.5f)
    }

    @Test
    fun `etaMinutesTo returns null when not charging`() {
        val a = ChargeAnalytics()
        a.push(sample(0L, 40f, 50f, mode = ObdPids.VehicleMode.DRIVING))
        a.push(sample(5_000L, 41f, 55f, mode = ObdPids.VehicleMode.DRIVING))
        val eta = a.etaMinutesTo(80f, 41f, 77f, ChargeState.NOT_CHARGING)
        assertNull(eta)
    }

    @Test
    fun `etaMinutesTo computes minutes from gap and average power`() {
        val a = ChargeAnalytics()
        // Constant 50 kW charging, 77 kWh pack. 40 % → 80 % is 40 % of pack
        // = 30.8 kWh, which at 50 kW takes 36.96 min.
        for (i in 0..10) a.push(sample(i * 6_000L, 40f + i * 0.5f, 50f))
        val eta = a.etaMinutesTo(80f, 40f, 77f, ChargeState.DC_CHARGING)!!
        assertEquals(36.96f, eta, 0.2f)
    }

    @Test
    fun `etaMinutesTo returns zero when already at target`() {
        val a = ChargeAnalytics()
        for (i in 0..10) a.push(sample(i * 6_000L, 85f, 30f))
        val eta = a.etaMinutesTo(80f, 85f, 77f, ChargeState.AC_CHARGING)!!
        assertEquals(0f, eta, 0.001f)
    }

    @Test
    fun `socSlopePctPerMin tracks linear increase`() {
        val a = ChargeAnalytics()
        // 6 samples, 10 s apart, SOC increasing 0.1 %/sample = 0.6 %/min.
        for (i in 0..5) a.push(sample(i * 10_000L, 40f + i * 0.1f, 80f))
        val slope = a.socSlopePctPerMin()!!
        assertEquals(0.6f, slope, 0.05f)
    }

    @Test
    fun `tempSlopeCPerMin detects warming pack`() {
        val a = ChargeAnalytics()
        // Matches the morning Handoff-2 data: ~0.45 °C/min.
        for (i in 0..5) {
            a.push(sample(i * 30_000L, 55f + i * 0.5f, 88f, temp = 36f + i * 0.225f))
        }
        val slope = a.tempSlopeCPerMin()!!
        assertEquals(0.45f, slope, 0.05f)
    }

    @Test
    fun `slope returns null with insufficient samples`() {
        val a = ChargeAnalytics()
        a.push(sample(0L, 20f, 50f))
        a.push(sample(5_000L, 22f, 50f))
        assertNull(a.socSlopePctPerMin())
        assertNull(a.tempSlopeCPerMin())
    }

    @Test
    fun `slope returns null with span shorter than 30 seconds`() {
        val a = ChargeAnalytics()
        for (i in 0..5) a.push(sample(i * 2_000L, 20f + i * 0.5f, 50f))
        // Total span = 10 s, below the 30 s minimum.
        assertNull(a.socSlopePctPerMin())
    }

    @Test
    fun `energy integrator emits result for a complete mid-range sweep`() {
        val a = ChargeAnalytics()
        // Realistic charge: 50 kW into a 77 kWh pack means roughly
        //   ΔSOC/Δt = P / capacity = 50/77 ≈ 0.65 %/min ≈ 0.054 %/s
        // → with 5 s ticks the SoC moves 0.27 % per sample. We use 0.1 %
        // per sample (slower, conservative). Crossing the 30..70 % band
        // takes 400 samples × 5 s = 33 min, integrated energy = 50 × 33/60
        // ≈ 27.5 kWh, apparent capacity ≈ 27.5 / 0.40 ≈ 69 kWh.
        val pollMs = 5_000L
        val deltaSocPerSample = 0.1f
        var soc = 28f
        var t = 0L
        repeat(500) {
            a.push(sample(t, soc, 50f))
            t += pollMs
            soc += deltaSocPerSample
        }
        // Push one non-charging sample to flush the segment.
        a.push(sample(t, soc, 0f, mode = ObdPids.VehicleMode.STANDBY))
        val result = a.energyIntegrator().lastResult()
        assertNotNull("integrator should emit after a full mid-range pass", result)
        assertTrue("apparent capacity should be plausible: ${result!!.apparentCapacityKwh}",
            result.apparentCapacityKwh in 50f..100f)
        assertTrue("ΔSOC should cover at least 20 %", result.socDeltaPct >= 20f)
    }

    @Test
    fun `energy integrator drops partial segments`() {
        val a = ChargeAnalytics()
        // Only sweep 30..45 % then stop charging — below the 50% of band
        // threshold, so no result should be emitted.
        var soc = 30f
        var t = 0L
        repeat(30) {
            a.push(sample(t, soc, 40f))
            t += 5_000L
            soc += 0.5f
        }
        a.push(sample(t, soc, 0f, mode = ObdPids.VehicleMode.STANDBY))
        assertNull(a.energyIntegrator().lastResult())
    }

    @Test
    fun `classifyThermalTrajectory advises pre-drive when cold and parked`() {
        val tr = classifyThermalTrajectory(
            tempAvg = 6f,
            slopeCPerMin = null,
            chargeState = ChargeState.NOT_CHARGING,
            pumpPct = null
        )
        assertEquals(ThermalAdvice.DRIVE_BEFORE_CHARGING, tr.advice)
    }

    @Test
    fun `classifyThermalTrajectory advises derated when hot and pump saturated`() {
        val tr = classifyThermalTrajectory(
            tempAvg = 40f,
            slopeCPerMin = 0.6f,
            chargeState = ChargeState.DC_CHARGING,
            pumpPct = 100f
        )
        assertEquals(ThermalAdvice.CHARGE_DERATED, tr.advice)
    }

    @Test
    fun `classifyThermalTrajectory advises optimal in the comfort band`() {
        val tr = classifyThermalTrajectory(
            tempAvg = 24f,
            slopeCPerMin = 0.1f,
            chargeState = ChargeState.NOT_CHARGING,
            pumpPct = 10f
        )
        assertEquals(ThermalAdvice.OPTIMAL, tr.advice)
    }
}
