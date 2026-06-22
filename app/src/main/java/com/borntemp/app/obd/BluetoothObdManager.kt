package com.borntemp.app.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * Manages Bluetooth Low Energy (BLE / GATT) communication with the OBDLink CX.
 *
 * The OBDLink CX is a BLE-only adapter (it does NOT expose a Bluetooth Classic
 * SPP/RFCOMM port). It presents a custom UART-style GATT service:
 *   - Service          FFF0
 *   - Notify  (RX/read) FFF1   — adapter pushes ELM327 output as notifications
 *   - Write   (TX)      FFF2   — write-with-response, one write at a time
 *
 * ELM327 responses are framed by the '>' prompt character, exactly as over a
 * serial link; we accumulate notification payloads until that prompt arrives.
 */
class BluetoothObdManager(private val context: Context) {

    companion object {
        // OBDLink CX custom UART service / characteristics.
        private val SERVICE_UUID: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        private val NOTIFY_UUID: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
        private val WRITE_UUID: UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
        // Standard Client Characteristic Configuration Descriptor.
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private const val CONNECT_TIMEOUT_MS = 15000L
        // 5s — must exceed the ELM327's own ATST FF timeout (~1020ms) plus
        // BLE transport overhead, so we don't bail before the adapter does.
        private const val RESPONSE_TIMEOUT_MS = 5000L
        private const val WRITE_TIMEOUT_MS = 2000L
        // Brief settle between init commands. These are local ELM AT commands
        // (no CAN round-trip), so they need only a short pause — not 300ms,
        // which alone added ~5s of dead time to every connect.
        private const val INIT_DELAY_MS = 80L

        // Ask for the largest MTU; the CX negotiates down to 247 max.
        private const val REQUESTED_MTU = 512
        private const val DEFAULT_MTU = 23
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var currentHeader: String? = null
    private var currentEcu: ObdPids.EcuTarget? = null

    @Volatile private var connected = false
    @Volatile private var negotiatedMtu = DEFAULT_MTU

    // Serialises commands — ELM327 is half-duplex, one request/response at a time.
    private val commandMutex = Mutex()

    // One-shot signals completed from the GATT callback thread.
    @Volatile private var connectDeferred: CompletableDeferred<Result<Unit>>? = null
    @Volatile private var writeDeferred: CompletableDeferred<Boolean>? = null

    // Notification accumulator + the response awaiter, guarded by [bufferLock].
    private val bufferLock = Any()
    private val assembler = ElmResponseAssembler()
    private var responseDeferred: CompletableDeferred<String>? = null

    val isConnected: Boolean
        get() = connected

    // ── GATT callback ─────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Negotiate MTU first, then discover services in onMtuChanged.
                    if (!g.requestMtu(REQUESTED_MTU)) {
                        g.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    failConnect(IOException("Déconnexion BLE (status=$status)"))
                    // Unblock any in-flight command/write so it doesn't hang.
                    completeResponse(forceEmpty = true)
                    writeDeferred?.takeIf { !it.isCompleted }?.complete(false)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnect(IOException("Découverte des services échouée (status=$status)"))
                return
            }
            val service = g.getService(SERVICE_UUID) ?: run {
                failConnect(IOException("Service FFF0 introuvable — l'appareil n'est pas un OBDLink CX BLE"))
                return
            }
            writeChar = service.getCharacteristic(WRITE_UUID)
            notifyChar = service.getCharacteristic(NOTIFY_UUID)
            val notify = notifyChar
            if (writeChar == null || notify == null) {
                failConnect(IOException("Caractéristiques FFF1/FFF2 introuvables"))
                return
            }
            // Subscribe to RX notifications (also triggers pairing on the CX).
            if (!g.setCharacteristicNotification(notify, true)) {
                failConnect(IOException("Impossible d'activer les notifications FFF1"))
                return
            }
            val cccd = notify.getDescriptor(CCCD_UUID) ?: run {
                failConnect(IOException("Descripteur CCCD (2902) introuvable"))
                return
            }
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (!g.writeDescriptor(cccd)) {
                    failConnect(IOException("Écriture du CCCD échouée"))
                }
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                connectDeferred?.takeIf { !it.isCompleted }?.complete(Result.success(Unit))
            } else {
                failConnect(IOException("Activation des notifications refusée (status=$status)"))
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == WRITE_UUID) {
                writeDeferred?.takeIf { !it.isCompleted }
                    ?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != NOTIFY_UUID) return
            val data = characteristic.value ?: return
            appendResponse(String(data, Charsets.US_ASCII))
        }
    }

    private fun failConnect(error: Throwable) {
        connectDeferred?.takeIf { !it.isCompleted }?.complete(Result.failure(error))
    }

    // Append a notification payload; complete the awaiter once the prompt arrives.
    private fun appendResponse(text: String) {
        val ready: CompletableDeferred<String>?
        val full: String
        synchronized(bufferLock) {
            full = assembler.append(text) ?: return
            ready = responseDeferred
            responseDeferred = null
        }
        ready?.takeIf { !it.isCompleted }?.complete(full)
    }

    private fun completeResponse(forceEmpty: Boolean) {
        val ready: CompletableDeferred<String>?
        synchronized(bufferLock) {
            ready = responseDeferred
            responseDeferred = null
            assembler.reset()
        }
        if (forceEmpty) ready?.takeIf { !it.isCompleted }?.complete("")
    }

    // ── Connection ──────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Result<Unit> = withContext(Dispatchers.IO) {
        disconnect()

        val deferred = CompletableDeferred<Result<Unit>>()
        connectDeferred = deferred

        val g = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            ?: return@withContext Result.failure(IOException("connectGatt a renvoyé null"))
        gatt = g

        val result = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { deferred.await() }
        when {
            result == null -> {
                disconnect()
                Result.failure(IOException("Délai de connexion BLE dépassé (15 s)"))
            }
            result.isFailure -> {
                disconnect()
                result
            }
            else -> result
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        connected = false
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        writeChar = null
        notifyChar = null
        currentHeader = null
        currentEcu = null
        negotiatedMtu = DEFAULT_MTU
        completeResponse(forceEmpty = true)
        connectDeferred?.takeIf { !it.isCompleted }
            ?.complete(Result.failure(IOException("Connexion annulée")))
        connectDeferred = null
    }

    // ── Command execution ───────────────────────────────────────────────────

    /**
     * Send an AT or OBD command and wait for the '>' prompt.
     * If [header] differs from the last one set, send ATSH<header> first.
     * Returns the raw response string, or null on timeout/error.
     */
    suspend fun sendCommand(command: String, header: String? = null): String? =
        withContext(Dispatchers.IO) {
            if (header != null && header != currentHeader) {
                sendRaw("ATSH$header")
                currentHeader = header
            }
            sendRaw(command)
        }

    /**
     * Send a command targeting a specific ECU. Switches ATSH + ATCRA +
     * ATFCSH + STCFCPA when [ecu] differs from the last live target — the
     * BMS init only sets up the flow-control pair for 17FC007B, so jumping
     * to module 0x10 (energy mgmt, MEC/EC/12V) without re-issuing them
     * causes every reply to be filtered out.
     */
    suspend fun sendCommand(command: String, ecu: ObdPids.EcuTarget): String? =
        withContext(Dispatchers.IO) {
            if (currentEcu != ecu) {
                for (c in ObdPids.ecuSwitchCommands(ecu)) sendRaw(c)
                currentEcu = ecu
                currentHeader = ecu.requestHeader
            }
            sendRaw(command)
        }

    private suspend fun sendRaw(command: String): String? = withContext(Dispatchers.IO) {
        val g = gatt ?: return@withContext null
        val wc = writeChar ?: return@withContext null
        if (!connected) return@withContext null

        commandMutex.withLock {
            val respDef = CompletableDeferred<String>()
            synchronized(bufferLock) {
                assembler.reset()
                responseDeferred = respDef
            }

            if (!writeChunked(g, wc, (command + "\r").toByteArray(Charsets.US_ASCII))) {
                synchronized(bufferLock) { responseDeferred = null }
                return@withLock null
            }

            val resp = withTimeoutOrNull(RESPONSE_TIMEOUT_MS) { respDef.await() }
            synchronized(bufferLock) { responseDeferred = null }
            resp?.takeIf { it.isNotEmpty() }?.trim()
        }
    }

    // Writes [bytes] over FFF2 in MTU-sized chunks, awaiting each write response.
    @SuppressLint("MissingPermission")
    private suspend fun writeChunked(
        g: BluetoothGatt,
        wc: BluetoothGattCharacteristic,
        bytes: ByteArray
    ): Boolean {
        // 3 bytes of ATT overhead for a write-with-response.
        val chunkSize = (negotiatedMtu - 3).coerceAtLeast(20)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)

            val writeDef = CompletableDeferred<Boolean>()
            writeDeferred = writeDef

            val started = @Suppress("DEPRECATION") run {
                wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                wc.value = chunk
                g.writeCharacteristic(wc)
            }
            if (!started) return false

            val ok = withTimeoutOrNull(WRITE_TIMEOUT_MS) { writeDef.await() } ?: false
            if (!ok) return false
            offset = end
        }
        return true
    }

    // ── ELM327 initialisation ───────────────────────────────────────────────

    /**
     * Run the ELM327 init sequence. [onProgress] is invoked after each command
     * completes so the caller can surface live progress, instead of the UI
     * sitting on a silent "INIT..." for the whole sequence.
     */
    suspend fun initializeElm(
        onProgress: (suspend (cmd: String, resp: String?) -> Unit)? = null
    ): List<Pair<String, String?>> {
        val results = mutableListOf<Pair<String, String?>>()
        for (cmd in ObdPids.INIT_SEQUENCE) {
            val resp = sendCommand(cmd)
            results.add(cmd to resp)
            onProgress?.invoke(cmd, resp)
            delay(INIT_DELAY_MS)
        }
        currentHeader = ObdPids.HEADER_BMS
        currentEcu = ObdPids.ECU_BMS
        return results
    }

    // ── Paired device helpers ───────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun findObdLinkDevice(adapter: BluetoothAdapter): BluetoothDevice? {
        return adapter.bondedDevices.firstOrNull { device ->
            device.name?.contains("OBDLink", ignoreCase = true) == true ||
            device.name?.contains("OBDLINK", ignoreCase = true) == true ||
            device.name?.contains("OBD", ignoreCase = true) == true
        }
    }

    @SuppressLint("MissingPermission")
    fun getPairedObdDevices(adapter: BluetoothAdapter): List<BluetoothDevice> {
        return adapter.bondedDevices.filter { device ->
            device.name?.contains("OBD", ignoreCase = true) == true ||
            device.name?.contains("ELM", ignoreCase = true) == true
        }
    }
}
