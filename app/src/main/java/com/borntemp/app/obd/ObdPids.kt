package com.borntemp.app.obd

/**
 * OBD/UDS PIDs for the Cupra Born 77kWh (MEB platform)
 *
 * Protocol  : ISO 15765-4 CAN, 29-bit extended addressing, 500 kbps
 * BMS req   : 0x17FC007B   resp: 0x17FE007B
 * EM  req   : 0x17FC0010   resp: 0x17FE0010   (energy mgmt / 12V / MEC / EC)
 * Functional broadcast resp: 0x18DAF1xx (engine ECU et al.)
 *
 * Sources reconciled against a live capture from 2026-06-18 on the user's car:
 *   - spot2000/Volkswagen-MEB-EV-CAN-parameters (authoritative CSV)
 *   - raimuucka/OBD2-Volkswagen-MEB-EV-CAN-parameters (fork)
 *   - nickn17/evDash CarVWID3.cpp
 *   - PowerBroker2/ELMduino issue #207
 *   - jagheterfredrik/meb-preheat NOTES.md
 */
object ObdPids {

    // ── ECU targets ─────────────────────────────────────────────────────────
    //
    // A target groups every header/filter the ELM327 needs reset when we
    // switch ECUs. ATCP17 is sticky from init, so the 6-char requestHeader is
    // what ATSH takes; fullRequestId / responseId are the full 29-bit IDs used
    // for ATCRA / ATFCSH / STCFCPA.
    data class EcuTarget(
        val name: String,
        val requestHeader: String,  // ATSH argument, 6 hex chars
        val responseId: String,     // ATCRA argument, 8 hex chars
        val fullRequestId: String   // ATFCSH / STCFCPA first half, 8 hex chars
    )

    val ECU_BMS = EcuTarget(
        name = "BMS",
        requestHeader = "FC007B",
        responseId = "17FE007B",
        fullRequestId = "17FC007B"
    )

    /** Energy-management / vehicle-electrical ECU. Hosts MEC, EC, 12V battery. */
    val ECU_EM = EcuTarget(
        name = "EM",
        requestHeader = "FC0010",
        responseId = "17FE0010",
        fullRequestId = "17FC0010"
    )

    /** Charging-management ECU (0x76). Suspected host for the vehicle's own
     *  remaining-charge-time estimate and charge-station handshake data —
     *  used by the post-init probe so we can field-test which DIDs respond. */
    val ECU_CHARGING = EcuTarget(
        name = "CHG",
        requestHeader = "FC0076",
        responseId = "17FE0076",
        fullRequestId = "17FC0076"
    )

    /** Battery-regulation ECU (0x7C). Mentioned in the handoff alongside
     *  the BMS (0x7B); plausible host for MEC/EC if the EM module 0x10
     *  proves silent on this car (which it did, 2026-06-21 field log). */
    val ECU_BATTERY_REG = EcuTarget(
        name = "BREG",
        requestHeader = "FC007C",
        responseId = "17FE007C",
        fullRequestId = "17FC007C"
    )

    /** DC-DC converter ECU (0xB9). Hosts at least 465B (current /16) and
     *  465D (voltage /512) per the handoff. Also probed for MEC fallback. */
    val ECU_DCDC = EcuTarget(
        name = "DCDC",
        requestHeader = "FC00B9",
        responseId = "17FE00B9",
        fullRequestId = "17FC00B9"
    )

    // Kept for the old `sendCommand(cmd, header: String?)` callers and to
    // preserve the ATSP7 / ATCP17 init that depends on the BMS being default.
    const val HEADER_BMS = "FC007B"
    const val HEADER_OBD = "DB33F1"   // 11-bit functional OBD-II broadcast

    // ── ELM327 / OBDLink init sequence ──────────────────────────────────────
    val INIT_SEQUENCE = listOf(
        "ATZ",
        "ATE0",
        "ATL0",
        "ATS0",
        "ATH1",
        "ATAT1",
        "ATST96",
        "ATAR",
        "STCAF0",
        "ATSP7",
        "ATCP17",
        "ATSHFC007B",
        "ATFCSH17FC007B",
        "ATFCSD300000",
        "ATFCSM1",
        "STCFCPA17FC007B,17FE007B",
        "ATCRA17FE007B"
    )

