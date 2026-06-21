package com.borntemp.app.viewmodel

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One historical SOH sample read back from a `borntemp_soh_*.csv` file.
 * All numeric columns are nullable because the CSV uses empty cells
 * (rather than a sentinel) when a value couldn't be read.
 */
data class SohHistoryEntry(
    val timestampMs: Long,
    val sohPct: Float?,
    val mecKwh: Float?,
    val tempAvgC: Float?,
    val socBmsPct: Float?,
    val confidence: SohConfidence,
    val sourceFile: String
)

/**
 * Reads every borntemp_soh_*.csv under the app-private Download dir and
 * returns a flat, time-sorted list of entries suitable for charting.
 *
 * The reader is defensive — files that can't be parsed are skipped, and
 * any row that lacks both timestamp and SOH is dropped (would just
 * pollute the chart). Anything else is best-effort: missing temperature
 * or confidence becomes null/UNAVAILABLE.
 */
object SohHistoryReader {

    private val isoFormats = listOf(
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
    )

    fun loadAll(context: Context): List<SohHistoryEntry> {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return emptyList()
        if (!base.exists()) return emptyList()
        val files = base.listFiles { f ->
            f.isFile && f.name.startsWith("borntemp_soh_") && f.name.endsWith(".csv")
        }?.toList().orEmpty()
        return files.flatMap { parseOne(it) }.sortedBy { it.timestampMs }
    }

    private fun parseOne(file: File): List<SohHistoryEntry> {
        val out = mutableListOf<SohHistoryEntry>()
        try {
            file.useLines { seq ->
                val iterator = seq.iterator()
                if (!iterator.hasNext()) return emptyList()
                val header = iterator.next().split(',').map { it.trim() }
                val idx = ColumnIndex.from(header)
                while (iterator.hasNext()) {
                    val raw = iterator.next()
                    if (raw.isBlank()) continue
                    val cols = raw.split(',')
                    val ts = parseTimestamp(cols.getOr(idx.isoTime), cols.getOr(idx.unixMs))
                        ?: continue
                    val sohPct = cols.getOr(idx.sohPct)?.toFloatOrNullSafe()
                    val mecKwh = cols.getOr(idx.mecKwh)?.toFloatOrNullSafe()
                    val tAvg = cols.getOr(idx.tAvg)?.toFloatOrNullSafe()
                    val socBms = cols.getOr(idx.socBms)?.toFloatOrNullSafe()
                    if (sohPct == null && mecKwh == null) continue
                    out += SohHistoryEntry(
                        timestampMs = ts,
                        sohPct = sohPct,
                        mecKwh = mecKwh,
                        tempAvgC = tAvg,
                        socBmsPct = socBms,
                        confidence = parseConfidence(cols.getOr(idx.confidence)),
                        sourceFile = file.name
                    )
                }
            }
        } catch (_: Exception) {
            // swallow — a malformed file shouldn't poison the whole history
        }
        return out
    }

    private fun parseTimestamp(iso: String?, unixMs: String?): Long? {
        unixMs?.toLongOrNull()?.let { return it }
        if (iso.isNullOrBlank()) return null
        for (fmt in isoFormats) {
            try { return fmt.parse(iso)?.time } catch (_: Exception) {}
        }
        return null
    }

    private fun parseConfidence(s: String?): SohConfidence = when (s?.trim()) {
        "RELIABLE"    -> SohConfidence.RELIABLE
        "INDICATIVE"  -> SohConfidence.INDICATIVE
        else          -> SohConfidence.UNAVAILABLE
    }

    private fun List<String>.getOr(idx: Int?): String? =
        if (idx == null || idx < 0 || idx >= size) null else this[idx].trim().ifEmpty { null }

    private fun String.toFloatOrNullSafe(): Float? =
        try { this.replace(',', '.').toFloat() } catch (_: NumberFormatException) { null }

    private data class ColumnIndex(
        val isoTime: Int?,
        val unixMs: Int?,
        val sohPct: Int?,
        val mecKwh: Int?,
        val tAvg: Int?,
        val socBms: Int?,
        val confidence: Int?,
    ) {
        companion object {
            fun from(header: List<String>): ColumnIndex {
                fun col(name: String): Int? = header.indexOfFirst { it.equals(name, ignoreCase = true) }
                    .takeIf { it >= 0 }
                return ColumnIndex(
                    isoTime    = col("iso_time"),
                    unixMs     = col("unix_ms"),
                    sohPct     = col("soh_pct"),
                    mecKwh     = col("mec_kwh"),
                    tAvg       = col("t_avg_c"),
                    socBms     = col("soc_bms_pct"),
                    confidence = col("confidence"),
                )
            }
        }
    }
}

/**
 * Aggregated view of the historical data used by the trend chart.
 * The chart works in normalised [0..1] coordinates internally; this
 * struct hands the composable the raw values it needs to label axes.
 */
data class SohTrendSnapshot(
    val entries: List<SohHistoryEntry>,
    val minSoh: Float,
    val maxSoh: Float,
    val firstTs: Long,
    val lastTs: Long,
    val firstSoh: Float?,
    val lastSoh: Float?,
    val sampleCount: Int,
    val sessionCount: Int,
) {
    companion object {
        fun from(entries: List<SohHistoryEntry>): SohTrendSnapshot {
            val withSoh = entries.filter { it.sohPct != null }
            val mins = withSoh.minOfOrNull { it.sohPct!! } ?: 0f
            val maxs = withSoh.maxOfOrNull { it.sohPct!! } ?: 100f
            val padded = (maxs - mins).coerceAtLeast(2f)
            // Padding so dots aren't pinned to the chart edges when all
            // readings are clustered in a 1 % window — common in fresh packs.
            return SohTrendSnapshot(
                entries = entries,
                minSoh = (mins - padded * 0.15f).coerceAtLeast(0f),
                maxSoh = (maxs + padded * 0.15f).coerceAtMost(110f),
                firstTs = entries.firstOrNull()?.timestampMs ?: 0L,
                lastTs = entries.lastOrNull()?.timestampMs ?: 0L,
                firstSoh = withSoh.firstOrNull()?.sohPct,
                lastSoh = withSoh.lastOrNull()?.sohPct,
                sampleCount = entries.size,
                sessionCount = entries.map { it.sourceFile }.distinct().size,
            )
        }
    }
}
