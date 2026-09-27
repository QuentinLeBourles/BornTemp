package com.borntemp.app.obd

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.borntemp.app.domain.Reading
import com.borntemp.app.domain.Signal
import com.borntemp.app.domain.UdsResult
import com.borntemp.app.domain.statusCell
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only plain-text capture of an OBD session, written to the app's
 * external Download/ directory so it can be retrieved via USB/MTP or shared
 * through a content:// URI without runtime permissions.
 *
 * Two artifacts per session:
 *   - `borntemp_<ts>.log`        — full per-frame trace (init/PID/event)
 *   - `borntemp_soh_<ts>.csv`    — one row per "reliable" SOH sample,
 *                                  for trending across sessions in a sheet
 *   - `borntemp_uds_<ts>.csv`    — one row per adapter exchange (ECU, DID,
 *                                  CAN frame sent, raw reply, latency,
 *                                  OK / NRC code / timeout): the debug trace,
 *                                  kept apart from the measurement CSV
 *
 * Failures (no external storage, IO errors) are swallowed — capture must never
 * break OBD polling.
 *
 * Lifecycle: start() once per connection, then init/pid/event/sohSample for
 * events, then close() on disconnect. Not thread-safe; the ViewModel
 * serialises calls from the polling coroutine.
 */
class SessionCapture(private val context: Context) {

    private var file: File? = null
    private var writer: BufferedWriter? = null

    private var sohFile: File? = null
    private var sohWriter: BufferedWriter? = null

    private var udsFile: File? = null
    private var udsWriter: BufferedWriter? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val nameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private val isoMsFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    companion object {
        /** CSV status column → the signal it reports on. */
        val STATUS_COLUMNS: List<Pair<String, Signal>> = listOf(
            "soc_bms_status" to Signal.SOC_BMS,
            "t_min_status" to Signal.T_MIN,
            "t_max_status" to Signal.T_MAX,
            "coolant_status" to Signal.COOLANT,
            "pump_status" to Signal.PUMP,
            "mode_status" to Signal.MODE,
            "v_hv_status" to Signal.V_HV,
            "i_hv_status" to Signal.I_HV,
            "mec_status" to Signal.MEC,
            "ec_status" to Signal.EC,
            "v_12_status" to Signal.V_12,
        )

        const val UDS_HEADER =
            "iso_time,unix_ms,ecu,command,tx_frame,rx_raw,latency_ms,status,nrc,detail\n"

        /** RFC 4180 quoting — multi-frame replies carry spaces, and a stray
         *  comma from a garbled ELM line must not shift the columns. */
        fun csvCell(v: String): String =
            if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\""
            else v
    }

    /**
     * Open a fresh capture file under getExternalFilesDir/Download/ and write
     * the header. Returns the File on success, null if external storage is
     * unavailable or IO fails.
     */
    fun start(deviceName: String?): File? {
        close()
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        if (!base.exists()) base.mkdirs()
        val now = Date()
        val stamp = nameFormat.format(now)
        val target = File(base, "borntemp_$stamp.log")
        return try {
            val w = BufferedWriter(FileWriter(target, /* append = */ false))
            w.write("# BornTemp capture\n")
            w.write("# Started: ${isoFormat.format(now)}\n")
            w.write("# Device:  ${deviceName ?: "unknown"}\n")
            w.write("# Adapter: OBDLink CX (BLE)\n")
            w.flush()
            file = target
            writer = w
            startSoh(base, stamp)
            startUds(base, stamp)
            target
        } catch (_: Exception) {
            file = null
            writer = null
            null
        }
    }

    private fun startSoh(base: File, stamp: String) {
        val target = File(base, "borntemp_soh_$stamp.csv")
        try {
            val w = BufferedWriter(FileWriter(target, /* append = */ false))
            // Header reflects exactly the fields enumerated in §4 of the
            // handoff so the user's existing spreadsheet flows still apply.
            w.write("iso_time,unix_ms,mec_kwh,ec_kwh,soc_hmi_pct,soc_bms_pct," +
                    "t_min_c,t_max_c,t_avg_c,t_coolant_in_c,t_coolant_out_c," +
                    "pump_pct,vehicle_mode,soh_pct,confidence," +
                    "v_hv_v,i_hv_a,p_kw," +
                    // Appended, never inserted: SohHistory reads by header
                    // name, and older files must keep parsing.
                    "v_12v," + STATUS_COLUMNS.joinToString(",") { it.first } + "\n")
            w.flush()
            sohFile = target
            sohWriter = w
        } catch (_: Exception) {
            sohFile = null
            sohWriter = null
        }
    }

    private fun startUds(base: File, stamp: String) {
        val target = File(base, "borntemp_uds_$stamp.csv")
        try {
            val w = BufferedWriter(FileWriter(target, /* append = */ false))
            w.write(UDS_HEADER)
            w.flush()
            udsFile = target
            udsWriter = w
        } catch (_: Exception) {
            udsFile = null
            udsWriter = null
        }
    }