    /** Commands to issue when switching the live ECU target (BMS ↔ EM ↔ …). */
    fun ecuSwitchCommands(target: EcuTarget): List<String> = listOf(
        "ATSH${target.requestHeader}",
        "ATCRA${target.responseId}",
        "ATFCSH${target.fullRequestId}",
        "STCFCPA${target.fullRequestId},${target.responseId}"
    )

    /** UDS DiagnosticSessionControl, sub-function 03 = ExtendedDiagnosticSession. */
    const val UDS_EXTENDED_SESSION = "1003"

    // ── PIDs on the BMS (17FC007B) ─────────────────────────────────────────

    /** Pack temperature MAX + sensor index. 4 data bytes: `[TH, TL, idx, ?]`. */
    const val PID_PACK_TEMP_MAX = "221E0E"

    /** Pack temperature MIN + sensor index. Same layout as MAX. */
    const val PID_PACK_TEMP_MIN = "221E0F"

    /** SOC BMS (internal/real). 1 data byte. `raw / 2.5` = %.
     *  The HMI/displayed SOC is derived from this — see [bmsSocToDisplay]. */
    const val PID_SOC_BMS = "22028C"

    /** HV pack voltage. 2 data bytes BE. `(B0*256 + B1) / 4` = V. */
    const val PID_PACK_VOLTAGE = "221E3B"

    /** HV pack current. 4 data bytes, first 2 BE signed. `(B0*256 + B1) / 5` = A.
     *  Sign convention from user handoff: + = charge / regen. */
    const val PID_PACK_CURRENT = "221E3C"

    /** Vehicle operation mode (0x7448, single byte).
     *  0=standby, 1=driving, 4=AC charge, 6=DC charge (others = unknown). */
    const val PID_VEHICLE_MODE = "227448"

    /** HV cooling pump duty cycle (0x743B, single byte = %). */
    const val PID_COOLANT_PUMP = "22743B"

    /** HV cooling fluid inlet/outlet temperatures (0x189D, 4 data bytes).
     *  Layout from user handoff: `WW XX YY ZZ` → outlet `(WW*256+XX)/64`,
     *  inlet `(YY*256+ZZ)/64` °C. */
    const val PID_COOLANT_TEMPS = "22189D"

    /** Cell N voltage. DID = 1E40 + (N-1). 2 data bytes BE,
     *  `(XX*256+YY)/1000 + 1` = V. */
    const val PID_CELL_VOLT_BASE = "221E40"

    /** Cell index of pack max voltage (1E33). Data: `[?, ?, ZZ, ?]`, idx in ZZ. */
    const val PID_CELL_VOLT_MAX_IDX = "221E33"

    /** Cell index of pack min voltage (1E34). Data: `[?, ?, ZZ, ?]`, idx in ZZ. */
    const val PID_CELL_VOLT_MIN_IDX = "221E34"

    /** Lifetime energy throughput. 16 data bytes: bytes 0..7 = total charge,
     *  8..15 = total discharge. Each 4-byte BE chunk / 8583.07 = kWh. */
    const val PID_LIFETIME_ENERGY = "221E32"

    // ── PIDs on the EM module (17FC0010) ───────────────────────────────────

    /** Maximum Energy Content — pack's estimated full-charge capacity.
     *  Formula assumed (CSV had "equation missing"): 4-byte BE unsigned Wh,
     *  `raw / 1000` = kWh. Plausibility-checked in [parseEnergyKwh]. */
    const val PID_MEC = "222AB2"

    /** Energy Content — current pack energy. Same encoding as MEC. */
    const val PID_EC = "222AB8"

    /** 12V battery voltage via EM module (more reliable than the OBD-II
     *  Mode 01 broadcast). 2 data bytes BE. `(B0*256+B1)/1024 + 4.26` = V. */
    const val PID_12V_VIA_EM = "222AF7"

    /** Legacy OBD-II Mode 01 PID 0x42. The MEB platform doesn't broadcast it
     *  reliably; kept only so existing tests still link. */
    const val PID_12V_VOLTAGE = "0142"

