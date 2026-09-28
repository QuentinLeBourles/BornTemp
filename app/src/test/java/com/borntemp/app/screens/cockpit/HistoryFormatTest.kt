package com.borntemp.app.screens.cockpit

import com.borntemp.app.domain.ChargeRecord
import com.borntemp.app.domain.LastKnown
import com.borntemp.app.domain.MonthEnergy
import com.borntemp.app.domain.ParkedGap
import com.borntemp.app.viewmodel.BatteryData
import com.borntemp.app.viewmodel.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId

class HistoryFormatTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val t0 = 1_790_413_911_838L   // 2026-09-26 11:11:51 Paris
    private val lk = LastKnown(t0, 58.3f, 58.4f, 29.3f, 29f, 29.6f, 14.4f, 93.7f, 12, 8388f, 7978f)

    @Test
    fun `connected with data shows live values and no age`() {
        val live = BatteryData(soc = 70f, avgTemp = 30f, sohPct = 93.7f, volt12v = 14.1f, timestamp = t0 + 10)
        val h = heroSnapshot(ConnectionState.CONNECTED, live, lk, nowMs = t0 + 20)
        assertEquals(70f, h.socHmi)
        assertNull(h.staleLabel)
    }

    @Test
    fun `disconnected falls back to last known with its age`() {
        val h = heroSnapshot(ConnectionState.DISCONNECTED, BatteryData(), lk, nowMs = t0 + 3 * 3_600_000L)
        assertEquals(58.3f, h.socHmi)
        assertEquals(29.3f, h.tempAvg)
        assertEquals(14.4f, h.volt12v)
        assertEquals("DERNIER RELEVÉ · il y a 3 h", h.staleLabel)
    }

    @Test
    fun `connected but before the first poll still shows last known`() {
        val h = heroSnapshot(ConnectionState.CONNECTED, BatteryData(), lk, nowMs = t0 + 60_000L)
        assertEquals(58.3f, h.socHmi)
        assertEquals("DERNIER RELEVÉ · il y a 1 min", h.staleLabel)
    }

    @Test
    fun `nothing ever recorded shows nothing`() {
        val h = heroSnapshot(ConnectionState.DISCONNECTED, BatteryData(), null, nowMs = t0)
        assertNull(h.socHmi)
        assertNull(h.staleLabel)
    }

    @Test
    fun `charge lines read like the dashboard`() {
        val r = ChargeRecord(
            startMs = t0 + 8 * 60_000L + 41_000L, endMs = t0 + 26 * 60_000L, dc = true,
            socStart = 26.9f, socEnd = 65.4f, counterKwh = 27.82f, estimatedKwh = 26.8f,
            avgKw = 101f, peakKw = 142f, tMaxStart = 22.3f, tMaxEnd = 36.1f, tMaxPeak = 36.4f,
            coolingStartMs = t0 + 18 * 60_000L + 47_000L,
        )
        val l = chargeLines(r, paris)
        assertEquals("sam. 26/09 · 11:20 · DC · 17 min", l.title)
        assertEquals("27 → 65 %", l.soc)
        assertEquals("27.8 kWh", l.energy)
        assertEquals("moy. 101 · pic 142 kW", l.power)
        assertEquals("T max 22 → 36 °C · refroid. 11:30", l.thermal)
    }

    @Test
    fun `estimated-only charge energy is flagged`() {
        val r = ChargeRecord(t0, t0 + 60_000L, false, 50f, 52f, null, 1.2f, null, null, null, null, null, null)
        val l = chargeLines(r, paris)
        assertEquals("≈ 1.2 kWh (estimé)", l.energy)
        assertEquals("", l.power)
        assertEquals("", l.thermal)
    }

    @Test
    fun `month and parked lines`() {
        assertEquals("sept. 2026", monthLabel(MonthEnergy(YearMonth.of(2026, 9), 120f, 95f).month))
        val g = ParkedGap(t0, t0 + 2 * 86_400_000L, 0.42f, 0.6f)
        assertEquals("26/09 → 28/09 · 0.42 kWh/j · 0.6 %/j", parkedLine(g, paris))
    }
}
