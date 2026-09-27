package com.borntemp.app.domain

/** What an identification candidate is trying to find. */
enum class CandidateTarget(val label: String) {
    HV_CURRENT("Courant HV"),
    BMS_LIMIT("Limite charge / courant BMS"),
    ENERGY_CONTENT("MEC / EC"),
    V12("Tension 12 V"),
}

enum class Cadence {
    /** Every poll tick: needed to correlate against load (current). */
    FAST,
    /** Every slow tick (~1 min at 5 s polling). */
    SLOW,
    /** At connection and on each charge-state change: the scans, so the trace
     *  holds one sweep per state (driving, charging, standby) to diff. */
    ON_STATE_CHANGE,
}

/**
 * One request (or a range of them) to try against the car, with the reason
 * it's worth trying. Results land in the UDS trace; nothing here is parsed
 * into a displayed value except the 12 V sources, which are known to answer.
 *
 * @property ecu name of an ObdPids.EcuTarget, or a raw 29-bit address pair
 *   `"17FC00XX/17FE00XX"` for addresses the app doesn't know yet.
 */
data class SignalCandidate(
    val id: String,
    val target: CandidateTarget,
    val label: String,
    val ecu: String,
    val commands: List<String>,
    val cadence: Cadence,
    val enabledByDefault: Boolean,
    val hypothesis: String,
)

object SignalCandidates {

    private fun didRange(service: String, from: Int, to: Int): List<String> =
        (from..to).map { "%s%04X".format(service, it) }

    val ALL: List<SignalCandidate> = listOf(
        // ── HV current ─────────────────────────────────────────────────────
        SignalCandidate(
            id = "i_hv_1e3d", target = CandidateTarget.HV_CURRENT,
            label = "BMS 1E3D", ecu = "BMS", commands = listOf("221E3D"),
            cadence = Cadence.FAST, enabledByDefault = true,
            hypothesis = "Voisin de 1E3B (tension) et 1E3C (non résolu) ; cité comme courant pack MEB. " +
                "Lu à chaque tick pour corréler avec la puissance chargeur.",
        ),
        SignalCandidate(
            id = "scan_bms_1e00", target = CandidateTarget.HV_CURRENT,
            label = "Balayage BMS 1E00–1E4F", ecu = "BMS", commands = didRange("22", 0x1E00, 0x1E4F),
            cadence = Cadence.ON_STATE_CHANGE, enabledByDefault = false,
            hypothesis = "Tous les DIDs pack connus (températures, tension, cellules, énergie) sont dans ce bloc ; " +
                "courant et limites y sont probables. ~80 requêtes, ~8 s par balayage.",
        ),
        // ── BMS limits ─────────────────────────────────────────────────────
        SignalCandidate(
            id = "scan_bms_0280", target = CandidateTarget.BMS_LIMIT,
            label = "Balayage BMS 0280–029F", ecu = "BMS", commands = didRange("22", 0x0280, 0x029F),
            cadence = Cadence.ON_STATE_CHANGE, enabledByDefault = false,
            hypothesis = "Bloc du SOC BMS (028C) : limites de charge/décharge et SOC min/max probables à côté.",
        ),
        SignalCandidate(
            id = "scan_bms_7440", target = CandidateTarget.BMS_LIMIT,
            label = "Balayage BMS 7440–744F", ecu = "BMS", commands = didRange("22", 0x7440, 0x744F),
            cadence = Cadence.ON_STATE_CHANGE, enabledByDefault = false,
            hypothesis = "Bloc du mode véhicule (7448) et de la pompe (743B) : états de charge et consignes.",
        ),
        // ── MEC / EC ───────────────────────────────────────────────────────
        SignalCandidate(
            id = "mec_ext_session_em", target = CandidateTarget.ENERGY_CONTENT,
            label = "EM 2AB2 en session étendue", ecu = "EM", commands = listOf("1003", "222AB2", "222AB8"),
            cadence = Cadence.ON_STATE_CHANGE, enabledByDefault = false,
            hypothesis = "EM répond NO DATA (aucune réponse) : l'adresse 0x10 est probablement fausse, mais on " +
                "écarte le cas « DID réservé à la session étendue » pour trancher.",
        ),
        SignalCandidate(
            id = "mec_ecu_sweep", target = CandidateTarget.ENERGY_CONTENT,
            label = "2AB2 sur d'autres adresses", ecu = "SWEEP",
            commands = listOf("19", "44", "46", "5F", "8C", "C0").map { "17FC00$it/17FE00$it:222AB2" },
            cadence = Cadence.ON_STATE_CHANGE, enabledByDefault = false,
            hypothesis = "Adresses de diagnostic MEB non vérifiées sur cette voiture (passerelle, gestion " +
                "énergie, BECM). Un NRC 31 prouve au moins que l'ECU existe.",
        ),
        // ── 12 V ───────────────────────────────────────────────────────────
        SignalCandidate(
            id = "v12_dcdc_465d", target = CandidateTarget.V12,
            label = "DCDC 465D (/512)", ecu = "DCDC", commands = listOf("22465D"),
            cadence = Cadence.SLOW, enabledByDefault = true,
            hypothesis = "Tension de sortie du convertisseur = réseau 12 V. Répond : 0x1CDA → 14,4 V (sonde du 26/09).",
        ),
        SignalCandidate(
            id = "v12_atrv", target = CandidateTarget.V12,
            label = "ATRV (broche OBD 16)", ecu = "ADAPTER", commands = listOf("ATRV"),
            cadence = Cadence.SLOW, enabledByDefault = true,
            hypothesis = "Mesurée par l'adaptateur lui-même, sans passer par le CAN. Répond : 14,0 V (sonde du 26/09).",
        ),
    )

    fun byId(id: String): SignalCandidate? = ALL.firstOrNull { it.id == id }

    fun defaultEnabled(): Set<String> = ALL.filter { it.enabledByDefault }.map { it.id }.toSet()

    /** Candidates to run this tick, in catalogue order. */
    fun due(enabled: Set<String>, slowTick: Boolean, stateChanged: Boolean): List<SignalCandidate> =
        ALL.filter { it.id in enabled }.filter {
            when (it.cadence) {
                Cadence.FAST -> true
                Cadence.SLOW -> slowTick
                Cadence.ON_STATE_CHANGE -> stateChanged
            }
        }
}
