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
import com.borntemp.app.domain.PidPolicy
import com.borntemp.app.domain.PollIntervals
import com.borntemp.app.domain.ReadStatus
import com.borntemp.app.domain.Reading
import com.borntemp.app.domain.Signal
import com.borntemp.app.domain.Cadence
import com.borntemp.app.domain.CandidateTarget
import com.borntemp.app.domain.SessionSample
import com.borntemp.app.domain.SignalCandidates
import com.borntemp.app.domain.classifyUdsResponse
import com.borntemp.app.domain.ratePerMin
import com.borntemp.app.domain.resolvePower
import com.borntemp.app.domain.summarizeSession
import com.borntemp.app.domain.thermalMetrics
import com.borntemp.app.domain.packAverageTemp
import com.borntemp.app.domain.shouldWriteCsvRow
import com.borntemp.app.domain.readingOf
import com.borntemp.app.domain.shouldRetry
import com.borntemp.app.domain.udsTxFrame
import com.borntemp.app.obd.ChargeSessionLogger
import com.borntemp.app.obd.MonitoredDeviceStore
import com.borntemp.app.obd.ObdBeaconReceiver
import com.borntemp.app.obd.ObdPids
import com.borntemp.app.obd.SessionCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
    private val sessionLogger = ChargeSessionLogger(
        application.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
    )
    private val analytics = ChargeAnalytics()
    private var pollingJob: Job? = null
    private var pollCounter = 0L
    private var lastSohSampleMs = 0L
    /** Last unmapped 7448 frame already traced, so the diagnostic fires once
     *  per distinct value instead of on every poll. */
    private var lastUnknownModeRaw: String? = null
    private val counterCapacity = LifetimeCounterCapacity()
    /** Consecutive slow ticks where MEC, EC and 12V-via-EM all failed. Past
     *  [MUTE_ENERGY_DIDS_AFTER] they're skipped for the rest of the connection:
     *  on this Born EM answers NO DATA after a ~1 s timeout and BMS/BREG reply
     *  7F 22 31, which cost ~3.5 s every slow tick in every capture so far. */
    private var energyDidMisses = 0

    private data class RatePoint(val t: Long, val tMax: Float?, val socHmi: Float?)
    /** Last [RATE_WINDOW_MS] of T max / SOC HMI for the dT/dt and dSOC/dt slopes. */
    private val rateHistory = ArrayDeque<RatePoint>()
    /** Samples of the current (or last) charge, for the Session tab. */
    private val chargeSamples = mutableListOf<SessionSample>()
    private var wasCharging = false
    /** Mode at the last candidate run; a change re-runs the scans. */
    private var lastCandidateMode: ObdPids.VehicleMode? = null

    private val abrpSettings = AbrpSettings(application)
    private val abrpClient = AbrpTelemetryClient()
    private val locationProvider = LocationProvider(application)
    private val batterySettings = BatterySettings(application)
    private val acquisitionSettings = AcquisitionSettings(application)
    private val monitoredDeviceStore = MonitoredDeviceStore(application)

    init {
        _uiState.update {
            it.copy(
                abrp = AbrpUiState(
                    enabled = abrpSettings.enabled,
                    apiKey = abrpSettings.apiKey,
                    userToken = abrpSettings.userToken
                ),
                packTypeOverride = batterySettings.packTypeOverride,
                chargingPollingIntervalMs = acquisitionSettings.chargingPollMs,
                enabledCandidates = acquisitionSettings.enabledCandidates
            )
        }
        if (abrpSettings.enabled) locationProvider.start()
        // Phase-1 UDS trace: every adapter exchange, classified, to its own file.
        obdManager.exchangeListener = BluetoothObdManager.ExchangeListener { ecu, command, response, latencyMs ->
            capture.uds(
                timestampMs = System.currentTimeMillis(),
                ecu = ecu?.name,
                command = command,
                txFrame = udsTxFrame(ecu?.fullRequestId ?: ObdPids.ECU_BMS.fullRequestId, command),
                response = response,
                latencyMs = latencyMs,
                result = classifyUdsResponse(command, response),
            )
        }
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

        private const val DEBUG_RAW_RESPONSES = true

        private const val MUTE_ENERGY_DIDS_AFTER = 3

        /** Sliding window for dT/dt and dSOC/dt: ≥ 2 BMS SOC steps at 50 kW. */
        private const val RATE_WINDOW_MS = 120_000L
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

    /** Raw text of the last [read], for diagnostics that need the frame. */
    private var lastRawResponse: String? = null

    /**
     * Acquire one PID as a [Reading]. Never throws and never blocks longer than
     * the policy's timeout per attempt, so a failing PID can't stall the cycle.
     * Retries on TIMEOUT only (see [shouldRetry]); an NRC is final.
     */
    private suspend fun <T> read(
        pid: String,
        ecu: ObdPids.EcuTarget,
        policy: PidPolicy = PidPolicy.FAST,
        parse: (String) -> T?,
    ): Reading<T> {
        var attempt = 0
        while (true) {
            val t = System.currentTimeMillis()
            val resp = try {
                obdManager.sendCommand(pid, ecu, policy.timeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            recordQuery("${ecu.name}:$pid", resp)
            lastRawResponse = resp
            val result = classifyUdsResponse(pid, resp)
            val parsed = if (result.status == ReadStatus.OK && resp != null) {
                runCatching { parse(resp) }.getOrNull()
            } else null
            val reading = readingOf(result, parsed, t)
            if (!shouldRetry(reading.status, attempt, policy)) return reading
            attempt++
        }
    }

    /**
     * Run the identification candidates due this tick. Every exchange is in the
     * UDS trace already; this adds one EVENT line per candidate with its
     * outcome, so the .log reads as a summary. Returns the first good 12 V
     * reading from a V12 candidate, if any ran.
     */
    private suspend fun runCandidates(
        enabled: Set<String>,
        slowTick: Boolean,
        stateChanged: Boolean,
    ): Reading<Float>? {
        var v12: Reading<Float>? = null
        for (c in SignalCandidates.due(enabled, slowTick, stateChanged)) {
            var ok = 0
            var failed = 0
            for (cmd in c.commands) {
                val r: Reading<Float?> = when (c.ecu) {
                    "ADAPTER" -> readAdapter(cmd) { ObdPids.parseAtrv(it) }
                    "SWEEP" -> {
                        val (ids, pid) = cmd.split(':')
                        val (req, resp) = ids.split('/')
                        read(pid, sweepTarget(req, resp)) { it as Any? }.let {
                            Reading(null, it.status, it.timestampMs, it.detail, it.nrc)
                        }
                    }
                    else -> {
                        val ecu = ecuByName(c.ecu) ?: continue
                        read(cmd, ecu) { raw ->
                            if (c.id == "v12_dcdc_465d") ObdPids.parseDcdcVoltage(raw) else Float.NaN
                        }
                    }
                }
                if (r.status == ReadStatus.OK) ok++ else failed++
                if (c.target == CandidateTarget.V12 && r.isOk && v12 == null) {
                    v12 = Reading.ok(r.value!!, r.timestampMs)
                }
            }
            if (c.cadence != Cadence.FAST) {
                capture.event("CANDIDAT ${c.id} — ${c.commands.size} requêtes, $ok OK, $failed en échec")
            }
        }
        return v12
    }

    /** AT command to the adapter itself (no ECU switch), as a Reading. */
    private suspend fun <T> readAdapter(command: String, parse: (String) -> T?): Reading<T> {
        val t = System.currentTimeMillis()
        val resp = try {
            obdManager.sendCommand(command)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        recordQuery(command, resp)
        val parsed = resp?.let { runCatching { parse(it) }.getOrNull() }
        return readingOf(classifyUdsResponse(command, resp), parsed, t)
    }

    private fun ecuByName(name: String): ObdPids.EcuTarget? = when (name) {
        "BMS" -> ObdPids.ECU_BMS
        "EM" -> ObdPids.ECU_EM
        "DCDC" -> ObdPids.ECU_DCDC
        "BREG" -> ObdPids.ECU_BATTERY_REG
        "CHG" -> ObdPids.ECU_CHARGING
        else -> null
    }

    /** Ad-hoc target for an address the app doesn't model yet. */
    private fun sweepTarget(requestId: String, responseId: String) = ObdPids.EcuTarget(
        name = "ADDR_${requestId.takeLast(2)}",
        requestHeader = requestId.takeLast(6),
        responseId = responseId,
        fullRequestId = requestId,
    )

    /**
     * Try several ECUs for one DID; first OK reading wins. Used for MEC/EC,
     * whose host is unknown on this Born. When all fail, the last ECU's
     * reading is returned so the status still explains the gap.
     */
    private suspend fun <T> readFirstOk(
        pid: String,
        vararg ecus: ObdPids.EcuTarget,
        parse: (String) -> T?,
    ): Reading<T> {
        var last: Reading<T> = Reading.notSupported(System.currentTimeMillis())
        for (e in ecus) {
            last = read(pid, e, parse = parse)
            if (last.isOk) return last
        }
        return last
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

    fun applyAcquisitionSetting(setting: AcquisitionSetting) {
        when (setting) {
            is AcquisitionSetting.ChargingPollInterval -> {
                val ms = setting.ms.coerceIn(PollIntervals.CHARGING_RANGE)
                acquisitionSettings.chargingPollMs = ms
                _uiState.update { it.copy(chargingPollingIntervalMs = ms) }
            }
            is AcquisitionSetting.Candidate -> {
                val next = acquisitionSettings.enabledCandidates.let {
                    if (setting.enabled) it + setting.id else it - setting.id
                }
                acquisitionSettings.enabledCandidates = next
                _uiState.update { it.copy(enabledCandidates = next) }
            }
        }
    }

    /** Interval for the next tick: 5–10 s while charging, the user's
     *  (looser) interval otherwise. */
    private fun currentPollIntervalMs(): Long {
        val st = _uiState.value
        val charging = st.batteryData.chargeState == ChargeState.AC_CHARGING ||
                       st.batteryData.chargeState == ChargeState.DC_CHARGING
        return PollIntervals(st.chargingPollingIntervalMs, st.pollingIntervalMs).forMode(charging)
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
                    udsTraceFileUri = capture.shareUdsUri(),
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
        counterCapacity.reset()
        rateHistory.clear()
        chargeSamples.clear()
        wasCharging = false
        lastCandidateMode = null
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
        sessionLogger.endConnection()
    }

    // ── Polling ──────────────────────────────────────────────────────────────

    private fun startPolling() {
        pollingJob?.cancel()
        pollCounter = 0L
        energyDidMisses = 0
        pollingJob = scope.launch {
            _uiState.update { it.copy(isPolling = true) }
            while (isActive && obdManager.isConnected) {
                // A throwing tick must not end the session: log it and poll on.
                try {
                    readAllData()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    capture.event("Tick en échec: ${e.javaClass.simpleName}: ${e.message}")
                }
                delay(currentPollIntervalMs())
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

    /** Flush capture files without closing them (Activity onStop, service
     *  teardown): background polling keeps writing afterwards. */
    fun flushCapture() = capture.flush()

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
        val pollMs = currentPollIntervalMs()

        // ── BMS fast block ────────────────────────────────────────────────
        // Every read yields a Reading: a value, or the status saying why not.
        // T max is the first request of the tick: after ~40 min the BMS starts
        // dropping it with NO DATA (168 of 750 on 2026-09-26 19:13, never
        // another PID), so it alone gets one retry.
        val tMaxR = read(ObdPids.PID_PACK_TEMP_MAX, ObdPids.ECU_BMS,
            PidPolicy(timeoutMs = PidPolicy.FAST.timeoutMs, retries = 1)) { ObdPids.parsePackTemp(it) }
        val tMinR = read(ObdPids.PID_PACK_TEMP_MIN, ObdPids.ECU_BMS) { ObdPids.parsePackTemp(it) }
        val socBmsR = read(ObdPids.PID_SOC_BMS, ObdPids.ECU_BMS) { ObdPids.parseSocBms(it) }
        val voltageR = read(ObdPids.PID_PACK_VOLTAGE, ObdPids.ECU_BMS) { ObdPids.parsePackVoltage(it) }
        // BMS sign convention confirmed in the field 2026-06-21 13:31 log:
        // raw + = OUT of pack. We flip here so the rest of the codebase uses
        // the "driver mental model": + into pack (charge / regen), − out.
        // parsePackCurrent returns null until 1E3C is decoded, so this reads
        // as PARSE_ERROR — the frame still lands in the UDS trace.
        val currentR = read(ObdPids.PID_PACK_CURRENT, ObdPids.ECU_BMS) {
            ObdPids.parsePackCurrent(it)?.let { raw -> -raw }
        }
        val packTempMax = tMaxR.value
        val packTempMin = tMinR.value
        val socBms = socBmsR.value
        val socDisplay = ObdPids.bmsSocToDisplay(socBms)
        val voltage = voltageR.value
        val currentNow = currentR.value

        // ── Vehicle mode + pump (fast — affects state + thermal) ────────
        val modeR = read(ObdPids.PID_VEHICLE_MODE, ObdPids.ECU_BMS) {
            ObdPids.parseVehicleMode(it).takeUnless { m -> m == ObdPids.VehicleMode.UNKNOWN }
        }
        val vehicleMode = modeR.value ?: previous.vehicleMode
        // An unmapped 7448 code reads as PARSE_ERROR; trace the frame rather
        // than guess a mapping — once per distinct raw value.
        if (modeR.status == ReadStatus.PARSE_ERROR && lastRawResponse != lastUnknownModeRaw) {
            lastUnknownModeRaw = lastRawResponse
            capture.event("VEHICLE_MODE non mappé (7448) — trame: ${lastRawResponse?.trim()}")
        }
        val pumpR = read(ObdPids.PID_COOLANT_PUMP, ObdPids.ECU_BMS) { ObdPids.parseCoolantPump(it) }
        val pumpPct = pumpR.value ?: previous.coolantPumpPct

        // ── Slow block ────────────────────────────────────────────────────
        // Off-tick, slow signals keep their last reading (and its timestamp).
        val held = previous.readings
        fun <T> heldReading(sig: Signal): Reading<T> {
            @Suppress("UNCHECKED_CAST")
            return (held[sig] as Reading<T>?) ?: Reading.notSupported(now, "pas encore lu")
        }
        var coolantR: Reading<Pair<Float?, Float?>> = heldReading(Signal.COOLANT)
        var cellMinR: Reading<ObdPids.CellExtreme> = heldReading(Signal.CELL_MIN)
        var cellMaxR: Reading<ObdPids.CellExtreme> = heldReading(Signal.CELL_MAX)
        var mecR: Reading<Float> = heldReading(Signal.MEC)
        var ecR: Reading<Float> = heldReading(Signal.EC)
        var v12R: Reading<Float> = heldReading(Signal.V_12)
        var v12FreshFromEm = false
        var lifetimeR: Reading<Pair<Float?, Float?>> = heldReading(Signal.LIFETIME_ENERGY)

        if (isSlowTick) {
            coolantR = read(ObdPids.PID_COOLANT_TEMPS, ObdPids.ECU_BMS) {
                ObdPids.parseCoolantTemps(it).takeIf { (ci, co) -> ci != null || co != null }
            }
            // Two PIDs, not four: 1E33 / 1E34 each return the extreme cell's
            // voltage together with its index.
            cellMinR = read(ObdPids.PID_CELL_VOLT_MIN_IDX, ObdPids.ECU_BMS) { ObdPids.parseCellExtreme(it) }
            cellMaxR = read(ObdPids.PID_CELL_VOLT_MAX_IDX, ObdPids.ECU_BMS) { ObdPids.parseCellExtreme(it) }

            // MEC / EC / 12V — EM first (CSV-documented host), then BMS/BREG.
            // None has ever answered on this Born; after MUTE_ENERGY_DIDS_AFTER
            // all-silent slow ticks they're skipped and report NOT_SUPPORTED.
            if (energyDidMisses < MUTE_ENERGY_DIDS_AFTER) {
                mecR = readFirstOk(ObdPids.PID_MEC, ObdPids.ECU_EM, ObdPids.ECU_BMS,
                    ObdPids.ECU_BATTERY_REG) { ObdPids.parseEnergyKwh(it) }
                ecR = readFirstOk(ObdPids.PID_EC, ObdPids.ECU_EM, ObdPids.ECU_BMS,
                    ObdPids.ECU_BATTERY_REG) { ObdPids.parseEnergyKwh(it) }
                v12R = read(ObdPids.PID_12V_VIA_EM, ObdPids.ECU_EM) { ObdPids.parse12vVoltageEm(it) }
                v12FreshFromEm = v12R.isOk
                if (!mecR.isOk && !ecR.isOk && !v12R.isOk) {
                    energyDidMisses++
                    if (energyDidMisses == MUTE_ENERGY_DIDS_AFTER) {
                        capture.event("MEC/EC/12V EM muets ${MUTE_ENERGY_DIDS_AFTER}× — plus interrogés jusqu'à la reconnexion")
                    }
                } else {
                    energyDidMisses = 0
                }
            } else {
                val why = "muet ${MUTE_ENERGY_DIDS_AFTER}× cette session"
                mecR = mecR.heldOr(Reading.notSupported(now, why))
                ecR = ecR.heldOr(Reading.notSupported(now, why))
                v12R = v12R.heldOr(Reading.notSupported(now, why))
            }
            // Lifetime cumulative energy — confirmed working on the BMS.
            val freshLifetime = read(ObdPids.PID_LIFETIME_ENERGY, ObdPids.ECU_BMS, PidPolicy.MULTI_FRAME) {
                ObdPids.parseLifetimeEnergy(it).takeIf { (c, d) -> c != null || d != null }
            }
            // Only on a fresh read — see LifetimeCounterCapacity.
            if (freshLifetime.isOk) {
                counterCapacity.feed(now, socDisplay, freshLifetime.value?.first, vehicleMode)
            }
            lifetimeR = freshLifetime
        }

        // ── Phase-5 identification candidates ───────────────────────────
        // Raw frames go to the UDS trace; only the 12 V sources feed a value,
        // as fallbacks while EM 2AF7 stays silent (it always has).
        val stateChanged = vehicleMode != lastCandidateMode
        lastCandidateMode = vehicleMode
        val v12Fallback = runCandidates(_uiState.value.enabledCandidates, isSlowTick, stateChanged)
        // A held reading must not block a fresh one: only a value EM returned
        // this very tick outranks the fallbacks.
        if (!v12FreshFromEm && v12Fallback != null) v12R = v12Fallback

        val coolantIn = coolantR.value?.first
        val coolantOut = coolantR.value?.second
        val cellMinMv = cellMinR.value?.millivolts
        val cellMaxMv = cellMaxR.value?.millivolts
        val cellMinIdx = cellMinR.value?.index
        val cellMaxIdx = cellMaxR.value?.index
        val mecKwh = mecR.value
        val ecKwh = ecR.value
        val volt12v = v12R.value
        // Counters only ever grow: a failed read keeps the last known totals.
        val lifetimeChargeKwh = lifetimeR.value?.first ?: previous.lifetimeChargeKwh
        val lifetimeDischargeKwh = lifetimeR.value?.second ?: previous.lifetimeDischargeKwh

        val readings: Map<Signal, Reading<*>> = mapOf(
            Signal.T_MAX to tMaxR,
            Signal.T_MIN to tMinR,
            Signal.SOC_BMS to socBmsR,
            Signal.V_HV to voltageR,
            Signal.I_HV to currentR,
            Signal.MODE to modeR,
            Signal.PUMP to pumpR,
            Signal.COOLANT to coolantR,
            Signal.CELL_MIN to cellMinR,
            Signal.CELL_MAX to cellMaxR,
            Signal.MEC to mecR,
            Signal.EC to ecR,
            Signal.V_12 to v12R,
            Signal.LIFETIME_ENERGY to lifetimeR,
        )

        // ── Derived ──────────────────────────────────────────────────────────
        val powerKw = if (voltage != null && currentNow != null)
            voltage * currentNow / 1000f else null
        val finalAvg = packAverageTemp(packTempMin, packTempMax)

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
        // Measured capacity, best source first: the lifetime counter (works on
        // this car), the power integrator (needs pack current, currently null),
        // then the last measurement persisted from an earlier session.
        val measured = counterCapacity.lastResult() ?: analytics.energyIntegrator().lastResult()
        if (measured != null && measured.timestampMs == now) {
            batterySettings.measuredCapacityKwh = measured.apparentCapacityKwh
        }
        val integratedKwh = measured?.apparentCapacityKwh ?: batterySettings.measuredCapacityKwh
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
            cellMaxMv - cellMinMv else null

        // ── Phase-4 derived metrics (domain, pure) ─────────────────────────
        val isCharging = chargeState == ChargeState.AC_CHARGING ||
                         chargeState == ChargeState.DC_CHARGING
        rateHistory.addLast(RatePoint(now, packTempMax, socDisplay))
        while (rateHistory.isNotEmpty() && rateHistory.first().t < now - RATE_WINDOW_MS) rateHistory.removeFirst()
        val tMaxRate = ratePerMin(rateHistory.mapNotNull { p -> p.tMax?.let { p.t to it } }, RATE_WINDOW_MS)
        val socRate = ratePerMin(rateHistory.mapNotNull { p -> p.socHmi?.let { p.t to it } }, RATE_WINDOW_MS)
        // Energy per 100 % HMI SOC: the measured capacity, else the reference.
        val power = resolvePower(voltage, effectiveCurrent, socRate, integratedKwh ?: capacityOrig)
        val derived = DerivedSnapshot(
            thermal = thermalMetrics(packTempMin, packTempMax, coolantIn, coolantOut),
            tMaxRatePerMin = tMaxRate,
            socRatePerMin = socRate,
            power = power,
        )
        // Session tab: the current charge, or the last one once it ends.
        if (isCharging) {
            if (!wasCharging) chargeSamples.clear()
            chargeSamples += SessionSample(now, socDisplay, packTempMin, packTempMax, coolantIn, coolantOut, power)
        }
        wasCharging = isCharging
        val chargeSummary = summarizeSession(chargeSamples)

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
            readings = readings,
            derived = derived,
            timestamp = now
        )

        sessionLogger.recordSample(
            timestampMs = now,
            socPercent = socDisplay,
            batteryTempC = finalAvg,
            chargePowerKw = effectivePower,
            chargeCurrentA = effectiveCurrent,
            chargeVoltageV = voltage,
            chargeState = chargeState,
        )

        val avgPowerKw = analytics.avgPowerKw()
        val socSlope = analytics.socSlopePctPerMin()
        val tempSlope = analytics.tempSlopeCPerMin()
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

        measured?.let { r ->
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
                chargeSummary = chargeSummary,
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
        val hasAnyMeasurement = packTempMin != null || packTempMax != null || socBms != null
        val charging = chargeState == ChargeState.AC_CHARGING || chargeState == ChargeState.DC_CHARGING
        if (hasAnyMeasurement && shouldWriteCsvRow(lastSohSampleMs, now, charging)) {
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
                powerKw = power?.kw,
                powerSource = power?.source?.name,
                volt12v = volt12v,
                readings = readings,
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
