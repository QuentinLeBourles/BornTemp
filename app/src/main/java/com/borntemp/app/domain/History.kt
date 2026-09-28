package com.borntemp.app.domain

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

// Offline history: what the app keeps between connections so it has something
// to show when the car isn't there. Records are one tab-separated line each,
// tagged and versioned, so the store can append without a schema engine and a
// damaged line costs one record, not the file.

/** BMS lifetime counters (1E32) and SOC at one instant. */
data class CounterSnapshot(
    val t: Long,
    val socHmi: Float?,
    val chargedKwh: Float?,
    val dischargedKwh: Float?,
)

/** One OBD connection, from its first to its last counter read. */
data class ConnectionRecord(val start: CounterSnapshot, val end: CounterSnapshot) {
    fun encode(): String = line(TAG, start.t, start.socHmi, start.chargedKwh, start.dischargedKwh,
        end.t, end.socHmi, end.chargedKwh, end.dischargedKwh)

    companion object {
        const val TAG = "C1"
        fun decode(line: String): ConnectionRecord? = fields(line, TAG, 8)?.let { f ->
            ConnectionRecord(
                CounterSnapshot(f.long(0) ?: return null, f.float(1), f.float(2), f.float(3)),
                CounterSnapshot(f.long(4) ?: return null, f.float(5), f.float(6), f.float(7)),
            )
        }
    }
}

/**
 * One charge. [counterKwh] is the BMS charge-counter delta (what went into the
 * pack); [estimatedKwh] the SOC-slope integral. [bestKwh] prefers the counter.
 */
data class ChargeRecord(
    val startMs: Long,
    val endMs: Long,
    val dc: Boolean,
    val socStart: Float?,
    val socEnd: Float?,
    val counterKwh: Float?,
    val estimatedKwh: Float?,
    val avgKw: Float?,
    val peakKw: Float?,
    val tMaxStart: Float?,
    val tMaxEnd: Float?,
    val tMaxPeak: Float?,
    val coolingStartMs: Long?,
) {
    val bestKwh: Float? get() = counterKwh ?: estimatedKwh

    fun encode(): String = line(TAG, startMs, endMs, if (dc) 1 else 0, socStart, socEnd, counterKwh,
        estimatedKwh, avgKw, peakKw, tMaxStart, tMaxEnd, tMaxPeak, coolingStartMs)

    companion object {
        const val TAG = "G1"
        fun decode(line: String): ChargeRecord? = fields(line, TAG, 13)?.let { f ->
            ChargeRecord(
                startMs = f.long(0) ?: return null, endMs = f.long(1) ?: return null,
                dc = f.long(2) == 1L, socStart = f.float(3), socEnd = f.float(4),
                counterKwh = f.float(5), estimatedKwh = f.float(6), avgKw = f.float(7),
                peakKw = f.float(8), tMaxStart = f.float(9), tMaxEnd = f.float(10),
                tMaxPeak = f.float(11), coolingStartMs = f.long(12),
            )
        }
    }
}

/** Last readings seen, shown with their age while disconnected. */
data class LastKnown(
    val t: Long,
    val socHmi: Float?,
    val socBms: Float?,
    val tAvg: Float?,
    val tMin: Float?,
    val tMax: Float?,
    val volt12v: Float?,
    val sohPct: Float?,
    val cellDeltaMv: Int?,
    val chargedKwh: Float?,
    val dischargedKwh: Float?,
) {
    fun encode(): String = line(TAG, t, socHmi, socBms, tAvg, tMin, tMax, volt12v, sohPct,
        cellDeltaMv, chargedKwh, dischargedKwh)

    companion object {
        const val TAG = "L1"
        fun decode(line: String): LastKnown? = fields(line, TAG, 11)?.let { f ->
            LastKnown(f.long(0) ?: return null, f.float(1), f.float(2), f.float(3), f.float(4),
                f.float(5), f.float(6), f.float(7), f.long(8)?.toInt(), f.float(9), f.float(10))
        }
    }
}

/** A charge record from the phase-4 summary plus the counter at both ends. */
fun chargeRecordOf(
    summary: SessionSummary,
    dc: Boolean,
    chargedAtStart: Float?,
    chargedAtEnd: Float?,
    tMaxPeak: Float?,
): ChargeRecord = ChargeRecord(
    startMs = summary.startMs,
    endMs = summary.startMs + summary.durationMs,
    dc = dc,
    socStart = summary.socStart,
    socEnd = summary.socEnd,
    counterKwh = if (chargedAtStart != null && chargedAtEnd != null && chargedAtEnd >= chargedAtStart)
        chargedAtEnd - chargedAtStart else null,
    estimatedKwh = summary.energyKwh,
    avgKw = summary.avgKw,
    peakKw = summary.peakKw,
    tMaxStart = summary.tMaxStartC,
    tMaxEnd = summary.tMaxEndC,
    tMaxPeak = tMaxPeak,
    coolingStartMs = summary.coolingStartMs,
)

