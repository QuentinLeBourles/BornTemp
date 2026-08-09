package com.borntemp.app.viewmodel

import com.borntemp.app.obd.ObdPids

/**
 * All temperature, voltage, current and battery-health readings from the BMS
 * and EM modules.
 *
 * `*Timestamp` fields hold the wall-clock millis at which each PID was last
 * answered. The view layer can use them to grey out values that have gone
 * stale (e.g. the HV current PID freezing on a cached reply during a fast
 * charge, observed in §7 of the handoff).
 */
data class BatteryData(
    val avgTemp: Float? = null,
    val sensors: List<Float?> = List(6) { null },
    val cellTempMin: Float? = null,
    val cellTempMax: Float? = null,
    val coolantTempIn: Float? = null,
    val coolantTempOut: Float? = null,
    val coolantPumpPct: Float? = null,
    val soc: Float? = null,                // displayed (HMI) %, derived from socBms
    val socBms: Float? = null,             // raw BMS SOC (includes hidden buffers)
    val voltage: Float? = null,            // HV pack (V)
    val current: Float? = null,            // HV pack (A, signed: + = charge/regen)
    val powerKw: Float? = null,            // derived = V * I / 1000
    val currentTimestamp: Long? = null,    // wall-clock of last successful HV current read
    val cellVoltMinMv: Int? = null,
    val cellVoltMaxMv: Int? = null,
    val cellVoltDeltaMv: Int? = null,      // derived = max − min
    val cellVoltMinIdx: Int? = null,
    val cellVoltMaxIdx: Int? = null,
    val sohPct: Float? = null,             // HV battery state of health (%)
    val mecKwh: Float? = null,             // current Maximum Energy Content
    val ecKwh: Float? = null,              // current Energy Content
    val lifetimeChargeKwh: Float? = null,  // ∫charge depuis la vie du pack (DID 1E32)
    val lifetimeDischargeKwh: Float? = null, // ∫décharge cumulée
    val packType: PackType = PackType.UNKNOWN,
    val capacityOrigKwh: Float? = null,    // 77.0 (LG) or 79.9 (SK)
    val bufferTopKwh: Float? = null,
    val bufferBottomKwh: Float? = null,
    val usableKwh: Float? = null,          // MEC minus reserved buffers
    val confidence: SohConfidence = SohConfidence.UNAVAILABLE,
    val confidenceReason: String? = null,
    val vehicleMode: ObdPids.VehicleMode = ObdPids.VehicleMode.UNKNOWN,
    val volt12v: Float? = null,            // 12V control module voltage
    val chargeState: ChargeState = ChargeState.UNKNOWN,
    val timestamp: Long = 0L
)

enum class ChargeState(val label: String) {
    UNKNOWN("--"),
    NOT_CHARGING("Roulage / Arrêt"),
    AC_CHARGING("Charge AC"),
    DC_CHARGING("Charge DC (rapide)")
}

/** Sourced from the user handoff: 2021–22 LG packs reference 77.0 kWh,
 *  2023 SK packs reference 79.9 kWh, 2024+ packs require a workaround
 *  the app doesn't implement yet (no MEC reply). */
enum class PackType(val label: String, val capacityKwh: Float?) {
    UNKNOWN("Inconnu", null),
    LG_2021_22("LG (2021–22)", 77.0f),
    SK_2023("SK (2023)", 79.9f)
}

/** Buffer structure relative to MEC for the 77 kWh LG reference, from §2 of
 *  the handoff:
 *    0→100 % displayed   ≈ 71.7 kWh  (usable)
 *    0→100 % BMS         ≈ 79.5 kWh  (full pack span)
 *    bottom reserve      ≈  4 %      (≈ 5.75 % BMS at 0 % display)
 *    top reserve         ≈  6 %
 *  Values for SK packs differ; until we get field data we apply the same
 *  ratios scaled by MEC.
 */