    // ── Hex extraction helpers ──────────────────────────────────────────────

    private const val HEADER_PAT = "(?:17F[A-F]00[0-9A-F]{2}|18DAF1[0-9A-F]{2}|[67][0-9A-F][0-9A-F]?)"
    private const val MEB_HEADER_PAT = "(?:17F[A-F]00[0-9A-F]{2}|18DAF1[0-9A-F]{2})"
    private const val UDS_FRAME = "$HEADER_PAT(?:0[0-9A-F])?62[0-9A-F]{4}([0-9A-F]+)"
    private const val OBD_FRAME = "$HEADER_PAT(?:0[0-9A-F])?41[0-9A-F]{2}([0-9A-F]+)"

    private fun extractUdsDataHex(response: String): String? {
        val clean = response.replace("\\s".toRegex(), "").uppercase()
        reassembleMebPositive(clean)?.let { return it }
        val match = Regex(UDS_FRAME).find(clean) ?: return null
        return match.groupValues[1]
    }

    private fun extractObdDataHex(response: String): String? {
        val clean = response.replace("\\s".toRegex(), "").uppercase()
        val match = Regex(OBD_FRAME).find(clean) ?: return null
        return match.groupValues[1]
    }

    private fun reassembleMebPositive(clean: String): String? {
        val headers = Regex(MEB_HEADER_PAT).findAll(clean).toList()
        if (headers.isEmpty()) return null

        var assembling = false
        var expectedHex = 0
        val buf = StringBuilder()

        for (m in headers) {
            val hdrEnd = m.range.last + 1
            if (hdrEnd + 2 > clean.length) continue
            val pciHi = clean[hdrEnd]
            val pciLo = clean[hdrEnd + 1]

            when (pciHi) {
                '0' -> {
                    val dataLen = pciLo.digitToInt(16)
                    val dataStart = hdrEnd + 2
                    val dataEnd = minOf(dataStart + dataLen * 2, clean.length)
                    val payload = clean.substring(dataStart, dataEnd)
                    if (payload.length >= 6 && payload.startsWith("62")) {
                        return payload.substring(6)
                    }
                    assembling = false
                    buf.setLength(0)
                }
                '1' -> {
                    if (hdrEnd + 4 > clean.length) continue
                    val lenHi = pciLo.digitToInt(16)
                    val lenLo = clean.substring(hdrEnd + 2, hdrEnd + 4).toInt(16)
                    expectedHex = ((lenHi shl 8) or lenLo) * 2
                    val dataStart = hdrEnd + 4
                    val firstEnd = minOf(dataStart + 12, clean.length)
                    buf.setLength(0)
                    buf.append(clean.substring(dataStart, firstEnd))
                    assembling = true
                }
                '2' -> {
                    if (!assembling) continue
                    val dataStart = hdrEnd + 2
                    val avail = clean.length - dataStart
                    if (avail <= 0) continue
                    val take = minOf(expectedHex - buf.length, minOf(14, avail))
                    if (take <= 0) continue
                    buf.append(clean.substring(dataStart, dataStart + take))
                    if (buf.length >= expectedHex) {
                        val payload = buf.substring(0, expectedHex)
                        if (payload.length >= 6 && payload.startsWith("62")) {
                            return payload.substring(6)
                        }
                        assembling = false
                        buf.setLength(0)
                    }
                }
            }
        }
        return null
    }

    // ── Parsers ─────────────────────────────────────────────────────────────

    /** Pack temperature from 1E0E/1E0F: `(B0*256 + B1) / 64` °C. */
    fun parsePackTemp(response: String): Float? {
        val hex = extractUdsDataHex(response) ?: return null
        if (hex.length < 4) return null
        val raw = hex.substring(0, 4).toIntOrNull(16) ?: return null
        val celsius = raw / 64.0f
        return if (celsius in -50f..150f) celsius else null
    }

    /** SOC BMS (internal/real) from 22028C: `raw / 2.5` %. */
    fun parseSocBms(response: String): Float? {
        val hex = extractUdsDataHex(response) ?: return null
        if (hex.length < 2) return null
        val raw = hex.substring(0, 2).toIntOrNull(16) ?: return null
        return raw / 2.5f
    }

