package com.borntemp.app.viewmodel

import com.borntemp.app.obd.ObdPids
import kotlin.math.abs

/**
 * Rolling-window helpers that turn the live polling stream into the
 * features described in Handoff 2:
 *   - smoothed charging power and SoC slope (Phase-1 ETA)
 *   - thermal slope `dT/dt` (Phase-1 thermal trajectory)
 *   - integrated energy across mid-range SoC bands (cross-check MEC)
 *
 * Stored as a fixed-size ring of timestamped samples — the viewmodel pushes
 * one entry per polling tick and reads the derived quantities back. The
 * window is intentionally short (default 120 s) so paliers de puissance and
 * thermal saturation are detected within a few ticks rather than smeared
 * over a whole session.
 */
class ChargeAnalytics(
    private val maxAgeMs: Long = 120_000L,
    private val maxSamples: Int = 240,
) {

    data class Sample(
        val t: Long,
        val socHmi: Float?,
        val socBms: Float?,
        val tempAvg: Float?,
        val powerKw: Float?,
        val voltage: Float?,
        val current: Float?,
        val mode: ObdPids.VehicleMode,
    )

    private val samples = ArrayDeque<Sample>()
    private val integrator = ChargeEnergyIntegrator()

    fun push(s: Sample) {
        samples.addLast(s)
        // Trim by both window size and recency. Both bounds matter: a
        // stationary car polling at 5 s can still flood the deque if
        // the user leaves the app open for hours.
        while (samples.size > maxSamples) samples.removeFirst()
        val cutoff = s.t - maxAgeMs
        while (samples.isNotEmpty() && samples.first().t < cutoff) samples.removeFirst()
        integrator.feed(s)
    }

    fun reset() {
        samples.clear()
        integrator.reset()
    }

    /** Mean power over the last [windowMs] ms — robust to a single missing
     *  reading (skipped) but returns null if the whole window is empty. */
    fun avgPowerKw(windowMs: Long = 60_000L): Float? {
        val now = samples.lastOrNull()?.t ?: return null
        val cutoff = now - windowMs
        val window = samples.filter { it.t >= cutoff && it.powerKw != null }
        if (window.isEmpty()) return null
        return window.sumOf { it.powerKw!!.toDouble() }.toFloat() / window.size
    }

    /** Linear-fit slope of the SoC HMI value, expressed in %/min. Positive
     *  when charging. Returns null if we have < 2 valid points or a
     *  span shorter than 30 s. */
    fun socSlopePctPerMin(): Float? = slopePerMin(samples) { it.socHmi }

    /** Linear-fit slope of T_batt_avg, expressed in °C/min. */
    fun tempSlopeCPerMin(): Float? = slopePerMin(samples) { it.tempAvg }

    /** Latest sample, or null if the analytics buffer is empty. */
    fun latest(): Sample? = samples.lastOrNull()

    /**
     * Heuristic ETA (minutes) from the current SoC to [targetSocPct].
     * Uses MEC-derived capacity and a smoothed power. Returns null if any
     * input is missing, the gap is non-positive, or the car isn't actively
     * charging.
     */
    fun etaMinutesTo(
        targetSocPct: Float,
        socNowPct: Float?,
        mecKwh: Float?,
        chargeState: ChargeState,
        windowMs: Long = 60_000L,
    ): Float? {
        if (chargeState != ChargeState.AC_CHARGING &&
            chargeState != ChargeState.DC_CHARGING) return null
        if (socNowPct == null || mecKwh == null) return null
        val avgP = avgPowerKw(windowMs) ?: return null
        if (avgP <= 1f) return null
        val gapPct = targetSocPct - socNowPct
        if (gapPct <= 0f) return 0f
        // Charging power is reported with the handoff sign convention
        // (+ = charge); we still abs() so a momentary regen sample in
        // the window doesn't flip the ETA sign.
        return gapPct / 100f * mecKwh / abs(avgP) * 60f
    }

    fun energyIntegrator(): ChargeEnergyIntegrator = integrator

    /**
     * Simple linear regression `slope = Σ(x−x̄)(y−ȳ) / Σ(x−x̄)²`,
     * x in minutes, y from [extract]. Drops samples where [extract] is
     * null. Returns null if there aren't enough valid samples or the
     * time span is too short to fit credibly (< 30 s).
     */
    private inline fun slopePerMin(
        deque: ArrayDeque<Sample>,
        crossinline extract: (Sample) -> Float?
    ): Float? {
        val pts = deque.mapNotNull { s ->
            val y = extract(s) ?: return@mapNotNull null
            (s.t / 60_000.0) to y.toDouble()
        }
        if (pts.size < 4) return null
        val tMin = pts.first().first
        val tMax = pts.last().first
        if ((tMax - tMin) * 60.0 < 30.0) return null
        val xMean = pts.sumOf { it.first } / pts.size
        val yMean = pts.sumOf { it.second } / pts.size
        var num = 0.0
        var den = 0.0
        for ((x, y) in pts) {
            val dx = x - xMean
            num += dx * (y - yMean)
            den += dx * dx
        }
        if (den == 0.0) return null
        return (num / den).toFloat()
    }
}

/**
 * Energy integrator used to cross-check the MEC reading.
 *
 * We only integrate while the car is in a CHARGING_* mode and only inside
 * mid-range SoC bands (default 30–70 %). The handoff warns that extremes
 * inflate the apparent capacity because the displayed SoC compresses
 * around the buffers. Whenever the (lowSoc..highSoc) interval is fully
 * observed (both endpoints crossed monotonically), we emit a derived
 * capacity estimate.
 */
