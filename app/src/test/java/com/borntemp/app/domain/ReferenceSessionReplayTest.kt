package com.borntemp.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the reference session borntemp_soh_20260926-111140.csv (drive, then
 * a DC charge 31 → 57 % BMS SOC) through the domain functions. Values asserted
 * here were checked by hand against the raw rows.
 */
class ReferenceSessionReplayTest {

    private data class Row(val iso: String, val t: Long, val cols: Map<String, String>) {
        fun f(name: String): Float? = cols[name]?.takeIf { it.isNotBlank() }?.toFloatOrNull()
    }

    private val rows: List<Row> by lazy {
        val lines = javaClass.classLoader!!
            .getResourceAsStream("borntemp_soh_20260926-111140.csv")!!
            .bufferedReader().readLines()
        val header = lines.first().split(',')
        lines.drop(1).filter { it.isNotBlank() }.map { line ->
            val c = header.zip(line.split(',')).toMap()
            Row(c.getValue("iso_time"), c.getValue("unix_ms").toLong(), c)
        }
    }

    private fun row(time: String) = rows.single { it.iso.endsWith(time) }

    @Test
    fun `thermal metrics at 11_32_43`() {
        val r = row("T11:32:43")
        val m = thermalMetrics(r.f("t_min_c"), r.f("t_max_c"), r.f("t_coolant_in_c"), r.f("t_coolant_out_c"))
        assertEquals(9.1f, m.headroomC!!, 0.01f)
        assertEquals(2.0f, m.extractionC!!, 0.01f)   // inlet 26, outlet 28
        assertEquals(0.8f, m.spreadC!!, 0.01f)
        assertEquals(true, m.activeCooling)
    }

    @Test
    fun `active cooling is first detected at 11_30_38`() {
        val first = firstActiveCoolingMs(rows.map { it.toSample(null) })
        assertEquals(row("T11:30:38").t, first)
    }

    @Test
    fun `t_avg is undefined on the three rows missing t_max`() {
        val gaps = rows.filter { it.f("t_max_c") == null }
        assertEquals(3, gaps.size)
        gaps.forEach { assertEquals(null, packAverageTemp(it.f("t_min_c"), it.f("t_max_c"))) }
    }

    @Test
    fun `estimated charge energy matches the BMS lifetime counter`() {
        // The 1E32 charge counter rose 27.8 kWh between 11:20:33 and 11:37:56.
        // The SOC-slope estimate, scaled by the measured 72.1 kWh per 100 %
        // HMI SOC, has to land near it for the ESTIMATED badge to be honest.
        val charge = rows.filter { it.cols["vehicle_mode"] == "CHARGING_DC" }
        val window = 120_000L
        val samples = charge.map { r ->
            val pts = charge.filter { it.t in (r.t - window)..r.t }.mapNotNull { p -> p.f("soc_hmi_pct")?.let { p.t to it } }
            val power = resolvePower(r.f("v_hv_v"), null, ratePerMin(pts, window), capacityKwh = 72.1f)
            r.toSample(power)
        }
        val sum = summarizeSession(samples)!!
        assertEquals(PowerSource.ESTIMATED, sum.energySource)
        assertTrue("energy ${sum.energyKwh}", sum.energyKwh!! in 24f..31f)
        assertTrue("avg ${sum.avgKw}", sum.avgKw!! in 80f..115f)
        assertEquals(row("T11:30:38").t, sum.coolingStartMs)
    }

    private fun Row.toSample(power: PowerReading?) = SessionSample(
        timestampMs = t,
        socHmi = f("soc_hmi_pct"),
        tMin = f("t_min_c"),
        tMax = f("t_max_c"),
        coolantIn = f("t_coolant_in_c"),
        coolantOut = f("t_coolant_out_c"),
        power = power,
    )
}