    /** Legacy alias — same wire as parseSocBms. Kept so old tests still link. */
    fun parseSoc(response: String): Float? = parseSocBms(response)

    /** Convert BMS SOC (0..100, with hidden buffers) to the HMI/displayed SOC.
     *  Formula from spot2000 CSV: `SOC_HMI = SOC_BMS * 51/46 - 6.4`, clamped. */
    fun bmsSocToDisplay(bmsSoc: Float?): Float? {
        if (bmsSoc == null) return null
        val raw = bmsSoc * 51f / 46f - 6.4f
        return raw.coerceIn(0f, 100f)
    }

    /** HV pack voltage from 1E3B: `(B0*256 + B1) / 4` V. */
    fun parsePackVoltage(response: String): Float? {
        val hex = extractUdsDataHex(response) ?: return null
        if (hex.length < 4) return null
        val raw = hex.substring(0, 4).toIntOrNull(16) ?: return null
        return raw / 4.0f
    }

    /** HV pack current from 1E3C: first 2 bytes BE, two's-complement signed,
     *  scale `/5` = A. Sign convention from handoff: + = charge / regen. */
    fun parsePackCurrent(response: String): Float? {
        val hex = extractUdsDataHex(response) ?: return null
        if (hex.length < 4) return null
        var raw = hex.substring(0, 4).toIntOrNull(16) ?: return null
        if (raw >= 0x8000) raw -= 0x10000
        return raw / 5.0f
    }

    /** OBD-II Mode 01 PID 0x42 — kept for legacy tests, not used at runtime. */
    fun parse12vVoltage(response: String): Float? {
        val hex = extractObdDataHex(response) ?: return null
        if (hex.length < 4) return null
        val hi = hex.substring(0, 2).toIntOrNull(16) ?: return null
        val lo = hex.substring(2, 4).toIntOrNull(16) ?: return null
        return (hi * 256 + lo) / 1000f
    }

    /** 12V from EM module DID 2AF7: `(B0*256+B1)/1024 + 4.26` V. */
    fun parse12vVoltageEm(response: String): Float? {
        val hex = extractUdsDataHex(response) ?: return null
        if (hex.length < 4) return null
        val raw = hex.substring(0, 4).toIntOrNull(16) ?: return null
        val v = raw / 1024f + 4.26f
        return if (v in 8f..18f) v else null
    }

    /** Vehicle operation mode from 7448 (single byte). */
    fun parseVehicleMode(response: String): VehicleMode {
        val hex = extractUdsDataHex(response) ?: return VehicleMode.UNKNOWN
        if (hex.length < 2) return VehicleMode.UNKNOWN
        val raw = hex.substring(0, 2).toIntOrNull(16) ?: return VehicleMode.UNKNOWN
        return when (raw) {
            0    -> VehicleMode.STANDBY
            1    -> VehicleMode.DRIVING
            4    -> VehicleMode.CHARGING_AC
            6    -> VehicleMode.CHARGING_DC
            else -> VehicleMode.UNKNOWN
        }
    }

    /** HV cooling pump duty from 743B (single byte = %). */
    fun parseCoolantPump(response: String): Float? {
        val hex = extractUdsDataHex(response) ?: return null
        if (hex.length < 2) return null
        val raw = hex.substring(0, 2).toIntOrNull(16) ?: return null
        return if (raw in 0..100) raw.toFloat() else null
    }

    /** HV cooling fluid temperatures. Returns (inlet, outlet) °C. */
    fun parseCoolantTemps(response: String): Pair<Float?, Float?> {
        val hex = extractUdsDataHex(response) ?: return null to null
        if (hex.length < 8) return null to null
        val ww = hex.substring(0, 2).toIntOrNull(16) ?: return null to null
        val xx = hex.substring(2, 4).toIntOrNull(16) ?: return null to null
        val yy = hex.substring(4, 6).toIntOrNull(16) ?: return null to null
        val zz = hex.substring(6, 8).toIntOrNull(16) ?: return null to null
        val outlet = (ww * 256 + xx) / 64f
        val inlet  = (yy * 256 + zz) / 64f
        val sane: (Float) -> Float? = { t -> if (t in -30f..120f) t else null }
        return sane(inlet) to sane(outlet)
    }

