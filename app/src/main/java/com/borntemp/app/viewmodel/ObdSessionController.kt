package com.borntemp.app.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.borntemp.app.abrp.AbrpSettings
import com.borntemp.app.abrp.AbrpTelemetryClient
import com.borntemp.app.abrp.LocationProvider
import com.borntemp.app.obd.BluetoothObdManager
import com.borntemp.app.obd.MonitoredDeviceStore
import com.borntemp.app.obd.ObdBeaconReceiver
import com.borntemp.app.obd.ObdPids
import com.borntemp.app.obd.SessionCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ObdSessionController(private val application: Application) {

    private val _uiState = MutableStateFlow(UiState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val obdManager = BluetoothObdManager(application)
    private val capture = SessionCapture(application)
    private val analytics = ChargeAnalytics()
    private var pollingJob: Job? = null
    private var pollCounter = 0L
    private var lastSohSampleMs = 0L
    /** Last unmapped 7448 frame already traced, so the diagnostic fires once
     *  per distinct value instead of on every poll. */
    private var lastUnknownModeRaw: String? = null

    private val abrpSettings = AbrpSettings(application)
    private val abrpClient = AbrpTelemetryClient()
    private val locationProvider = LocationProvider(application)
    private val batterySettings = BatterySettings(application)
    private val monitoredDeviceStore = MonitoredDeviceStore(application)

    init {
        _uiState.update {
            it.copy(
                abrp = AbrpUiState(
                    enabled = abrpSettings.enabled,
                    apiKey = abrpSettings.apiKey,
                    userToken = abrpSettings.userToken
                ),
                packTypeOverride = batterySettings.packTypeOverride
            )
        }
        if (abrpSettings.enabled) locationProvider.start()
    }

    fun setPackTypeOverride(override: PackTypeOverride) {
        batterySettings.packTypeOverride = override
        _uiState.update { it.copy(packTypeOverride = override) }
    }

    companion object {
        // Slow PIDs (SOH/MEC/EC, 12V, coolant temps) only fetched every N fast ticks.
        private const val SLOW_POLL_EVERY = 12
        private const val MIN_POLL_MS = 2000L
        private const val MAX_POLL_MS = 60000L

        // Stale-data guard for HV current/voltage (handoff §7 bug #1):
        // if the PID hasn't answered for more than this multiplier × the
        // configured polling interval, the UI drops back to "--".
        private const val STALE_TIMEOUT_MULT = 3

        // Append one CSV row at most every 30 s, even if the slow tick fires
        // more often — keeps the file lean across long sessions.
        private const val SOH_CSV_MIN_INTERVAL_MS = 30_000L

        private const val DEBUG_RAW_RESPONSES = true
    }

    // Wraps sendCommand so every PID query's raw response is recorded:
    //   - to the session capture file (always — so the .log is reviewable)
    //   - to the in-app log panel (when DEBUG_RAW_RESPONSES is true)
    private suspend fun query(pid: String, header: String? = null): String? {
        val resp = obdManager.sendCommand(pid, header)
        recordQuery(pid, resp)
        return resp
    }

    private suspend fun queryOn(pid: String, ecu: ObdPids.EcuTarget): String? {
        val resp = obdManager.sendCommand(pid, ecu)
        recordQuery("${ecu.name}:$pid", resp)
        return resp
    }

    /**
     * Read one extreme-cell DID (1E33 max / 1E34 min). One round-trip now
     * yields both the voltage and the cell index — the frame always carried
     * both, so the follow-up read of `1E40 + (idx-1)` is gone.
     *
     * Still traces its failures: the card showing "--" used to say nothing
     * about which link broke.
     */
    private suspend fun readCellExtreme(pid: String, label: String): ObdPids.CellExtreme? {
        val raw = queryOn(pid, ObdPids.ECU_BMS)
        if (raw == null) {
            capture.event("CELL $label ($pid) — aucune réponse")
            return null
        }
        val cell = ObdPids.parseCellExtreme(raw)
        if (cell == null) {
            capture.event("CELL $label ($pid) — trame non décodée: ${raw.trim()}")
            return null
        }
        return cell
    }

    /**
     * Try several ECUs for an energy DID and return the first that parses to
     * a plausible kWh value. Used as a runtime fallback for MEC/EC since the
     * documented EM module 0x10 is silent on this Born — it lets the app
     * survive a CSV mis-attribution without code changes the day we find
     * the right host.
     */
    private suspend fun queryAnyEnergy(
        pid: String,
        vararg ecus: ObdPids.EcuTarget
    ): Float? {
        for (e in ecus) {
            val resp = queryOn(pid, e) ?: continue
            val kwh = ObdPids.parseEnergyKwh(resp) ?: continue
            return kwh
        }
        return null
    }

    private fun recordQuery(label: String, resp: String?) {
        capture.pid(label, resp)
        if (DEBUG_RAW_RESPONSES) {
            val shown = resp?.replace("\r", " ")?.replace(">", "")?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "TIMEOUT"
            val entry = LogEntry(message = "$label ← $shown", level = LogLevel.INFO)
            _uiState.update { it.copy(logEntries = (it.logEntries + entry).takeLast(50)) }
        }
    }

    // ── Polling interval setting ────────────────────────────────────────────

    fun setPollingInterval(ms: Long) {
        val clamped = ms.coerceIn(MIN_POLL_MS, MAX_POLL_MS)
        _uiState.update { it.copy(pollingIntervalMs = clamped) }
    }

    // ── ABRP settings ───────────────────────────────────────────────────────

    fun setAbrpApiKey(key: String) {
        abrpSettings.apiKey = key
        _uiState.update { it.copy(abrp = it.abrp.copy(apiKey = key)) }
    }

    fun setAbrpUserToken(token: String) {
        abrpSettings.userToken = token
        _uiState.update { it.copy(abrp = it.abrp.copy(userToken = token)) }
    }

    fun setAbrpEnabled(enabled: Boolean) {
        abrpSettings.enabled = enabled
        _uiState.update { it.copy(abrp = it.abrp.copy(enabled = enabled)) }
        if (enabled) locationProvider.start() else locationProvider.stop()
    }

    // ── Logging ─────────────────────────────────────────────────────────────

    private fun log(message: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(message = message, level = level)
        _uiState.update { state ->
            val entries = (state.logEntries + entry).takeLast(50)
            state.copy(logEntries = entries)
        }
        capture.event("[${level.name}] $message")
    }

    // ── Device listing ───────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun getPairedDevices(context: Context): List<BluetoothDevice> {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return emptyList()
        return obdManager.getPairedObdDevices(adapter)
    }

    @SuppressLint("MissingPermission")
    fun getBluetoothAdapter(context: Context): BluetoothAdapter? {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bluetoothManager?.adapter
    }

    // ── Connection ───────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        // Ignore re-taps while a connect is already in flight. A second connect()
        // calls obdManager.connect() → disconnect(), tearing down the half-built
        // session mid-handshake — the cause of the "UDS 1003 ← NO DATA" failures.
        val current = _uiState.value.connectionState
        if (current == ConnectionState.CONNECTING || current == ConnectionState.INITIALIZING) {
            return
        }
        scope.launch {
            _uiState.update { it.copy(
                connectionState = ConnectionState.CONNECTING,
                selectedDevice = device.name,
                errorMessage = null
            )}
            log("Connexion à ${device.name}...", LogLevel.INFO)

            val result = obdManager.connect(device)
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: "Erreur inconnue"
                log("Échec connexion : $msg", LogLevel.ERROR)
                _uiState.update { it.copy(
                    connectionState = ConnectionState.ERROR,
                    errorMessage = "Impossible de se connecter : $msg"
                )}
                return@launch
            }
            monitoredDeviceStore.deviceAddress = device.address
            ObdBeaconReceiver.armDetection(application)

            // Open the capture file now so init responses are recorded.
            val captureFile = capture.start(device.name)
            if (captureFile != null) {
                _uiState.update { it.copy(
                    captureFileUri = capture.shareUri(),
                    captureFileName = captureFile.name,
                    sohHistoryFileUri = capture.shareSohUri(),
                    sohHistoryFileName = capture.currentSohFile()?.name
                )}
                log("Capture → ${captureFile.name}", LogLevel.OK)
            } else {
                log("Capture indisponible (stockage externe inaccessible)", LogLevel.WARN)
            }

            log("Bluetooth connecté. Initialisation ELM327...", LogLevel.OK)
            _uiState.update { it.copy(connectionState = ConnectionState.INITIALIZING) }

            // Log each init command as it completes (not batched after the whole
            // sequence returns), so the UI shows live progress instead of a
            // ~7s silent "INIT..." that tempts the user to re-tap CONNECTER.
            obdManager.initializeElm { cmd, resp ->
                capture.init(cmd, resp)
                val trimmed = resp?.replace(">", "")?.trim() ?: "TIMEOUT"
                val entry = LogEntry(
                    message = "$cmd → $trimmed",
                    level = if (resp != null) LogLevel.INFO else LogLevel.WARN
                )
                _uiState.update { it.copy(logEntries = (it.logEntries + entry).takeLast(50)) }
            }

            log("Init MEB OK. CAN 29-bit / 500, header 17FC007B (BMS)", LogLevel.OK)

            // UDS DiagnosticSessionControl 0x03 (Extended) — required on MEB
            // before any $22 ReadDataByIdentifier query is honored by the BMS.
            val sess = obdManager.sendCommand(ObdPids.UDS_EXTENDED_SESSION)
            val sessShown = sess?.replace("\r", " ")?.replace(">", "")?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "TIMEOUT"
            capture.event("UDS Session 1003 ← $sessShown")
            val sessEntry = LogEntry(
                message = "Session 1003 ← $sessShown",
                level = if (sess != null) LogLevel.INFO else LogLevel.WARN
            )
            _uiState.update { it.copy(logEntries = (it.logEntries + sessEntry).takeLast(50)) }

            // Show the cockpit as soon as the session is up; the one-shot probe
            // (~20 PIDs) then runs before polling starts, shaving several more
            // seconds off perceived connect time.
            _uiState.update { it.copy(connectionState = ConnectionState.CONNECTED) }

            runDiagnosticProbe()

            startPolling()
        }
    }

    /**
     * Diagnostic probe v6 (MEB profile, MEC + EM module + vehicle mode) —
     * runs once after init. Confirms both ECU targets answer; if the EM
     * (0x10) is silent, the SOH section will stay greyed out in the UI.
     */
    private suspend fun runDiagnosticProbe() {
        log("--- Sonde v6 (MEB + EM) ---", LogLevel.INFO)
        capture.event("--- Probe v6 (MEB + EM) start ---")

        suspend fun step(label: String, command: String, ecu: ObdPids.EcuTarget? = null) {
            val resp = if (ecu != null) obdManager.sendCommand(command, ecu)
                      else obdManager.sendCommand(command)
            val shown = resp?.replace("\r", " ")?.replace(">", "")?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "TIMEOUT"
            capture.event("PROBE  $label  ← $shown")
            val entry = LogEntry(
                message = "$label ← $shown",
                level = if (resp != null) LogLevel.INFO else LogLevel.WARN
            )
            _uiState.update { it.copy(logEntries = (it.logEntries + entry).takeLast(50)) }
        }

        step("ATRV (voltage)",       "ATRV")
        step("ATDP (protocol)",      "ATDP")
        // BMS block
        step("22028C (SOC BMS)",     ObdPids.PID_SOC_BMS,        ObdPids.ECU_BMS)
        step("221E3B (pack V)",      ObdPids.PID_PACK_VOLTAGE,   ObdPids.ECU_BMS)
        step("221E3C (pack I)",      ObdPids.PID_PACK_CURRENT,   ObdPids.ECU_BMS)
        step("221E0E (pack T max)",  ObdPids.PID_PACK_TEMP_MAX,  ObdPids.ECU_BMS)
        step("221E0F (pack T min)",  ObdPids.PID_PACK_TEMP_MIN,  ObdPids.ECU_BMS)
        step("227448 (vehicle mode)",ObdPids.PID_VEHICLE_MODE,   ObdPids.ECU_BMS)
        step("22743B (pump %)",      ObdPids.PID_COOLANT_PUMP,   ObdPids.ECU_BMS)
        step("22189D (coolant T)",   ObdPids.PID_COOLANT_TEMPS,  ObdPids.ECU_BMS)
        // EM block (different module — exercises the ATCRA/ATFCSH switch)
        step("222AB2 (MEC)",         ObdPids.PID_MEC,            ObdPids.ECU_EM)
        step("222AB8 (EC)",          ObdPids.PID_EC,             ObdPids.ECU_EM)
        step("222AF7 (12V EM)",      ObdPids.PID_12V_VIA_EM,     ObdPids.ECU_EM)
        // 2026-06-21 field finding: the EM module 0x10 is silent on this
        // Born. Try the same DIDs on the BMS (the very first handoff said
        // "même module 17FC007B") and on neighbouring battery / DC-DC ECUs.
        step("BMS 222AB2 (MEC?)",    ObdPids.PID_MEC,            ObdPids.ECU_BMS)
        step("BMS 222AB8 (EC?)",     ObdPids.PID_EC,             ObdPids.ECU_BMS)
        step("BREG 222AB2",          ObdPids.PID_MEC,            ObdPids.ECU_BATTERY_REG)
        step("BREG 222AB8",          ObdPids.PID_EC,             ObdPids.ECU_BATTERY_REG)
        step("DCDC 222AB2",          ObdPids.PID_MEC,            ObdPids.ECU_DCDC)
        step("DCDC 22465B (DC-DC I)","22465B",                   ObdPids.ECU_DCDC)
        step("DCDC 22465D (DC-DC V)","22465D",                   ObdPids.ECU_DCDC)
        // CHG module exists (replied NRC last time) — try other DIDs.
        step("CHG 22F40D",           "22F40D",                   ObdPids.ECU_CHARGING)
        step("CHG 22F441",           "22F441",                   ObdPids.ECU_CHARGING)
        step("CHG 221E32",           ObdPids.PID_LIFETIME_ENERGY, ObdPids.ECU_CHARGING)
        // Lifetime energy on BMS — confirmed working 2026-06-21.
        step("221E32 (lifetime E)",  ObdPids.PID_LIFETIME_ENERGY, ObdPids.ECU_BMS)
        // back to BMS for the polling loop
        step("ATCRA17FE007B (back to BMS)", "ATCRA17FE007B")
        step("ATSHFC007B",           "ATSHFC007B")

        capture.event("--- Probe v6 end ---")
        log("--- Sonde terminée ---", LogLevel.INFO)
    }

    fun disconnect() {
        stopPolling()
        obdManager.disconnect()
        analytics.reset()
        _uiState.update { it.copy(
            connectionState = ConnectionState.DISCONNECTED,
            batteryData = BatteryData(),
            selectedDevice = null,
            isPolling = false,
            chargeProjection = ChargeProjection(),
            thermalTrajectory = ThermalTrajectory(null, null, null, ThermalAdvice.NONE, null)
        )}
        log("Déconnecté.", LogLevel.INFO)
        capture.close()
    }

    // ── Polling ──────────────────────────────────────────────────────────────

    private fun startPolling() {
        pollingJob?.cancel()
        pollCounter = 0L
        pollingJob = scope.launch {
            _uiState.update { it.copy(isPolling = true) }
            while (isActive && obdManager.isConnected) {
                readAllData()
                delay(_uiState.value.pollingIntervalMs)
            }
            if (!obdManager.isConnected) {
                log("Connexion perdue.", LogLevel.ERROR)
                _uiState.update { it.copy(
                    connectionState = ConnectionState.ERROR,
                    isPolling = false,
                    errorMessage = "Connexion perdue avec l'OBDLink CX"
                )}
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun refreshNow() {
        if (obdManager.isConnected) {
            scope.launch { readAllData() }
        }
    }

    // ── Data reading ─────────────────────────────────────────────────────────

    private suspend fun readAllData() {
        val isSlowTick = pollCounter % SLOW_POLL_EVERY == 0L
        pollCounter++
        val previous = _uiState.value.batteryData
        val now = System.currentTimeMillis()
        val pollMs = _uiState.value.pollingIntervalMs

        // ── BMS fast block ────────────────────────────────────────────────
        val packTempMax = queryOn(ObdPids.PID_PACK_TEMP_MAX, ObdPids.ECU_BMS)
            ?.let { ObdPids.parsePackTemp(it) }
        val packTempMin = queryOn(ObdPids.PID_PACK_TEMP_MIN, ObdPids.ECU_BMS)
            ?.let { ObdPids.parsePackTemp(it) }
        val socBms = queryOn(ObdPids.PID_SOC_BMS, ObdPids.ECU_BMS)
            ?.let { ObdPids.parseSocBms(it) }
        val socDisplay = ObdPids.bmsSocToDisplay(socBms)
        val voltage = queryOn(ObdPids.PID_PACK_VOLTAGE, ObdPids.ECU_BMS)
            ?.let { ObdPids.parsePackVoltage(it) }
        // BMS sign convention confirmed in the field 2026-06-21 13:31 log:
        // car parked in DRIVING mode reported +4.6 A — that's the DC-DC
        // converter pulling 12V from the HV pack, i.e. discharge. So the
        // raw value's + = OUT of pack. We flip here so the rest of the
        // codebase (LivePowerChip, ABRP, ChargeAnalytics, CSV) uses the
        // "driver mental model": + into pack (charge / regen), − out.
        val currentNow = queryOn(ObdPids.PID_PACK_CURRENT, ObdPids.ECU_BMS)
            ?.let { ObdPids.parsePackCurrent(it)?.let { raw -> -raw } }

        // ── Vehicle mode + pump (fast — affects state + thermal) ────────
        val vehicleModeRaw = queryOn(ObdPids.PID_VEHICLE_MODE, ObdPids.ECU_BMS)
        val vehicleMode = vehicleModeRaw
            ?.let { ObdPids.parseVehicleMode(it) } ?: previous.vehicleMode
        // parseVehicleMode only maps 0/1/4/6 and never returns null, so a code
        // we don't know overwrites the previous state with UNKNOWN and hides an
        // active charge. Trace the frame rather than guess a mapping — once per
        // distinct raw value, so a persistent unknown doesn't flood the log.
        if (vehicleMode == ObdPids.VehicleMode.UNKNOWN && vehicleModeRaw != null &&
            vehicleModeRaw != lastUnknownModeRaw) {
            lastUnknownModeRaw = vehicleModeRaw
            capture.event("VEHICLE_MODE non mappé (7448) — trame: ${vehicleModeRaw.trim()}")
        }
        val pumpPct = queryOn(ObdPids.PID_COOLANT_PUMP, ObdPids.ECU_BMS)
            ?.let { ObdPids.parseCoolantPump(it) } ?: previous.coolantPumpPct

        // ── Slow block ────────────────────────────────────────────────────
        var mecKwh = previous.mecKwh
        var ecKwh = previous.ecKwh
        var volt12v = previous.volt12v
        var lifetimeChargeKwh = previous.lifetimeChargeKwh
        var lifetimeDischargeKwh = previous.lifetimeDischargeKwh
        var coolantIn = previous.coolantTempIn
        var coolantOut = previous.coolantTempOut
        var cellMinIdx = previous.cellVoltMinIdx
        var cellMaxIdx = previous.cellVoltMaxIdx
        var cellMinMv = previous.cellVoltMinMv
        var cellMaxMv = previous.cellVoltMaxMv

        if (isSlowTick) {
            coolantIn = null; coolantOut = null
            queryOn(ObdPids.PID_COOLANT_TEMPS, ObdPids.ECU_BMS)?.let {
                val (ci, co) = ObdPids.parseCoolantTemps(it)
                coolantIn = ci; coolantOut = co
            }
            // Two PIDs, not four: 1E33 / 1E34 each return the extreme cell's
            // voltage together with its index.
            readCellExtreme(ObdPids.PID_CELL_VOLT_MIN_IDX, "min")?.let {
                cellMinIdx = it.index; cellMinMv = it.millivolts
            }
            readCellExtreme(ObdPids.PID_CELL_VOLT_MAX_IDX, "max")?.let {
                cellMaxIdx = it.index; cellMaxMv = it.millivolts
            }

            // MEC / EC / 12V — try the EM module first (CSV-documented host),
            // fall back to the BMS itself if EM is silent. The 2026-06-21
            // field session showed EM 0x10 mute on this car; BMS may still
            // know if the original handoff was right after all.
            mecKwh = queryAnyEnergy(ObdPids.PID_MEC, ObdPids.ECU_EM, ObdPids.ECU_BMS,
                ObdPids.ECU_BATTERY_REG) ?: mecKwh
            ecKwh = queryAnyEnergy(ObdPids.PID_EC, ObdPids.ECU_EM, ObdPids.ECU_BMS,
                ObdPids.ECU_BATTERY_REG) ?: ecKwh
            // Lifetime cumulative energy — confirmed working on the BMS.
            queryOn(ObdPids.PID_LIFETIME_ENERGY, ObdPids.ECU_BMS)?.let {
                val (charged, discharged) = ObdPids.parseLifetimeEnergy(it)
                if (charged != null) lifetimeChargeKwh = charged
                if (discharged != null) lifetimeDischargeKwh = discharged
            }
            queryOn(ObdPids.PID_12V_VIA_EM, ObdPids.ECU_EM)
                ?.let { ObdPids.parse12vVoltageEm(it) }?.let { volt12v = it }
        }

        // ── Derived ──────────────────────────────────────────────────────────
        val powerKw = if (voltage != null && currentNow != null)
            voltage * currentNow / 1000f else null
        val finalAvg = when {
            packTempMax != null && packTempMin != null -> (packTempMax + packTempMin) / 2f
            packTempMax != null -> packTempMax
            packTempMin != null -> packTempMin
            else -> null
        }

        // §7 bug #1 — stale HV current: if the PID didn't answer this tick,
        // we use the previous reading only if it's younger than a few polls.
        // Beyond that the UI drops to "--" (not a fabricated cached value).
        val staleCutoffMs = pollMs * STALE_TIMEOUT_MULT
        val effectiveCurrent: Float?
        val effectiveCurrentTs: Long?
        val effectivePower: Float?
        if (currentNow != null) {
            effectiveCurrent = currentNow
            effectiveCurrentTs = now
            effectivePower = powerKw
        } else if (previous.currentTimestamp != null &&
                   now - previous.currentTimestamp < staleCutoffMs) {
            effectiveCurrent = previous.current
            effectiveCurrentTs = previous.currentTimestamp
            effectivePower = previous.powerKw
        } else {
            effectiveCurrent = null
            effectiveCurrentTs = null
            effectivePower = null
        }

        // ── Handoff 2 analytics: feed the rolling window first, so everything
        // derived below (integrated capacity, ETA, slopes) sees this tick ────
        analytics.push(
            ChargeAnalytics.Sample(
                t = now,
                socHmi = socDisplay,
                socBms = socBms,
                tempAvg = finalAvg,
                powerKw = effectivePower,
                voltage = voltage,
                current = effectiveCurrent,
                mode = vehicleMode,
            )
        )

        // Pack ID + SOH + buffer breakdown.
        //
        // MEC (222AB2) is mute on this Born, and everything capacity-derived
        // used to hang off that single DID: SOH, buffers, confidence, the
        // charge ETA and the CSV history all collapsed to null together. Two
        // fallbacks break that chain:
        //   reference capacity — user override, else the MEC guess, else the
        //     77 kWh default the charge estimator already assumes;
        //   measured capacity  — the integrator's mid-range charge pass, the
        //     only real capacity measurement available on this car.
        // User override takes precedence over the auto-heuristic; AUTO falls
        // back to the MEC-based guess.
        val overrideChoice = _uiState.value.packTypeOverride
        val packType = overrideChoice.packType ?: guessPackType(mecKwh)
        val capacityOrig = referenceCapacityKwh(packType)
        val integratedKwh = analytics.energyIntegrator().lastResult()?.apparentCapacityKwh
        // Real MEC wins the day it answers; until then the integrated capacity
        // is the only honest numerator we have for SOH.
        val effectiveCapacity = mecKwh ?: integratedKwh
        val sohPct = effectiveCapacity?.let {
            (it / capacityOrig * 100f).coerceIn(0f, 110f)
        }
        val bufferBottom = effectiveCapacity?.let { BatteryBuffers.bottomReserveKwh(it) }
        val bufferTop    = effectiveCapacity?.let { BatteryBuffers.topReserveKwh(it) }
        val usable       = effectiveCapacity?.let { BatteryBuffers.usableKwh(it) }

        // §7 bug #2 — charge state from vehicle mode, not power sign.
        val chargeState = when (vehicleMode) {
            ObdPids.VehicleMode.CHARGING_AC -> ChargeState.AC_CHARGING
            ObdPids.VehicleMode.CHARGING_DC -> ChargeState.DC_CHARGING
            ObdPids.VehicleMode.DRIVING,
            ObdPids.VehicleMode.STANDBY     -> ChargeState.NOT_CHARGING
            ObdPids.VehicleMode.UNKNOWN     -> ChargeState.UNKNOWN
        }

        // Confidence follows the provenance of the number we actually showed.
        val (confidence, confReason) = classifyCapacityProvenance(
            mecKwh = mecKwh,
            integratedKwh = integratedKwh,
            tempAvg = finalAvg,
            socBms = socBms,
            mode = vehicleMode
        )

        // §7 bug #3 — Δ cellules computed app-side.
        val cellDelta = if (cellMinMv != null && cellMaxMv != null)
            cellMaxMv!! - cellMinMv!! else null

        val data = BatteryData(
            avgTemp = finalAvg,
            sensors = List(6) { null },
            cellTempMin = packTempMin,
            cellTempMax = packTempMax,
            coolantTempIn = coolantIn,
            coolantTempOut = coolantOut,
            coolantPumpPct = pumpPct,
            soc = socDisplay,
            socBms = socBms,
            voltage = voltage,
            current = effectiveCurrent,
            powerKw = effectivePower,
            currentTimestamp = effectiveCurrentTs,
            cellVoltMinMv = cellMinMv,
            cellVoltMaxMv = cellMaxMv,
            cellVoltDeltaMv = cellDelta,
            cellVoltMinIdx = cellMinIdx,
            cellVoltMaxIdx = cellMaxIdx,
            sohPct = sohPct,
            mecKwh = mecKwh,
            ecKwh = ecKwh,
            lifetimeChargeKwh = lifetimeChargeKwh,
            lifetimeDischargeKwh = lifetimeDischargeKwh,
            packType = packType,
            capacityOrigKwh = capacityOrig,
            bufferTopKwh = bufferTop,
            bufferBottomKwh = bufferBottom,
            usableKwh = usable,
            confidence = confidence,
            confidenceReason = confReason,
            vehicleMode = vehicleMode,
            volt12v = volt12v,
            chargeState = chargeState,
            timestamp = now
        )

        val avgPowerKw = analytics.avgPowerKw()
        val socSlope = analytics.socSlopePctPerMin()
        val tempSlope = analytics.tempSlopeCPerMin()
        val isCharging = chargeState == ChargeState.AC_CHARGING ||
                         chargeState == ChargeState.DC_CHARGING
        val projection = ChargeProjection(
            visible = isCharging,
            avgPowerKw = avgPowerKw,
            socSlopePctPerMin = socSlope,
            // The reference capacity is a fine ETA denominator even before any
            // measurement lands — it only scales the remaining-energy estimate.
            etaMinutesTo80 = analytics.etaMinutesTo(
                80f, socDisplay, effectiveCapacity ?: capacityOrig, chargeState),
            etaMinutesTo100 = analytics.etaMinutesTo(
                100f, socDisplay, effectiveCapacity ?: capacityOrig, chargeState),
            apparentCapacityKwh = integratedKwh,
        )
        val trajectory = classifyThermalTrajectory(
            tempAvg = finalAvg,
            slopeCPerMin = tempSlope,
            chargeState = chargeState,
            pumpPct = pumpPct,
        )

        analytics.energyIntegrator().lastResult()?.let { r ->
            // Cross-check log line: keeps a paper trail when the integrator
            // sees a complete mid-range pass, so we can sanity-check the
            // MEC reading against integrated energy in the .log file.
            if (r.timestampMs == now) {
                log(
                    "Capacité intégrée %.2f kWh sur ΔSOC %.1f %% (cross-check MEC)"
                        .format(r.apparentCapacityKwh, r.socDeltaPct),
                    LogLevel.OK
                )
            }
        }

        _uiState.update {
            it.copy(
                batteryData = data,
                chargeProjection = projection,
                thermalTrajectory = trajectory
            )
        }

        if (finalAvg != null) {
            log(
                "Relevé OK — Moy. %.1f°C | SOC %.0f%% | %s%s".format(
                    finalAvg,
                    socDisplay ?: 0f,
                    chargeState.label,
                    sohPct?.let { " | SOH %.1f%%".format(it) } ?: ""
                ),
                LogLevel.OK
            )
        } else {
            log("Relevé : aucune donnée température reçue.", LogLevel.WARN)
        }

        // CSV history — append at most every 30 s. This used to require an MEC
        // reading, which this car never returns, so the file only ever held its
        // header. The empty cells sohSample() writes for null floats keep it
        // spreadsheet-friendly, so we log whatever the BMS did answer and skip
        // only rows that would carry no measurement at all.
        val hasAnyMeasurement = finalAvg != null || socBms != null
        if (hasAnyMeasurement && now - lastSohSampleMs >= SOH_CSV_MIN_INTERVAL_MS) {
            capture.sohSample(
                timestampMs = now,
                mecKwh = mecKwh,
                ecKwh = ecKwh,
                socHmi = socDisplay,
                socBms = socBms,
                tMin = packTempMin,
                tMax = packTempMax,
                tAvg = finalAvg,
                coolantIn = coolantIn,
                coolantOut = coolantOut,
                pumpPct = pumpPct,
                vehicleMode = vehicleMode.name,
                sohPct = sohPct,
                confidence = confidence.name,
                voltageHv = voltage,
                currentHv = effectiveCurrent,
                powerKw = effectivePower
            )
            lastSohSampleMs = now
        }

        // ── ABRP telemetry ─────────────────────────────────────────────────
        val abrp = _uiState.value.abrp
        if (abrp.enabled && abrp.apiKey.isNotBlank() && abrp.userToken.isNotBlank()) {
            val result = abrpClient.send(
                apiKey = abrp.apiKey,
                userToken = abrp.userToken,
                data = data,
                location = locationProvider.snapshot()
            )
            _uiState.update { state ->
                state.copy(abrp = state.abrp.copy(
                    lastSendOk = result.success,
                    lastSendMessage = result.message,
                    lastSendTimeMs = now
                ))
            }
            if (!result.success) {
                log("ABRP ✗ ${result.message}", LogLevel.WARN)
            }
        }
    }
}