object BatteryBuffers {
    const val BOTTOM_RESERVE_FRACTION = 0.04f
    const val TOP_RESERVE_FRACTION    = 0.06f

    fun usableKwh(mecKwh: Float): Float =
        mecKwh * (1f - BOTTOM_RESERVE_FRACTION - TOP_RESERVE_FRACTION)

    fun bottomReserveKwh(mecKwh: Float): Float = mecKwh * BOTTOM_RESERVE_FRACTION
    fun topReserveKwh(mecKwh: Float): Float    = mecKwh * TOP_RESERVE_FRACTION
}

/** Confidence in the current MEC/SOH reading, contextualised by temperature,
 *  recent charge depth, and vehicle activity (see §5 of the handoff). */
enum class SohConfidence(val label: String, val emoji: String) {
    UNAVAILABLE("Indisponible", "❔"),
    INDICATIVE("Indicatif", "🟠"),
    RELIABLE("Fiable", "🟢")
}

/**
 * Classify the MEC reading's reliability. Inputs are nullable because we want
 * to degrade gracefully (e.g. before the first temperature reading lands).
 */
fun classifySohConfidence(
    mecKwh: Float?,
    tempAvg: Float?,
    socBms: Float?,
    mode: ObdPids.VehicleMode
): Pair<SohConfidence, String?> {
    if (mecKwh == null) return SohConfidence.UNAVAILABLE to "MEC absent"
    val reasons = mutableListOf<String>()
    if (tempAvg == null || tempAvg !in 18f..28f) {
        reasons += "T batt hors 18–28 °C"
    }
    if (socBms == null || socBms < 90f) {
        reasons += "SOC < 90 % récemment"
    }
    if (mode != ObdPids.VehicleMode.STANDBY) {
        reasons += "véhicule actif"
    }
    return if (reasons.isEmpty()) {
        SohConfidence.RELIABLE to "T optimale + SOC haut + véhicule en veille"
    } else {
        SohConfidence.INDICATIVE to reasons.joinToString(", ")
    }
}

/**
 * Heuristic pack identification. Until we trust the MEC value to within a few
 * %, we rely on the BMS-side capacity report (MEC at high SOC). MEC under
 * 78 kWh ⇒ LG reference; above ⇒ SK reference. The user can override later.
 */
fun guessPackType(mecKwh: Float?): PackType {
    if (mecKwh == null) return PackType.UNKNOWN
    return if (mecKwh >= 78f) PackType.SK_2023 else PackType.LG_2021_22
}

/**
 * Reference capacity (kWh) that SOH and the charge ETA are scaled against.
 *
 * MEC is mute on this Born, so [guessPackType] answers UNKNOWN and its
 * [PackType.capacityKwh] is null — which used to null out SOH, the buffers and
 * every ETA at once. Falling back to the pack size the charge estimator already
 * assumes keeps those alive; the number only scales results, it is never shown
 * as a measurement.
 */
fun referenceCapacityKwh(packType: PackType): Float =
    packType.capacityKwh ?: ChargeEstimator.DEFAULT_PACK_KWH

/**
 * Confidence for a SOH figure, tagged by where its capacity came from: a real
 * MEC reading (graded by [classifySohConfidence]), else the charge integrator's
 * measured mid-range pass, else nothing to report.
 */
fun classifyCapacityProvenance(
    mecKwh: Float?,
    integratedKwh: Float?,
    tempAvg: Float?,
    socBms: Float?,
    mode: ObdPids.VehicleMode
): Pair<SohConfidence, String?> = when {
    mecKwh != null -> classifySohConfidence(mecKwh, tempAvg, socBms, mode)
    integratedKwh != null ->
        SohConfidence.INDICATIVE to "capacité intégrée sur une passe 30–70 % de SOC"
    else ->
        SohConfidence.UNAVAILABLE to "MEC absent, aucune passe de charge 30–70 %"
}

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    INITIALIZING,
    CONNECTED,
    ERROR
}

