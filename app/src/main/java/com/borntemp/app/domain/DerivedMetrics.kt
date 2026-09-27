package com.borntemp.app.domain

// Derived battery metrics. Pure functions only: the controller feeds them
// readings, the UI and the CSV read the results.

/**
 * Thermal picture of the pack against its coolant loop.
 *
 * - [headroomC]   `t_max − t_coolant_in`: how much colder the fluid entering
 *                 the pack is than its hottest cell — the margin the loop can
 *                 still pull heat through.
 * - [extractionC] `t_coolant_out − t_coolant_in`: heat the fluid is actually
 *                 carrying away.
 * - [spreadC]     `t_max − t_min`: cell temperature imbalance.
 * - [activeCooling] outlet warmer than inlet, i.e. the loop is extracting heat.
 *
 * Each field is null when one of its inputs is missing, never a guess.
 */
data class ThermalMetrics(
    val headroomC: Float?,
    val extractionC: Float?,
    val spreadC: Float?,
    val activeCooling: Boolean?,
)

fun thermalMetrics(tMin: Float?, tMax: Float?, coolantIn: Float?, coolantOut: Float?): ThermalMetrics {
    val extraction = if (coolantIn != null && coolantOut != null) coolantOut - coolantIn else null
    return ThermalMetrics(
        headroomC = if (tMax != null && coolantIn != null) tMax - coolantIn else null,
        extractionC = extraction,
        spreadC = if (tMax != null && tMin != null) tMax - tMin else null,
        activeCooling = extraction?.let { it > 0f },
    )
}

/**
 * Least-squares slope, per minute, of the points in the last [windowMs]
 * (relative to the newest point). Null with fewer than two points or a span
 * under 30 s — too short for a credible slope at the BMS's 0.4 % SOC steps.
 */
fun ratePerMin(points: List<Pair<Long, Float>>, windowMs: Long): Float? {
    val newest = points.maxOfOrNull { it.first } ?: return null
    val pts = points.filter { it.first >= newest - windowMs }
    if (pts.size < 2) return null
    if (pts.maxOf { it.first } - pts.minOf { it.first } < MIN_RATE_SPAN_MS) return null
    val xs = pts.map { it.first / 60_000.0 }
    val ys = pts.map { it.second.toDouble() }
    val xMean = xs.average()
    val yMean = ys.average()
    var num = 0.0
    var den = 0.0
    for (i in xs.indices) {
        val dx = xs[i] - xMean
        num += dx * (ys[i] - yMean)
        den += dx * dx
    }
    return if (den == 0.0) null else (num / den).toFloat()
}

private const val MIN_RATE_SPAN_MS = 30_000L

enum class PowerSource { MEASURED, ESTIMATED }

/** Pack power, + into the pack. [source] drives the "estimée" badge. */
data class PowerReading(val kw: Float, val source: PowerSource)

/**
 * `V × I` when the pack current was read; otherwise the energy implied by the
 * HMI SOC slope: `%/min ÷ 100 × capacity × 60` kW, marked ESTIMATED.
 *
 * [capacityKwh] must be energy per 100 % *HMI* SOC — the measured capacity
 * (~72 kWh on this pack) rather than the 77 kWh nameplate, which would read
 * ~7 % high.
 */
fun resolvePower(voltageV: Float?, currentA: Float?, socSlopePctPerMin: Float?, capacityKwh: Float): PowerReading? {
    if (voltageV != null && currentA != null) {
        return PowerReading(voltageV * currentA / 1000f, PowerSource.MEASURED)
    }
    val slope = socSlopePctPerMin ?: return null
    return PowerReading(slope / 100f * capacityKwh * 60f, PowerSource.ESTIMATED)
}

/** One tick's worth of what a session summary needs. */
data class SessionSample(
    val timestampMs: Long,
    val socHmi: Float?,
    val tMin: Float?,
    val tMax: Float?,
    val coolantIn: Float?,
    val coolantOut: Float?,
    val power: PowerReading?,
)

/** Timestamp of the first sample where the loop extracts heat, or null. */
fun firstActiveCoolingMs(samples: List<SessionSample>): Long? =
    samples.firstOrNull {
        thermalMetrics(it.tMin, it.tMax, it.coolantIn, it.coolantOut).activeCooling == true
    }?.timestampMs

/**
 * Session tab figures. Energy is the trapezoidal integral of power over
 * consecutive samples that both carry one; [energySource] is ESTIMATED as soon
 * as any estimated sample contributed, so the badge never over-claims.
 */
data class SessionSummary(
    val startMs: Long,
    val durationMs: Long,
    val energyKwh: Float?,
    val energySource: PowerSource?,
    val avgKw: Float?,
    val peakKw: Float?,
    val tMaxStartC: Float?,
    val tMaxEndC: Float?,
    val socStart: Float?,
    val socEnd: Float?,
    val coolingStartMs: Long?,
)

fun summarizeSession(samples: List<SessionSample>): SessionSummary? {
    if (samples.isEmpty()) return null
    val sorted = samples.sortedBy { it.timestampMs }
    var energy = 0.0
    var poweredMs = 0L
    var anyEstimated = false
    var anyEnergy = false
    for (i in 1 until sorted.size) {
        val a = sorted[i - 1].power ?: continue
        val b = sorted[i].power ?: continue
        val dtMs = sorted[i].timestampMs - sorted[i - 1].timestampMs
        energy += (a.kw + b.kw) / 2.0 * dtMs / 3_600_000.0
        poweredMs += dtMs
        anyEnergy = true
        if (a.source == PowerSource.ESTIMATED || b.source == PowerSource.ESTIMATED) anyEstimated = true
    }
    val powers = sorted.mapNotNull { it.power }
    val energyKwh = if (anyEnergy) energy.toFloat() else null
    return SessionSummary(
        startMs = sorted.first().timestampMs,
        durationMs = sorted.last().timestampMs - sorted.first().timestampMs,
        energyKwh = energyKwh,
        energySource = when {
            !anyEnergy -> null
            anyEstimated -> PowerSource.ESTIMATED
            else -> PowerSource.MEASURED
        },
        avgKw = energyKwh?.takeIf { poweredMs > 0 }?.let { it / (poweredMs / 3_600_000f) },
        peakKw = powers.maxOfOrNull { it.kw },
        tMaxStartC = sorted.firstNotNullOfOrNull { it.tMax },
        tMaxEndC = sorted.lastOrNull { it.tMax != null }?.tMax,
        socStart = sorted.firstNotNullOfOrNull { it.socHmi },
        socEnd = sorted.lastOrNull { it.socHmi != null }?.socHmi,
        coolingStartMs = firstActiveCoolingMs(sorted),
    )
}
