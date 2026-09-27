package com.borntemp.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId

class HistoryTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val h = 3_600_000L
    private val day = 24 * h

    // 2026-09-26 11:11:51 Europe/Paris, the reference session's start.
    private val t0 = 1_790_413_911_838L

    private fun snap(t: Long, soc: Float?, c: Float?, d: Float?) = CounterSnapshot(t, soc, c, d)

    // ── Codecs ─────────────────────────────────────────────────────────────

    @Test
    fun `connection record round-trips, nulls included`() {
        val r = ConnectionRecord(snap(t0, 28.2f, 8338.1f, 7950.4f), snap(t0 + h, null, 8367.9f, null))
        assertEquals(r, ConnectionRecord.decode(r.encode()))
    }

    @Test
    fun `charge record round-trips`() {
        val r = ChargeRecord(
            startMs = t0, endMs = t0 + 17 * 60_000L, dc = true,
            socStart = 26.9f, socEnd = 65.4f,
            counterKwh = 27.82f, estimatedKwh = 26.8f,
            avgKw = 101f, peakKw = 142f,
            tMaxStart = 22.3f, tMaxEnd = 36.1f, tMaxPeak = 36.4f,
            coolingStartMs = t0 + 10 * 60_000L,
        )
        assertEquals(r, ChargeRecord.decode(r.encode()))
    }

    @Test
    fun `last known round-trips`() {
        val r = LastKnown(t0, 58.3f, 58.4f, 29.3f, 29.0f, 29.6f, 14.4f, 93.7f, 12, 8388f, 7978f)
        assertEquals(r, LastKnown.decode(r.encode()))
    }

    @Test
    fun `decoding garbage or another record type yields null instead of throwing`() {
        assertNull(ConnectionRecord.decode("not a record"))
        assertNull(ConnectionRecord.decode(LastKnown(t0, null, null, null, null, null, null, null, null, null, null).encode()))
        assertNull(ChargeRecord.decode(""))
    }

    @Test
    fun `floats are written with a dot whatever the default locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            val line = ConnectionRecord(snap(t0, 28.25f, 1.5f, 2.5f), snap(t0, 1f, 1f, 1f)).encode()
            assertTrue(line, "28.25" in line && "," !in line)
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    // ── Aggregations ───────────────────────────────────────────────────────

    @Test
    fun `monthly energy sums counter deltas into the month they were observed`() {
        val aug31 = 1_788_206_400_000L          // 2026-08-31 22:00 Paris
        val s = listOf(
            snap(aug31, 50f, 8000f, 7600f),
            snap(aug31 + 4 * h, 40f, 8000f, 7610f),   // Sept 1st: 10 kWh driven, overnight
            snap(aug31 + 5 * day, 80f, 8030f, 7612f), // 30 kWh charged in Sept
        )
        val m = monthlyEnergy(s, paris)
        assertEquals(1, m.size)
        assertEquals(YearMonth.of(2026, 9), m[0].month)
        assertEquals(30f, m[0].chargedKwh, 0.01f)
        assertEquals(12f, m[0].dischargedKwh, 0.01f)
    }

    @Test
    fun `monthly energy skips counter resets and missing values`() {
        val s = listOf(
            snap(t0, 50f, 8000f, 7600f),
            snap(t0 + h, 50f, null, 7601f),
            snap(t0 + 2 * h, 50f, 10f, 7602f),       // corrupt frame: counter "went back"
        )
        val m = monthlyEnergy(s, paris).single()
        assertEquals(0f, m.chargedKwh, 0.01f)
        assertEquals(2f, m.dischargedKwh, 0.01f)
    }

    @Test
    fun `parked drain is measured between connections the car stayed parked`() {
        val a = ConnectionRecord(snap(t0, 60f, 8000f, 7600f), snap(t0 + h, 58.3f, 8000f, 7605f))
        val b = ConnectionRecord(snap(t0 + h + 2 * day, 57.1f, 8000f, 7605.8f), snap(t0 + 3 * day, 50f, 8000f, 7610f))
        val gap = parkedGaps(listOf(b, a)).single()
        assertEquals(2 * day, gap.toMs - gap.fromMs)
        assertEquals(0.4f, gap.dischargeKwhPerDay!!, 0.01f)
        assertEquals(0.6f, gap.socPctPerDay!!, 0.01f)
    }

    @Test
    fun `gaps where the car was driven, charged or barely parked are not drain`() {
        val a = ConnectionRecord(snap(t0, 60f, 8000f, 7600f), snap(t0 + h, 58f, 8000f, 7600f))
        val driven = ConnectionRecord(snap(t0 + 2 * day, 40f, 8000f, 7615f), snap(t0 + 3 * day, 40f, 8000f, 7615f))
        val charged = ConnectionRecord(snap(t0 + 5 * day, 80f, 8030f, 7615f), snap(t0 + 6 * day, 80f, 8030f, 7615f))
        val quick = ConnectionRecord(snap(t0 + 6 * day + h, 80f, 8030f, 7615f), snap(t0 + 7 * day, 79f, 8030f, 7615f))
        assertTrue(parkedGaps(listOf(a, driven, charged, quick)).isEmpty())
    }

    @Test
    fun `age label reads naturally`() {
        assertEquals("à l'instant", ageLabel(t0 + 30_000L, t0))
        assertEquals("il y a 5 min", ageLabel(t0 + 5 * 60_000L, t0))
        assertEquals("il y a 3 h", ageLabel(t0 + 3 * h + 20 * 60_000L, t0))
        assertEquals("il y a 2 j", ageLabel(t0 + 2 * day + 5 * h, t0))
    }

    @Test
    fun `charge record takes energy from the BMS counter when both ends were read`() {
        val summary = SessionSummary(
            startMs = t0, durationMs = 17 * 60_000L, energyKwh = 26.8f,
            energySource = PowerSource.ESTIMATED, avgKw = 101f, peakKw = 142f,
            tMaxStartC = 22.3f, tMaxEndC = 36.1f, socStart = 26.9f, socEnd = 65.4f,
            coolingStartMs = t0 + 600_000L,
        )
        val r = chargeRecordOf(summary, dc = true, chargedAtStart = 8340.07f, chargedAtEnd = 8367.89f, tMaxPeak = 36.4f)
        assertEquals(27.82f, r.counterKwh!!, 0.01f)
        assertEquals(26.8f, r.estimatedKwh!!, 0.01f)
        assertEquals(27.82f, r.bestKwh!!, 0.01f)
        assertEquals(t0 + 17 * 60_000L, r.endMs)

        val noCounter = chargeRecordOf(summary, dc = true, chargedAtStart = null, chargedAtEnd = 8367.89f, tMaxPeak = null)
        assertNull(noCounter.counterKwh)
        assertEquals(26.8f, noCounter.bestKwh!!, 0.01f)
    }
}
