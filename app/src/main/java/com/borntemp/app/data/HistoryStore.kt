package com.borntemp.app.data

import com.borntemp.app.domain.ChargeRecord
import com.borntemp.app.domain.ConnectionRecord
import com.borntemp.app.domain.LastKnown
import java.io.File

/**
 * File-backed offline history, one record per line.
 *
 * Lives in the app's internal files dir (not the external Download/ captures):
 * it is small, covered by Android Auto Backup, and survives an uninstall-free
 * reinstall from backup. Every method swallows IO errors — history must never
 * break polling.
 *
 * Records in progress (the current connection, the current charge) are kept in
 * an "open" file rewritten on each update. A normal end appends them to the
 * history; if the process dies first, the next start recovers them — so a
 * killed app loses at most the last update, never the whole session.
 */
class HistoryStore(private val dir: File) {

    companion object {
        const val CONNECTIONS = "history_connections.tsv"
        const val CHARGES = "history_charges.tsv"
        const val LAST_KNOWN = "last_known.tsv"
        private const val OPEN_CONNECTION = "open_connection.tsv"
        private const val OPEN_CHARGE = "open_charge.tsv"
    }

    init {
        runCatching { dir.mkdirs() }
        recover(OPEN_CONNECTION, CONNECTIONS)
        recover(OPEN_CHARGE, CHARGES)
    }

    @Synchronized fun updateOpenConnection(r: ConnectionRecord) = write(OPEN_CONNECTION, r.encode())
    @Synchronized fun closeConnection() = recover(OPEN_CONNECTION, CONNECTIONS)
    @Synchronized fun updateOpenCharge(r: ChargeRecord) = write(OPEN_CHARGE, r.encode())
    @Synchronized fun closeCharge() = recover(OPEN_CHARGE, CHARGES)

    @Synchronized fun saveLastKnown(r: LastKnown) = write(LAST_KNOWN, r.encode())

    fun lastKnown(): LastKnown? = lines(LAST_KNOWN).firstNotNullOfOrNull { LastKnown.decode(it) }

    /** Oldest first — the order the aggregations expect. */
    fun connections(): List<ConnectionRecord> =
        lines(CONNECTIONS).mapNotNull { ConnectionRecord.decode(it) }.sortedBy { it.start.t }

    /** Newest first — the order the charge log shows. */
    fun charges(): List<ChargeRecord> =
        lines(CHARGES).mapNotNull { ChargeRecord.decode(it) }.sortedByDescending { it.startMs }

    /** Move a leftover open record into its history file, then drop it. */
    private fun recover(openName: String, historyName: String) {
        val open = File(dir, openName)
        try {
            if (!open.exists()) return
            // Line breaks only: trim() would eat a trailing tab, i.e. the
            // empty last field of a record, and the line would stop decoding.
            val content = open.readText().trimEnd('\n', '\r')
            if (content.isNotEmpty()) File(dir, historyName).appendText(content + "\n")
            open.delete()
        } catch (_: Exception) { /* fail open */ }
    }

    /** Write via a temp file + rename, so a crash mid-write can't leave half a line. */
    private fun write(name: String, line: String) {
        try {
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(line + "\n")
            if (!tmp.renameTo(File(dir, name))) {
                File(dir, name).writeText(line + "\n")
                tmp.delete()
            }
        } catch (_: Exception) { /* fail open */ }
    }

    private fun lines(name: String): List<String> = try {
        File(dir, name).takeIf { it.exists() }?.readLines().orEmpty()
    } catch (_: Exception) {
        emptyList()
    }
}