enum class AutoConnectStatus {
    ARMED,
    NO_DEVICE_SAVED,
    NO_PERMISSION,
    BLUETOOTH_OFF
}

data class UiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val batteryData: BatteryData = BatteryData(),
    val selectedDevice: String? = null,
    val errorMessage: String? = null,
    val logEntries: List<LogEntry> = emptyList(),
    val isPolling: Boolean = false,
    val pollingIntervalMs: Long = 5000L,
    val abrp: AbrpUiState = AbrpUiState(),
    val packTypeOverride: PackTypeOverride = PackTypeOverride.AUTO,
    val captureFileUri: android.net.Uri? = null,
    val captureFileName: String? = null,
    val sohHistoryFileUri: android.net.Uri? = null,
    val sohHistoryFileName: String? = null,
    val chargeProjection: ChargeProjection = ChargeProjection(),
    val thermalTrajectory: ThermalTrajectory = ThermalTrajectory(
        slopeCPerMin = null,
        minutesToOptimal = null,
        minutesToHot = null,
        advice = ThermalAdvice.NONE,
        adviceDetail = null
    )
)

/** Snapshot of the Handoff-2 Feature A — ETA toward target SoCs. */
data class ChargeProjection(
    val visible: Boolean = false,
    val avgPowerKw: Float? = null,
    val socSlopePctPerMin: Float? = null,
    val etaMinutesTo80: Float? = null,
    val etaMinutesTo100: Float? = null,
    val apparentCapacityKwh: Float? = null
)

data class AbrpUiState(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val userToken: String = "",
    val lastSendOk: Boolean? = null,
    val lastSendMessage: String? = null,
    val lastSendTimeMs: Long? = null
)

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val level: LogLevel = LogLevel.INFO
)

enum class LogLevel { INFO, OK, WARN, ERROR }

/**
 * Temperature classification for the Born 77kWh battery.
 * Optimal operating range: 15–35°C
 * Charging limited below 10°C and above 40°C
 *
 * Note: this is the "pure" thermal classification. For the live UI a refined
 * version cross-references the HV pump duty to flag derating risk; see
 * [classifyThermalStatus].
 */
fun classifyTemperature(temp: Float?): TempClass = when {
    temp == null   -> TempClass.UNKNOWN
    temp < 0f      -> TempClass.CRITICAL_COLD
    temp < 10f     -> TempClass.COLD
    temp < 15f     -> TempClass.COOL
    temp < 35f     -> TempClass.OPTIMAL
    temp < 40f     -> TempClass.WARM
    temp < 45f     -> TempClass.HOT
    else           -> TempClass.CRITICAL_HOT
}

/**
 * Refined thermal status that accounts for the HV cooling pump's PWM duty
 * cycle (DID 0x743B). If the pack is hot AND the pump is saturated, we treat
 * that as derating risk regardless of whether T is technically still below
 * the 40 °C "limit"; conversely if the pump is at rest we trust the raw band.
 * Implements §7's suggestion to anchor on derating reality rather than fixed
 * bands.
 */
fun classifyThermalStatus(temp: Float?, pumpPct: Float?): TempClass {
    val base = classifyTemperature(temp)
    if (temp == null) return base
    if (temp >= 35f && (pumpPct ?: 0f) >= 80f) {
        return if (base.ordinal >= TempClass.HOT.ordinal) base else TempClass.HOT
    }
    return base
}

enum class TempClass(val label: String, val emoji: String) {
    UNKNOWN("En attente", ""),
    CRITICAL_COLD("Critique - Gel", "🔵"),
    COLD("Froide", "🔵"),
    COOL("Fraîche", "🩵"),
    OPTIMAL("Optimale", "🟢"),
    WARM("Chaude", "🟡"),
    HOT("Très chaude", "🔴"),
    CRITICAL_HOT("Critique - Surchauffe", "🔴")
}