// ── Aggregations ────────────────────────────────────────────────────────────

data class MonthEnergy(val month: YearMonth, val chargedKwh: Float, val dischargedKwh: Float)

/**
 * kWh charged/discharged per calendar month, from consecutive counter
 * snapshots. The counters live in the BMS, so energy used while the phone
 * wasn't connected still shows up — attributed to the month it was observed.
 * A negative delta (corrupt frame) or a missing value contributes nothing.
 */
fun monthlyEnergy(snapshots: List<CounterSnapshot>, zone: ZoneId): List<MonthEnergy> {
    val sorted = snapshots.sortedBy { it.t }
    val charged = sortedMapOf<YearMonth, Float>()
    val discharged = sortedMapOf<YearMonth, Float>()
    for (i in 1 until sorted.size) {
        val a = sorted[i - 1]; val b = sorted[i]
        val month = YearMonth.from(Instant.ofEpochMilli(b.t).atZone(zone))
        val dc = delta(a.chargedKwh, b.chargedKwh)
        val dd = delta(a.dischargedKwh, b.dischargedKwh)
        if (dc == null && dd == null) continue
        charged[month] = (charged[month] ?: 0f) + (dc ?: 0f)
        discharged[month] = (discharged[month] ?: 0f) + (dd ?: 0f)
    }
    return charged.keys.map { MonthEnergy(it, charged.getValue(it), discharged.getValue(it)) }
}

private fun delta(a: Float?, b: Float?): Float? =
    if (a != null && b != null && b >= a) b - a else null

/** Energy and SOC lost while parked between two connections. */
data class ParkedGap(
    val fromMs: Long,
    val toMs: Long,
    val dischargeKwhPerDay: Float?,
    val socPctPerDay: Float?,
)

/** Shorter gaps say more about SOC rounding than about drain. */
const val PARKED_MIN_GAP_MS = 6 * 3_600_000L
/** Standby drain on an MEB is well under this; above it, the car was driven. */
const val PARKED_MAX_KWH_PER_DAY = 2f
/** Any real charge between connections disqualifies the gap. */
const val PARKED_MAX_CHARGE_KWH = 0.5f

/**
 * Standby drain between consecutive connections, keeping only gaps where the
 * counters show the car stayed parked: long enough, no charge, and a discharge
 * rate no drive could produce.
 */
fun parkedGaps(connections: List<ConnectionRecord>): List<ParkedGap> {
    val sorted = connections.sortedBy { it.start.t }
    return (1 until sorted.size).mapNotNull { i ->
        val a = sorted[i - 1].end
        val b = sorted[i].start
        val gapMs = b.t - a.t
        if (gapMs < PARKED_MIN_GAP_MS) return@mapNotNull null
        val days = gapMs / 86_400_000f
        val charged = delta(a.chargedKwh, b.chargedKwh)
        if (charged == null || charged > PARKED_MAX_CHARGE_KWH) return@mapNotNull null
        val discharged = delta(a.dischargedKwh, b.dischargedKwh) ?: return@mapNotNull null
        val perDay = discharged / days
        if (perDay > PARKED_MAX_KWH_PER_DAY) return@mapNotNull null
        val socPerDay = if (a.socHmi != null && b.socHmi != null) (a.socHmi - b.socHmi) / days else null
        ParkedGap(a.t, b.t, perDay, socPerDay)
    }
}

/** "il y a 3 h" — how old the last-known values are. */
fun ageLabel(nowMs: Long, thenMs: Long): String {
    val min = (nowMs - thenMs) / 60_000L
    return when {
        min < 1 -> "à l'instant"
        min < 60 -> "il y a $min min"
        min < 24 * 60 -> "il y a ${min / 60} h"
        else -> "il y a ${min / (24 * 60)} j"
    }
}

// ── Line codec ──────────────────────────────────────────────────────────────

private fun line(tag: String, vararg values: Any?): String =
    (listOf(tag) + values.map { v ->
        when (v) {
            null -> ""
            // Locale.US: a French default locale would write "58,3".
            is Float -> String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
            else -> v.toString()
        }
    }).joinToString("\t")

private class Fields(private val cells: List<String>) {
    fun long(i: Int): Long? = cells.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toLongOrNull()
    fun float(i: Int): Float? = cells.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toFloatOrNull()
}

private fun fields(line: String, tag: String, count: Int): Fields? {
    val cells = line.split('\t')
    if (cells.firstOrNull() != tag || cells.size != count + 1) return null
    return Fields(cells.drop(1))
}