class ChargeEnergyIntegrator(
    private val midRange: ClosedFloatingPointRange<Float> = 30f..70f,
) {

    /** One observation point during a charge. */
    private data class Point(val t: Long, val soc: Float, val powerKw: Float)

    private var segment: MutableList<Point>? = null
    private var lastResult: Result? = null

    data class Result(
        val energyKwhAdded: Float,
        val socDeltaPct: Float,
        val apparentCapacityKwh: Float,
        val timestampMs: Long
    )

    fun feed(s: ChargeAnalytics.Sample) {
        val charging = s.mode == ObdPids.VehicleMode.CHARGING_AC ||
                       s.mode == ObdPids.VehicleMode.CHARGING_DC
        if (!charging) {
            // Charging interrupted — flush whatever we accumulated. If the
            // session never covered the full mid-range we just drop it;
            // capacity estimation across a partial sweep is too noisy.
            tryEmit()
            segment = null
            return
        }
        val soc = s.socHmi ?: return
        val p = s.powerKw ?: return
        if (soc !in midRange) {
            // We can only emit a result once a complete mid-range pass
            // happened, so out-of-band readings get used to close the
            // segment if it's already running.
            if (segment != null) tryEmit()
            if (soc < midRange.start) segment = mutableListOf()
            return
        }
        val seg = segment ?: mutableListOf<Point>().also { segment = it }
        seg += Point(s.t, soc, p)
    }

    private fun tryEmit() {
        val seg = segment ?: return
        if (seg.size < 4) return
        val first = seg.first()
        val last = seg.last()
        val socDelta = last.soc - first.soc
        if (socDelta < (midRange.endInclusive - midRange.start) * 0.5f) return
        // Trapezoidal integration of power · dt.
        var energyKwh = 0f
        for (i in 1 until seg.size) {
            val a = seg[i - 1]; val b = seg[i]
            val dtH = (b.t - a.t) / 3_600_000f
            energyKwh += (a.powerKw + b.powerKw) / 2f * dtH
        }
        if (energyKwh <= 0f) return
        val capacity = energyKwh / (socDelta / 100f)
        lastResult = Result(
            energyKwhAdded = energyKwh,
            socDeltaPct = socDelta,
            apparentCapacityKwh = capacity,
            timestampMs = last.t
        )
    }

    fun reset() {
        segment = null
        lastResult = null
    }

    fun lastResult(): Result? = lastResult
}

// ── Thermal trajectory advice ─────────────────────────────────────────────

enum class ThermalAdvice(val label: String) {
    NONE("--"),
    DRIVE_BEFORE_CHARGING("Pré-roule ~10 min, le pack est froid"),
    POSTPONE_CHARGE("Batterie chaude — la charge sera bridée, reporte si possible"),
    CHARGE_DERATED("Charge en cours bridée par la thermique"),
    OPTIMAL("Fenêtre thermique optimale")
}

data class ThermalTrajectory(
    val slopeCPerMin: Float?,
    val minutesToOptimal: Float?,   // if cold, time to reach 15 °C
    val minutesToHot: Float?,       // if heating fast, time to reach 40 °C
    val advice: ThermalAdvice,
    val adviceDetail: String?
)

/**
 * Build a thermal trajectory snapshot from current temperature, slope, mode
 * and (optional) coolant info. Phase-1 heuristic — uses §5/§7 of the SOH
 * handoff + the data points in Handoff 2 (0.45 °C/min at moderate temp,
 * 0.9 °C/min at 100 kW / 35 °C).
 *
 * Returns NONE advice when the picture is incomplete rather than hallucinating.
 */
fun classifyThermalTrajectory(
    tempAvg: Float?,
    slopeCPerMin: Float?,
    chargeState: ChargeState,
    pumpPct: Float?,
): ThermalTrajectory {
    val optimalLow = 15f
    val hotThreshold = 40f

    val minutesToOptimal = if (tempAvg != null && tempAvg < optimalLow && slopeCPerMin != null && slopeCPerMin > 0.05f) {
        (optimalLow - tempAvg) / slopeCPerMin
    } else null
    val minutesToHot = if (tempAvg != null && tempAvg in 25f..hotThreshold && slopeCPerMin != null && slopeCPerMin > 0.05f) {
        (hotThreshold - tempAvg) / slopeCPerMin
    } else null

    val charging = chargeState == ChargeState.AC_CHARGING ||
                   chargeState == ChargeState.DC_CHARGING

    val (advice, detail) = when {
        tempAvg == null ->
            ThermalAdvice.NONE to null
        tempAvg < 10f && chargeState == ChargeState.NOT_CHARGING ->
            ThermalAdvice.DRIVE_BEFORE_CHARGING to
                "Pack à %.1f °C : un préroulage débloque la puissance de charge.".format(tempAvg)
        tempAvg > 38f && !charging ->
            ThermalAdvice.POSTPONE_CHARGE to
                "Pack à %.1f °C : la session sera plafonnée par le bridage thermique.".format(tempAvg)
        tempAvg > 38f && charging && (pumpPct ?: 0f) >= 80f ->
            ThermalAdvice.CHARGE_DERATED to
                "Refroidissement saturé (pompe %.0f %%). La fenêtre optimale ne sera pas atteinte avant la fin de charge.".format(pumpPct ?: 100f)
        tempAvg in optimalLow..35f ->
            ThermalAdvice.OPTIMAL to "Fenêtre thermique idéale pour la performance et la charge."
        else ->
            ThermalAdvice.NONE to null
    }

    return ThermalTrajectory(
        slopeCPerMin = slopeCPerMin,
        minutesToOptimal = minutesToOptimal,
        minutesToHot = minutesToHot,
        advice = advice,
        adviceDetail = detail
    )
}
