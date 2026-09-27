package com.borntemp.app.domain

/**
 * ABRP telemetry runs on its own 1 s loop, decoupled from OBD polling: GPS and
 * speed refresh every second (what ABRP's live tracking needs), battery values
 * at the poll rate. Sends are sequential, so a slow network stretches the
 * period instead of piling up requests.
 */
const val ABRP_PERIOD_MS = 1_000L

fun abrpNextDelayMs(sendDurationMs: Long, periodMs: Long = ABRP_PERIOD_MS): Long =
    (periodMs - sendDurationMs).coerceAtLeast(0L)

/** At 1 Hz a dead network would write a warning every second; log the
 *  transition into failure only. */
fun shouldLogAbrpFailure(previousOk: Boolean?, ok: Boolean): Boolean =
    !ok && previousOk != false
