package com.borntemp.app.obd

import com.borntemp.app.viewmodel.ChargeState
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Logs real driving and charging session data to `sessions.csv` /
 * `samples.csv`, in the exact contract `Born_to_rag`'s `ingest.py` already
 * validates. A third, independent consumer of `ObdSessionController`'s
 * per-tick data alongside `SessionCapture` and the ABRP push — additive,
 * doesn't touch either.
 *
 * Unlike `SessionCapture`, these two files accumulate across the app's
 * entire lifetime (append mode, header written once) rather than being
 * recreated per connection, so a single `adb pull` gathers every session
 * recorded so far.
 *
 * A "session" here is one charger-type segment: a new one starts whenever
 * the vehicle transitions between not-charging and AC/DC charging (or
 * between AC and DC directly), so a single continuous BLE connection that
 * goes from driving straight into charging produces two rows, not one.
 */
class ChargeSessionLogger(outputDir: File?) {

    // Pinned to UTC (unlike SessionCapture's human-facing log timestamps,
    // which intentionally use local wall-clock time) so session_id
    // generation is deterministic regardless of the device's timezone —
    // avoids a DST-transition edge case where two segments opened on the
    // same real day could otherwise collide on local wall-clock time.
    private val nameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val sessionsWriter: BufferedWriter? = outputDir?.let { openWriter(it, "sessions.csv", SESSIONS_HEADER) }
    private val samplesWriter: BufferedWriter? = outputDir?.let { openWriter(it, "samples.csv", SAMPLES_HEADER) }

    private var connectionStamp: String? = null
    private var segmentCounter = 0
    private var currentSessionId: String? = null
    private var currentChargerType: String? = null
    private var segmentSocStart: Float? = null
    private var segmentTempStartC: Float? = null
    private var segmentStartedAtMs: Long? = null
    private var lastSocPercent: Float? = null
    private var lastTimestampMs: Long? = null

    private fun openWriter(dir: File, filename: String, header: String): BufferedWriter? {
        return try {
            val target = File(dir, filename)
            val isNew = !target.exists()
            val w = BufferedWriter(FileWriter(target, /* append = */ true))
            if (isNew) {
                w.write(header)
                w.write("\n")
                w.flush()
            }
            w
        } catch (_: Exception) {
            null
        }
    }

    fun recordSample(
        timestampMs: Long,
        socPercent: Float?,
        batteryTempC: Float?,
        chargePowerKw: Float?,
        chargeCurrentA: Float?,
        chargeVoltageV: Float?,
        chargeState: ChargeState,
    ) {
        if (samplesWriter == null || sessionsWriter == null) return
        if (chargeState == ChargeState.UNKNOWN) return
        if (socPercent == null || batteryTempC == null) return

        val chargerType = when (chargeState) {
            ChargeState.AC_CHARGING -> "AC"
            ChargeState.DC_CHARGING -> "DC"
            ChargeState.NOT_CHARGING -> "UNKNOWN"
            ChargeState.UNKNOWN -> return // unreachable — guarded above
        }

        if (currentSessionId == null) {
            openSegment(timestampMs, chargerType, socPercent, batteryTempC)
        } else if (chargerType != currentChargerType) {
            closeSegment(endReasonFor(currentChargerType!!, chargerType))
            openSegment(timestampMs, chargerType, socPercent, batteryTempC)
        }

        writeSampleRow(timestampMs, socPercent, batteryTempC, chargePowerKw, chargeCurrentA, chargeVoltageV)
        lastSocPercent = socPercent
        lastTimestampMs = timestampMs
    }

    fun endConnection() {
        if (currentSessionId != null) closeSegment("presence_loss")
        connectionStamp = null
        segmentCounter = 0
    }

    private fun openSegment(timestampMs: Long, chargerType: String, socPercent: Float, batteryTempC: Float) {
        if (connectionStamp == null) {
            connectionStamp = nameFormat.format(Date(timestampMs))
            segmentCounter = 0
        } else {
            segmentCounter += 1
        }
        currentSessionId = "$connectionStamp-$segmentCounter"
        currentChargerType = chargerType
        segmentSocStart = socPercent
        segmentTempStartC = batteryTempC
        segmentStartedAtMs = timestampMs
        lastSocPercent = socPercent
        lastTimestampMs = timestampMs
    }

    private fun closeSegment(endReason: String) {
        val w = sessionsWriter ?: return
        val row = buildString {
            append(currentSessionId); append(',')
            append(segmentStartedAtMs); append(',')
            append(lastTimestampMs); append(',')
            append(f(segmentSocStart)); append(',')
            append(f(lastSocPercent)); append(',')
            append(currentChargerType); append(',')
            append(f(segmentTempStartC)); append(',')
            append(endReason); append('\n')
        }
        try {
            w.write(row)
            w.flush()
        } catch (_: Exception) { /* fail open */ }
        currentSessionId = null
        currentChargerType = null
    }

    private fun endReasonFor(oldChargerType: String, newChargerType: String): String =
        if ((oldChargerType == "AC" || oldChargerType == "DC") && newChargerType == "UNKNOWN")
            "charge_complete"
        else
            "unknown"

    private fun writeSampleRow(
        timestampMs: Long,
        socPercent: Float,
        batteryTempC: Float,
        chargePowerKw: Float?,
        chargeCurrentA: Float?,
        chargeVoltageV: Float?,
    ) {
        val w = samplesWriter ?: return
        val row = buildString {
            append(currentSessionId); append(',')
            append(timestampMs); append(',')
            append(socPercent); append(',')
            append(batteryTempC); append(',')
            append(f(chargePowerKw)); append(',')
            append(f(chargeCurrentA)); append(',')
            append(f(chargeVoltageV)); append(',')
            append(""); append('\n') // ambient_temp_c always empty — see design doc
        }
        try {
            w.write(row)
            w.flush()
        } catch (_: Exception) { /* fail open */ }
    }

    private fun f(x: Float?): String = x?.toString() ?: ""

    companion object {
        private const val SESSIONS_HEADER =
            "session_id,started_at,ended_at,soc_start,soc_end,charger_type,battery_temp_start_c,end_reason"
        private const val SAMPLES_HEADER =
            "session_id,timestamp,soc_percent,battery_temp_c,charge_power_kw,charge_current_a,charge_voltage_v,ambient_temp_c"
    }
}