    /** Append one exchange to the UDS trace. Flushed per row: the trace is
     *  most useful exactly when a session ends badly. Synchronized: the
     *  transport calls it from whichever coroutine issued the command. */
    @Synchronized
    fun uds(
        timestampMs: Long,
        ecu: String?,
        command: String,
        txFrame: String,
        response: String?,
        latencyMs: Long,
        result: UdsResult,
    ) {
        val w = udsWriter ?: return
        val row = listOf(
            isoMsFormat.format(Date(timestampMs)),
            timestampMs.toString(),
            ecu.orEmpty(),
            command,
            txFrame,
            response?.replace("\r".toRegex(), " ")?.replace(">", "")?.trim().orEmpty(),
            latencyMs.toString(),
            result.status.name,
            result.nrc?.let { "%02X".format(it) }.orEmpty(),
            result.detail.orEmpty(),
        ).joinToString(",") { csvCell(it) }
        try {
            w.write(row); w.write("\n"); w.flush()
        } catch (_: Exception) { /* fail open */ }
    }

    fun init(cmd: String, response: String?) {
        appendLine("INIT ", "${cmd.padEnd(13)} → ${displayResponse(response)}")
    }

    fun pid(pid: String, response: String?) {
        appendLine("PID  ", "${pid.padEnd(13)} ← ${displayResponse(response)}")
    }

    fun event(text: String) {
        appendLine("EVENT", text)
    }

    /** Append one SOH sample row to the per-session CSV. All numeric fields
     *  fall back to an empty cell when null so the resulting file stays
     *  spreadsheet-friendly. */
    fun sohSample(
        timestampMs: Long,
        mecKwh: Float?,
        ecKwh: Float?,
        socHmi: Float?,
        socBms: Float?,
        tMin: Float?,
        tMax: Float?,
        tAvg: Float?,
        coolantIn: Float?,
        coolantOut: Float?,
        pumpPct: Float?,
        vehicleMode: String?,
        sohPct: Float?,
        confidence: String?,
        voltageHv: Float? = null,
        currentHv: Float? = null,
        powerKw: Float? = null,
        volt12v: Float? = null,
        readings: Map<Signal, Reading<*>> = emptyMap(),
    ) {
        val w = sohWriter ?: return
        // Locale.US is not cosmetic here: the default locale on this phone is
        // French, whose decimal separator is a comma — in a comma-separated
        // file every float silently became two columns and shifted every field
        // after it. Field capture 2026-08-16 shows "360,00" where the row meant
        // 360.00 V. The timestamp formats above already pin Locale.US.
        fun f(x: Float?, digits: Int = 2) =
            if (x == null) "" else String.format(Locale.US, "%.${digits}f", x)
        val row = buildString {
            append(isoFormat.format(Date(timestampMs))); append(',')
            append(timestampMs);                          append(',')
            append(f(mecKwh, 3));                         append(',')
            append(f(ecKwh, 3));                          append(',')
            append(f(socHmi, 1));                         append(',')
            append(f(socBms, 1));                         append(',')
            append(f(tMin, 1));                           append(',')
            append(f(tMax, 1));                           append(',')
            append(f(tAvg, 1));                           append(',')
            append(f(coolantIn, 1));                      append(',')
            append(f(coolantOut, 1));                     append(',')
            append(f(pumpPct, 0));                        append(',')
            append(vehicleMode ?: "");                    append(',')
            append(f(sohPct, 2));                         append(',')
            append(confidence ?: "");                     append(',')
            append(f(voltageHv, 2));                      append(',')
            append(f(currentHv, 2));                      append(',')
            append(f(powerKw, 3));                        append(',')
            append(f(volt12v, 2))
            // One status per acquired value: an empty cell above is never
            // unexplained. Derived columns (soc_hmi, t_avg, p_kw) inherit
            // the statuses of their inputs.
            for ((_, signal) in STATUS_COLUMNS) {
                append(','); append(statusCell(readings[signal]))
            }
            append('\n')
        }
        try {
            w.write(row)
            w.flush()
        } catch (_: Exception) { /* fail open */ }
    }

    /** content:// URI for the main .log session capture. */
    fun shareUri(): Uri? = uriFor(file)

    /** content:// URI for the per-session SOH CSV history. */
    fun shareSohUri(): Uri? = uriFor(sohFile)

    /** content:// URI for the UDS debug trace. */
    fun shareUdsUri(): Uri? = uriFor(udsFile)

    /** Push buffered rows to disk without ending the session — called when
     *  the app leaves the foreground, where the process may be killed. */
    @Synchronized
    fun flush() {
        for (w in listOf(writer, sohWriter, udsWriter)) {
            try { w?.flush() } catch (_: Exception) { /* fail open */ }
        }
    }

    fun currentFile(): File? = file
    fun currentSohFile(): File? = sohFile

    fun close() {
        writer?.let { w ->
            try {
                w.write("# Ended: ${isoFormat.format(Date())}\n")
                w.flush()
                w.close()
            } catch (_: Exception) { /* fail open */ }
        }
        writer = null
        sohWriter?.let { w ->
            try { w.flush(); w.close() } catch (_: Exception) { /* fail open */ }
        }
        sohWriter = null
        udsWriter?.let { w ->
            try { w.flush(); w.close() } catch (_: Exception) { /* fail open */ }
        }
        udsWriter = null
    }

    private fun uriFor(f: File?): Uri? {
        val target = f ?: return null
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun appendLine(tag: String, content: String) {
        val w = writer ?: return
        try {
            w.write("${timeFormat.format(Date())}  $tag  $content\n")
            w.flush()
        } catch (_: Exception) { /* fail open — keep polling */ }
    }

    private fun displayResponse(response: String?): String {
        if (response.isNullOrEmpty()) return "TIMEOUT"
        return response.replace("\r", " ").replace(">", "").trim()
            .ifEmpty { "TIMEOUT" }
    }
}