    /** Cell index byte from 1E33 / 1E34 — the index lives in the 3rd byte (ZZ). */
    fun parseCellVoltIndex(response: String): Int? {
        val hex = extractUdsDataHex(response) ?: return null
        if (hex.length < 6) return null
        return hex.substring(4, 6).toIntOrNull(16)
    }

    /** Individual cell voltage from 1E40+ : `(XX*256+YY)/1000 + 1` V → mV. */
    fun parseCellVoltMv(response: String): Int? {
        val hex = extractUdsDataHex(response) ?: return null
        if (hex.length < 4) return null
        val raw = hex.substring(0, 4).toIntOrNull(16) ?: return null
        val volts = raw / 1000f + 1f
        if (volts !in 2f..5f) return null
        return (volts * 1000f).toInt()
    }

    /** Build the cell-N read PID. Cell numbering is 1-based; DID = 0x1E40 + (N-1). */
    fun cellVoltPid(cellOneBased: Int): String {
        val did = 0x1E40 + (cellOneBased - 1)
        return "22%04X".format(did)
    }

    /** Decode 4-byte big-endian Wh → kWh, with a plausibility check.
     *  Returns null if the raw value is outside 10..120 kWh (likely garbage). */
    fun parseEnergyKwh(response: String): Float? {
        val hex = extractUdsDataHex(response) ?: return null
        if (hex.length < 8) {
            // Some MEB ECUs reply with 2 bytes here, others 4. Try 2-byte as
            // a fallback: scale `raw / 100` empirically (one community source).
            if (hex.length >= 4) {
                val raw16 = hex.substring(0, 4).toIntOrNull(16) ?: return null
                val kwh = raw16 / 100f
                return if (kwh in 10f..120f) kwh else null
            }
            return null
        }
        val raw = hex.substring(0, 8).toLongOrNull(16) ?: return null
        val kwh = raw / 1000f
        return if (kwh in 10f..120f) kwh else null
    }

    /** Lifetime energy from 1E32 → (totalChargeKwh, totalDischargeKwh).
     *  Per the spot2000 CSV (DID 1E32, B0 = first byte after the 62-1E-32 echo):
     *    charge    = unsigned(B8..B11)  / 8583.07  kWh
     *    discharge =   signed(B12..B15) / 8583.07  kWh   (negative = out of pack)
     *  The discharge accumulator is a SIGNED two's-complement 32-bit int; parsing
     *  it unsigned turned a real −6,924 kWh into a bogus +493,476 kWh on the UI. */
    fun parseLifetimeEnergy(response: String): Pair<Float?, Float?> {
        val hex = extractUdsDataHex(response) ?: return null to null
        if (hex.length < 32) return null to null
        val chargeRaw = hex.substring(16, 24).toLongOrNull(16) ?: return null to null
        var dischRaw  = hex.substring(24, 32).toLongOrNull(16) ?: return null to null
        if (dischRaw >= 0x8000_0000L) dischRaw -= 0x1_0000_0000L   // sign-extend
        val charge = chargeRaw / 8583.07f
        val discharge = kotlin.math.abs(dischRaw / 8583.07f)       // magnitude for display
        // Safety net: a passenger EV won't exceed a few hundred thousand kWh in
        // its life. Reject off-scale values (e.g. a corrupt multiframe reassembly)
        // so the UI degrades to "--" instead of showing garbage.
        val saneCharge = charge.takeIf { it in 0f..500_000f }
        val saneDisch  = discharge.takeIf { it in 0f..500_000f }
        return saneCharge to saneDisch
    }

    /** Vehicle operation modes reported by DID 7448. */
    enum class VehicleMode(val label: String) {
        UNKNOWN("--"),
        STANDBY("Veille"),
        DRIVING("Roulage"),
        CHARGING_AC("Charge AC"),
        CHARGING_DC("Charge DC (rapide)")
    }
}
