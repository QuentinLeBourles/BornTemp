package com.borntemp.app.viewmodel

import kotlin.math.max

/**
 * DC fast-charge estimator — Phase-1 algorithm spec'd in the 2026-06-21
 * design handoff (`docs/design-handoff-extracted/.../README.md`).
 *
 * Pure function over `(chargerKw, startSoc, targetSoc, batteryTemp, pack)`,
 * with no I/O and no coroutines, so it can be re-evaluated on every slider
 * tick without going through the polling pipeline. The Cockpit screen
 * keeps the controls' state UI-locally; the BatteryData is consulted only
 * to seed defaults (current SOC, current battery temperature).
 */
object ChargeEstimator {

    /** Pack usable energy used by the algorithm. Matches the brief: a 77 kWh
     *  LG-pack Cupra Born. The screen may swap this in the future when the
     *  user explicitly identifies an SK pack via the override selector. */
    const val DEFAULT_PACK_KWH = 77f

    /** Battery-side achievable charge power as a function of SoC (warm pack,
     *  77 kWh LG curve from the handoff). Returns kW. */
    fun battMax(socPct: Float): Float = when {
        socPct < 8f  -> 110f
        socPct < 25f -> 165f
        socPct < 40f -> 150f
        socPct < 55f -> 120f
        socPct < 65f -> 95f
        socPct < 75f -> 78f
        socPct < 85f -> 58f
        socPct < 92f -> 42f
        else         -> 28f
    }

    /** Temperature derate factor on the battery side (pack-cell driven). */
    fun tempFactor(batteryCelsius: Float): Float = when {
        batteryCelsius < 5f   -> 0.35f
        batteryCelsius < 15f  -> 0.6f
        batteryCelsius < 22f  -> 0.85f
        batteryCelsius <= 35f -> 1.0f
        batteryCelsius <= 42f -> 0.85f
        else                  -> 0.6f
    }

    /**
     * Ambient-temperature derate. Captures the cooling-margin effect that
     * pure cell temperature misses — verified in the 2026-06-21 field log:
     * 35 °C ambient, 37 °C pack → 70 kW observed vs ~127 kW the cell-only
     * model would predict. The same pack at 40 °C with a cool morning
     * matched the model within 5 %.
     *
     * Linear above 25 °C (cooling capacity vs. heat shedding falls off),
     * mild floor at < 5 °C (the pack steals some charge power to warm itself).
     */
    fun ambientFactor(ambientCelsius: Float): Float = when {
        ambientCelsius < 5f  -> 0.9f
        ambientCelsius <= 25f -> 1.0f
        else                 -> (1.0f - (ambientCelsius - 25f) * 0.05f).coerceAtLeast(0.5f)
    }

    data class Result(
        val timeMinutes: Float,
        val energyKwh: Float,
        val deltaSocPct: Float,
        val avgKw: Float,
        val peakKw: Float,
        val chargerLimitedRatio: Float, // 0..1 — fraction of integration steps where the charger capped the rate
        val verdict: Verdict,
        val limiterLabel: String
    )

    enum class Verdict(val label: String) {
        TRES_RAPIDE("TRÈS RAPIDE"),
        RAPIDE("RAPIDE"),
        CORRECT("CORRECT"),
        LENT("LENT")
    }

    /**
     * Integrate the charge curve in 0.5 % SoC steps. The trapezoidal-style
     * sum is simple on purpose: at this granularity the rectangular
     * approximation already matches the prototype's JS reference (each step
     * = 0.005 × pack kWh of energy, divided by the effective power to get a
     * time slice in minutes).
     *
     * Out-of-range or zero-span inputs return a "Cible déjà atteinte"
     * result with all derived fields at zero — keeps the UI from
     * crashing when the user drags Cible below Départ.
     */
    fun estimate(
        chargerKw: Float,
        startSoc: Float,
        targetSoc: Float,
        batteryCelsius: Float,
        ambientCelsius: Float = 20f,
        packKwh: Float = DEFAULT_PACK_KWH
    ): Result {
        if (targetSoc <= startSoc || chargerKw <= 0f || packKwh <= 0f) {
            return Result(0f, 0f, 0f, 0f, 0f, 0f, Verdict.LENT, "Cible déjà atteinte")
        }
        val battTemp = tempFactor(batteryCelsius)
        val ambTemp = ambientFactor(ambientCelsius)
        // Multiplicative — both factors capture different sides of the same
        // physical bottleneck (cell chemistry limit vs. cooling capacity).
        val tFactor = battTemp * ambTemp
        var time = 0f
        var energy = 0f
        var peak = 0f
        var chargerLimitedSteps = 0
        var totalSteps = 0
        var soc = startSoc
        val stepSoc = 0.5f
        val stepEnergy = 0.005f * packKwh

        while (soc < targetSoc) {
            val achievable = battMax(soc) * tFactor
            val effective = minOf(achievable, chargerKw)
            // effective can't be 0 unless chargerKw is 0 (guarded above) and
            // battMax never returns 0; still keep the guard so a bad input
            // can't loop forever.
            if (effective <= 0.01f) break
            energy += stepEnergy
            time += stepEnergy / effective * 60f
            peak = max(peak, effective)
            if (chargerKw < achievable) chargerLimitedSteps++
            totalSteps++
            soc += stepSoc
        }

        val deltaSoc = targetSoc - startSoc
        val avg = if (time > 0f) energy / (time / 60f) else 0f
        val limitedRatio = if (totalSteps > 0) chargerLimitedSteps.toFloat() / totalSteps else 0f
        val verdict = when {
            avg >= 110f -> Verdict.TRES_RAPIDE
            avg >= 80f  -> Verdict.RAPIDE
            avg >= 50f  -> Verdict.CORRECT
            else        -> Verdict.LENT
        }
        // Pick the dominant limiter to display. Ambient bridage wins over
        // battery-cold bridage when both apply, since hot-ambient slowdowns
        // are the case the user is most likely to be surprised by.
        val limiter = when {
            ambTemp < 1f && ambientCelsius > 25f ->
                "Ambient %.0f °C · cooling saturé".format(ambientCelsius)
            ambTemp < 1f && ambientCelsius < 5f ->
                "Ambient %.0f °C · warm-up batterie".format(ambientCelsius)
            battTemp < 1f && batteryCelsius < 22f ->
                "Batterie froide %.0f °C".format(batteryCelsius)
            battTemp < 1f && batteryCelsius > 35f ->
                "Batterie chaude %.0f °C".format(batteryCelsius)
            limitedRatio > 0.4f ->
                "Limité par la borne (${chargerKw.toInt()} kW)"
            else ->
                "Pleine puissance batterie"
        }

        return Result(
            timeMinutes = time,
            energyKwh = energy,
            deltaSocPct = deltaSoc,
            avgKw = avg,
            peakKw = peak,
            chargerLimitedRatio = limitedRatio,
            verdict = verdict,
            limiterLabel = limiter
        )
    }

    /** Format a minute value as the brief's two-mode display:
     *  `< 60 min` → `"32 min"`; otherwise `"1h 24"`. */
    fun formatTime(minutes: Float): String {
        if (minutes <= 0f) return "--"
        if (minutes < 60f) return "${minutes.toInt()} min"
        val h = (minutes / 60f).toInt()
        val m = (minutes - h * 60f).toInt()
        return "%dh %02d".format(h, m)
    }
}
