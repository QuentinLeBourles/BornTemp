package com.borntemp.app.domain

/**
 * One acquired value with the reason it is (or isn't) there. [value] is
 * non-null only when [status] is OK, so an empty field always comes with a
 * status saying why.
 */
data class Reading<out T>(
    val value: T?,
    val status: ReadStatus,
    val timestampMs: Long,
    val detail: String? = null,
) {
    val isOk: Boolean get() = status == ReadStatus.OK && value != null

    /** This reading if it holds a value, else [fallback]. Slow-tick signals
     *  keep their last good reading — with its original timestamp, so the UI
     *  can still tell how old it is. */
    fun heldOr(fallback: Reading<@UnsafeVariance T>): Reading<T> = if (isOk) this else fallback

    companion object {
        fun <T> notSupported(timestampMs: Long, detail: String? = null): Reading<T> =
            Reading(null, ReadStatus.NOT_SUPPORTED, timestampMs, detail)

        fun <T> ok(value: T, timestampMs: Long): Reading<T> =
            Reading(value, ReadStatus.OK, timestampMs)
    }
}

/** Build a reading from a classified reply and its parsed value. */
fun <T> readingOf(result: UdsResult, parsed: T?, timestampMs: Long): Reading<T> = when {
    result.status != ReadStatus.OK -> Reading(null, result.status, timestampMs, result.detail)
    parsed == null -> Reading(null, ReadStatus.PARSE_ERROR, timestampMs, "unparsed")
    else -> Reading(parsed, ReadStatus.OK, timestampMs)
}

/**
 * Per-PID acquisition policy. [timeoutMs] bounds how long one PID may hold
 * the cycle; [retries] is extra attempts on TIMEOUT only.
 */
data class PidPolicy(val timeoutMs: Long, val retries: Int = 0) {
    companion object {
        /** Single-frame BMS reads answer in 60–150 ms; 2 s leaves room for
         *  the ELM's own ~0.6 s CAN timeout plus BLE, and stops one dead PID
         *  from eating 5 s of the cycle as the old global timeout did. */
        val FAST = PidPolicy(timeoutMs = 2_000L)
        /** Multi-frame replies (1E32 is 3 frames). */
        val MULTI_FRAME = PidPolicy(timeoutMs = 3_000L)
    }
}

fun shouldRetry(status: ReadStatus, attempt: Int, policy: PidPolicy): Boolean =
    status == ReadStatus.TIMEOUT && attempt < policy.retries

/**
 * Poll cadence by activity. Charging moves fast (≈1 % SOC/min at 100 kW), so
 * it gets 5–10 s; driving and standby get a looser, user-set interval.
 */
class PollIntervals(chargingMs: Long, otherMs: Long) {
    val chargingMs: Long = chargingMs.coerceIn(CHARGING_RANGE)
    val otherMs: Long = otherMs.coerceIn(OTHER_RANGE)

    fun forMode(charging: Boolean): Long = if (charging) chargingMs else otherMs

    companion object {
        val CHARGING_RANGE = 5_000L..10_000L
        val OTHER_RANGE = 2_000L..60_000L
    }
}

/** Every signal the app acquires; keys the per-signal status map. */
enum class Signal(val label: String) {
    T_MAX("T max"),
    T_MIN("T min"),
    SOC_BMS("SOC BMS"),
    V_HV("Tension HV"),
    I_HV("Courant HV"),
    MODE("Mode véhicule"),
    PUMP("Pompe"),
    COOLANT("Fluide entrée/sortie"),
    CELL_MIN("Cellule min"),
    CELL_MAX("Cellule max"),
    MEC("MEC"),
    EC("EC"),
    V_12("Tension 12 V"),
    LIFETIME_ENERGY("Énergie cumulée"),
}
