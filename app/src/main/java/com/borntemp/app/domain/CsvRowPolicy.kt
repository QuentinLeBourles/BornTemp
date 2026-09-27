package com.borntemp.app.domain

/** Charging moves ~1 % SOC/min; outside a charge a row every 30 s is plenty. */
const val CSV_ROW_INTERVAL_IDLE_MS = 30_000L

/**
 * Pack average for the CSV and the UI. Only defined when both extremes were
 * read: falling back to one of them (as the old code did) wrote t_min into
 * t_avg on 3 rows of the 2026-09-26 session and faked a 0.4 °C drop.
 */
fun packAverageTemp(tMin: Float?, tMax: Float?): Float? =
    if (tMin != null && tMax != null) (tMin + tMax) / 2f else null

fun shouldWriteCsvRow(lastRowMs: Long, nowMs: Long, charging: Boolean): Boolean =
    charging || nowMs - lastRowMs >= CSV_ROW_INTERVAL_IDLE_MS

/**
 * One status cell: the status name, with the NRC code appended so the CSV
 * alone tells a requestOutOfRange (NRC_31) from a conditionsNotCorrect
 * (NRC_22). Empty when the signal wasn't part of this row.
 */
fun statusCell(reading: Reading<*>?): String = when {
    reading == null -> ""
    reading.status == ReadStatus.NRC && reading.nrc != null -> "NRC_%02X".format(reading.nrc)
    else -> reading.status.name
}
