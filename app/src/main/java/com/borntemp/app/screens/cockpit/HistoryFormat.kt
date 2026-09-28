package com.borntemp.app.screens.cockpit

import com.borntemp.app.domain.ChargeRecord
import com.borntemp.app.domain.LastKnown
import com.borntemp.app.domain.ParkedGap
import com.borntemp.app.domain.ageLabel
import com.borntemp.app.viewmodel.BatteryData
import com.borntemp.app.viewmodel.ConnectionState
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Pure formatting for the offline views, kept out of the composables so it
// can be tested on the JVM. French, Locale.FRANCE for day/month names.

/** What the cockpit hero shows: live values, or the last known ones with their age. */
data class HeroSnapshot(
    val tempAvg: Float?,
    val socHmi: Float?,
    val sohPct: Float?,
    val volt12v: Float?,
    /** Non-null when the values are not live. */
    val staleLabel: String?,
)

fun heroSnapshot(
    state: ConnectionState,
    live: BatteryData,
    lastKnown: LastKnown?,
    nowMs: Long,
): HeroSnapshot {
    val hasLive = state == ConnectionState.CONNECTED && live.timestamp > 0L
    if (hasLive || lastKnown == null) {
        return HeroSnapshot(live.avgTemp, live.soc, live.sohPct, live.volt12v, null)
    }
    return HeroSnapshot(
        tempAvg = lastKnown.tAvg,
        socHmi = lastKnown.socHmi,
        sohPct = lastKnown.sohPct,
        volt12v = lastKnown.volt12v,
        staleLabel = "DERNIER RELEVÉ · ${ageLabel(nowMs, lastKnown.t)}",
    )
}

data class ChargeLines(
    val title: String,
    val soc: String,
    val energy: String,
    val power: String,
    val thermal: String,
)

private val dayTime = DateTimeFormatter.ofPattern("EEE dd/MM · HH:mm", Locale.FRANCE)
private val hourMin = DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE)
private val dayMonth = DateTimeFormatter.ofPattern("dd/MM", Locale.FRANCE)
private val monthYear = DateTimeFormatter.ofPattern("MMM yyyy", Locale.FRANCE)

private fun Long.at(zone: ZoneId) = Instant.ofEpochMilli(this).atZone(zone)
private fun f1(x: Float) = String.format(Locale.US, "%.1f", x)
private fun f2(x: Float) = String.format(Locale.US, "%.2f", x)

fun chargeLines(r: ChargeRecord, zone: ZoneId): ChargeLines {
    val minutes = ((r.endMs - r.startMs) / 60_000L).coerceAtLeast(0)
    val soc = if (r.socStart != null && r.socEnd != null)
        "${r.socStart.roundToInt()} → ${r.socEnd.roundToInt()} %" else ""
    val energy = when {
        r.counterKwh != null -> "${f1(r.counterKwh)} kWh"
        r.estimatedKwh != null -> "≈ ${f1(r.estimatedKwh)} kWh (estimé)"
        else -> ""
    }
    val power = if (r.avgKw != null && r.peakKw != null)
        "moy. ${r.avgKw.roundToInt()} · pic ${r.peakKw.roundToInt()} kW" else ""
    val thermal = buildList {
        if (r.tMaxStart != null && r.tMaxEnd != null) {
            add("T max ${r.tMaxStart.roundToInt()} → ${r.tMaxEnd.roundToInt()} °C")
        }
        r.coolingStartMs?.let { add("refroid. ${hourMin.format(it.at(zone))}") }
    }.joinToString(" · ")
    return ChargeLines(
        title = "${dayTime.format(r.startMs.at(zone))} · ${if (r.dc) "DC" else "AC"} · $minutes min",
        soc = soc,
        energy = energy,
        power = power,
        thermal = thermal,
    )
}

fun monthLabel(month: YearMonth): String = monthYear.format(month)

fun parkedLine(g: ParkedGap, zone: ZoneId): String = buildList {
    add("${dayMonth.format(g.fromMs.at(zone))} → ${dayMonth.format(g.toMs.at(zone))}")
    g.dischargeKwhPerDay?.let { add("${f2(it)} kWh/j") }
    g.socPctPerDay?.let { add("${f1(it)} %/j") }
}.joinToString(" · ")
