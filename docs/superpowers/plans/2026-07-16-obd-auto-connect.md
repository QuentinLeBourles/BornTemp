# OBD Auto-Connect Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-connect BornTemp to the OBDLink CX the moment the car is detected, and keep polling + pushing to ABRP for the whole drive with the screen off and the app closed, with no way to end up with two independent connections to the adapter.

**Architecture:** Extract the connect/poll/ABRP-push logic that currently lives in `MainViewModel` into a plain `ObdSessionController` class, held as a single process-wide instance by a custom `Application` subclass. A new `ObdForegroundService`, started by a `BroadcastReceiver` that watches for the OBDLink CX's BLE advertisement, drives that same controller instance headlessly; `MainViewModel` becomes a thin adapter over it. Because there is structurally only one `ObdSessionController` (and therefore only one `BluetoothObdManager`) per process, manual UI use and the background service can never open a second connection.

*Deviation from the spec:* the design doc called out `ObdSessionController` and `ObdSessionRepository` as two separate classes. This plan merges them — `ObdSessionController` exposes its own `uiState: StateFlow<UiState>` directly, and both `MainViewModel` and `ObdForegroundService` read that same instance. A separate repository class would hold no state of its own (the controller already is the single source of truth); splitting it out was spec-time granularity that turned out to be unnecessary once the controller's shape was nailed down here.

**Tech Stack:** Kotlin, Jetpack Compose, Android BLE (`BluetoothLeScanner` PendingIntent scanning), Android foreground `Service` (`connectedDevice` type), `SharedPreferences`, JUnit (plain JVM, no Robolectric present in this project).

## Global Constraints

- `compileSdk = 34`, `minSdk = 26`, `targetSdk = 34` (`app/build.gradle.kts:31-33`) — every new API must be guarded for `minSdk 26` or version-checked.
- `BLUETOOTH_SCAN` must be declared with `android:usesPermissionFlags="neverForLocation"` — detection filters on service UUID only, never on derived location.
- Foreground service must declare `foregroundServiceType="connectedDevice"` and request `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- Connect retry is capped at **5 attempts**, **~3s** apart; a distinct failure notification fires only after all 5 fail.
- Background session auto-stops the moment the adapter disconnects/goes out of range (no manual step).
- UI copy stays French, matching all existing strings in `MainViewModel`/`MainActivity`.
- No Cupra/We Connect cloud integration in this plan — out of scope per the spec's Non-goals (separate design).
- Follow existing conventions: `@SuppressLint("MissingPermission")` at the call site of any Bluetooth API (as `BluetoothObdManager`/`MainViewModel` already do), SharedPreferences-backed settings classes (`AbrpSettings`, `BatterySettings`) get no dedicated unit test (no Robolectric in this project) — same tier applies to `MonitoredDeviceStore`.

---

### Task 1: `MonitoredDeviceStore`

**Files:**
- Create: `app/src/main/java/com/borntemp/app/obd/MonitoredDeviceStore.kt`

**Interfaces:**
- Produces: `class MonitoredDeviceStore(context: Context)` with `var deviceAddress: String?` (SharedPreferences-backed, `null` until a device has been saved).

- [ ] **Step 1: Write the file**

```kotlin
package com.borntemp.app.obd

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the MAC address of the OBDLink CX that auto-connect should watch
 * for. Overwritten every time the user manually connects (see
 * `ObdSessionController.connect`), so pairing a replacement adapter and
 * connecting to it once retargets auto-connect at the new device.
 */
class MonitoredDeviceStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("monitored_device", Context.MODE_PRIVATE)

    var deviceAddress: String?
        get() = prefs.getString("device_address", null)
        set(v) { prefs.edit().putString("device_address", v).apply() }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

No dedicated unit test for this task — it's a trivial `SharedPreferences` wrapper, the same tier as the existing untested `AbrpSettings`/`BatterySettings`, and this project has no Robolectric/instrumentation harness to fake a `Context` in a JVM test. It's exercised indirectly by Task 2's smoke test and by Task 6's manual in-vehicle verification.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/borntemp/app/obd/MonitoredDeviceStore.kt
git commit -m "feat(obd): add MonitoredDeviceStore to persist the auto-connect target"
```

---

### Task 2: Extract `ObdSessionController` out of `MainViewModel`

**Files:**
- Create: `app/src/main/java/com/borntemp/app/viewmodel/ObdSessionController.kt`
- Modify: `app/src/main/java/com/borntemp/app/viewmodel/MainViewModel.kt` (replaced with a thin delegate — full replacement content given below)

**Interfaces:**
- Consumes: `MonitoredDeviceStore` (Task 1).
- Produces: `class ObdSessionController(private val application: Application)` with `val uiState: StateFlow<UiState>`, `fun connect(device: BluetoothDevice)`, `fun disconnect()`, `fun refreshNow()`, `fun setPollingInterval(ms: Long)`, `fun setAbrpEnabled(enabled: Boolean)`, `fun setAbrpApiKey(key: String)`, `fun setAbrpUserToken(token: String)`, `fun setPackTypeOverride(override: PackTypeOverride)`, `fun getPairedDevices(context: Context): List<BluetoothDevice>`, `fun getBluetoothAdapter(context: Context): BluetoothAdapter?`. These are exactly `MainViewModel`'s current public signatures, unchanged, so every existing call site keeps compiling untouched.

This task is a **mechanical move**, not new behavior — every line of logic in `readAllData()`, `connect()`, `runDiagnosticProbe()`, `startPolling()`, etc. is unchanged. The only semantic change is: the connect/poll loop is no longer tied to `viewModelScope` (which dies when the Activity's ViewModel is cleared), and the automatic `disconnect()` that used to fire in `onCleared()` is **removed** — that was the thing that would have killed a background session the instant you closed the app.

- [ ] **Step 1: Create `ObdSessionController.kt` as a copy of `MainViewModel.kt` with these exact changes**

Copy the full current content of `app/src/main/java/com/borntemp/app/viewmodel/MainViewModel.kt` into the new file, then apply these edits to the new file only:

1. Imports — remove:
```kotlin
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
```
add:
```kotlin
import com.borntemp.app.obd.MonitoredDeviceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
```
(`kotlinx.coroutines.Job`, `.delay`, `.flow.*`, `.isActive`, `.launch` are already imported in the original file and stay as-is.) Also add:
```kotlin
import kotlinx.coroutines.Dispatchers
```

2. Class declaration — change:
```kotlin
class MainViewModel(application: Application) : AndroidViewModel(application) {
```
to:
```kotlin
class ObdSessionController(private val application: Application) {
```

3. Right after `private val _uiState = MutableStateFlow(UiState())`, add a replacement for `viewModelScope`:
```kotlin
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
```

4. Right after `private val batterySettings = BatterySettings(application)`, add:
```kotlin
    private val monitoredDeviceStore = MonitoredDeviceStore(application)
```

5. Replace all three occurrences of `viewModelScope.launch` with `scope.launch` (inside `connect()`, `startPolling()`, and `refreshNow()`).

6. In `connect()`, right after the failed-connect early return (`if (result.isFailure) { ...; return@launch }`), insert:
```kotlin
            monitoredDeviceStore.deviceAddress = device.address
```
so it reads:
```kotlin
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

            // Open the capture file now so init responses are recorded.
```

7. Delete the `override fun onCleared() { ... }` method entirely (its 4 lines, including the `disconnect()` call) — there is no Android ViewModel lifecycle to hook anymore, and this controller must outlive any single Activity.

- [ ] **Step 2: Replace `MainViewModel.kt` with a thin delegate**

```kotlin
package com.borntemp.app.viewmodel

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.borntemp.app.ObdSessionHolder
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin Activity-scoped adapter over the process-wide [ObdSessionController]
 * singleton (see [ObdSessionHolder]). The controller — not this ViewModel —
 * owns the BLE connection, poll loop and ABRP push, so a background
 * `ObdForegroundService` session survives this ViewModel being cleared when
 * the Activity finishes.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val controller: ObdSessionController = ObdSessionHolder.controllerFor(application)

    val uiState: StateFlow<UiState> = controller.uiState

    fun setPackTypeOverride(override: PackTypeOverride) = controller.setPackTypeOverride(override)
    fun setPollingInterval(ms: Long) = controller.setPollingInterval(ms)
    fun setAbrpApiKey(key: String) = controller.setAbrpApiKey(key)
    fun setAbrpUserToken(token: String) = controller.setAbrpUserToken(token)
    fun setAbrpEnabled(enabled: Boolean) = controller.setAbrpEnabled(enabled)
    fun getPairedDevices(context: Context): List<BluetoothDevice> = controller.getPairedDevices(context)
    fun getBluetoothAdapter(context: Context): BluetoothAdapter? = controller.getBluetoothAdapter(context)
    fun connect(device: BluetoothDevice) = controller.connect(device)
    fun disconnect() = controller.disconnect()
    fun refreshNow() = controller.refreshNow()
}
```

Note: this references `com.borntemp.app.ObdSessionHolder`, created in Task 3 — this task will not compile on its own until Task 3 lands. That's fine; Tasks 2 and 3 are committed together as a pair (see Task 3's steps) since they're two halves of one deliverable (a controller with nowhere to live isn't independently useful).

- [ ] **Step 3: Continue directly to Task 3** — do not attempt to compile/test/commit yet, since this task's code intentionally references `ObdSessionHolder`, which Task 3 creates.

---

### Task 3: `BornTempApplication` + `ObdSessionHolder` singleton

**Files:**
- Create: `app/src/main/java/com/borntemp/app/BornTempApplication.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `ObdSessionController` (Task 2).
- Produces: `object ObdSessionHolder { fun controllerFor(context: Context): ObdSessionController }`, `class BornTempApplication : Application()`. `Task 2`'s `MainViewModel` and `Task 6`'s `ObdForegroundService` both call `ObdSessionHolder.controllerFor(...)`.

- [ ] **Step 1: Write `BornTempApplication.kt`**

```kotlin
package com.borntemp.app

import android.app.Application
import android.content.Context
import com.borntemp.app.viewmodel.ObdSessionController

/**
 * Custom [Application] so the OBD session survives individual Activities —
 * [ObdSessionController] is created once here and shared by `MainViewModel`
 * and `com.borntemp.app.obd.ObdForegroundService`, so there is structurally
 * only ever one BLE connection to the OBDLink CX in this process.
 */
class BornTempApplication : Application() {
    val sessionController: ObdSessionController by lazy { ObdSessionController(this) }
}

object ObdSessionHolder {
    fun controllerFor(context: Context): ObdSessionController =
        (context.applicationContext as BornTempApplication).sessionController
}
```

- [ ] **Step 2: Register the Application class in the manifest**

In `app/src/main/AndroidManifest.xml`, change:
```xml
    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
```
to:
```xml
    <application
        android:name=".BornTempApplication"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL` (this is the point where Task 2's `MainViewModel.kt` finally compiles, since `ObdSessionHolder` now exists).

- [ ] **Step 4: Manual smoke test**

Run the app on a phone (or the connected test device), tap "CONNECTER" against the OBDLink CX exactly as before, and confirm the whole flow (init log lines, cockpit showing live temp/SOC, ABRP push if enabled) behaves identically to the pre-refactor build. This is the regression check for the Task 2 + 3 move — nothing about the user-visible behavior should have changed yet.

- [ ] **Step 5: Commit Tasks 2 and 3 together**

```bash
git add app/src/main/java/com/borntemp/app/viewmodel/ObdSessionController.kt \
        app/src/main/java/com/borntemp/app/viewmodel/MainViewModel.kt \
        app/src/main/java/com/borntemp/app/BornTempApplication.kt \
        app/src/main/AndroidManifest.xml
git commit -m "refactor(obd): extract ObdSessionController into an app-scoped singleton"
```

---

### Task 4: `ConnectRetryPolicy` (unit-tested)

**Files:**
- Create: `app/src/main/java/com/borntemp/app/obd/ConnectRetryPolicy.kt`
- Test: `app/src/test/java/com/borntemp/app/obd/ConnectRetryPolicyTest.kt`

**Interfaces:**
- Produces: `class ConnectRetryPolicy(maxAttempts: Int = 5, delayMs: Long = 3000L, delay: suspend (Long) -> Unit = ...)` with `sealed class State { data class Attempting(val attempt: Int, val maxAttempts: Int); object Succeeded; object GaveUp }` and `suspend fun run(onState: (State) -> Unit, connect: suspend () -> Boolean): Boolean`. Consumed by Task 6's `ObdForegroundService`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.borntemp.app.obd

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectRetryPolicyTest {

    @Test
    fun `succeeds on first attempt without retrying`() = runTest {
        var connectCalls = 0
        val states = mutableListOf<ConnectRetryPolicy.State>()
        val policy = ConnectRetryPolicy(maxAttempts = 5, delayMs = 3000L, delay = { })

        val result = policy.run(
            onState = { states.add(it) },
            connect = { connectCalls++; true }
        )

        assertTrue(result)
        assertEquals(1, connectCalls)
        assertEquals(
            listOf(ConnectRetryPolicy.State.Attempting(1, 5), ConnectRetryPolicy.State.Succeeded),
            states
        )
    }

    @Test
    fun `succeeds on third attempt after two failures`() = runTest {
        var connectCalls = 0
        var delayCalls = 0
        val policy = ConnectRetryPolicy(maxAttempts = 5, delayMs = 3000L, delay = { delayCalls++ })

        val result = policy.run(
            onState = { },
            connect = { connectCalls++; connectCalls == 3 }
        )

        assertTrue(result)
        assertEquals(3, connectCalls)
        assertEquals(2, delayCalls)
    }

    @Test
    fun `gives up after five failed attempts`() = runTest {
        var connectCalls = 0
        var delayCalls = 0
        val states = mutableListOf<ConnectRetryPolicy.State>()
        val policy = ConnectRetryPolicy(maxAttempts = 5, delayMs = 3000L, delay = { delayCalls++ })

        val result = policy.run(
            onState = { states.add(it) },
            connect = { connectCalls++; false }
        )

        assertFalse(result)
        assertEquals(5, connectCalls)
        assertEquals(4, delayCalls)
        assertEquals(ConnectRetryPolicy.State.GaveUp, states.last())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.borntemp.app.obd.ConnectRetryPolicyTest"`
Expected: FAIL to compile — `ConnectRetryPolicy` is unresolved.

- [ ] **Step 3: Write `ConnectRetryPolicy.kt`**

```kotlin
package com.borntemp.app.obd

import kotlinx.coroutines.delay

/**
 * Bounded retry for `ObdForegroundService`'s connect attempt: up to
 * [maxAttempts] tries, waiting [delayMs] between failures, surfaced as a
 * [State] the service renders into its notification. [delay] is injected
 * so tests can run the whole attempt sequence instantly.
 */
class ConnectRetryPolicy(
    private val maxAttempts: Int = 5,
    private val delayMs: Long = 3000L,
    private val delay: suspend (Long) -> Unit = { delay(it) }
) {
    sealed class State {
        data class Attempting(val attempt: Int, val maxAttempts: Int) : State()
        object Succeeded : State()
        object GaveUp : State()
    }

    /**
     * Runs [connect] up to [maxAttempts] times, invoking [onState] before
     * each attempt and once more with the final outcome. Returns true iff
     * [connect] eventually returned true.
     */
    suspend fun run(onState: (State) -> Unit, connect: suspend () -> Boolean): Boolean {
        for (attempt in 1..maxAttempts) {
            onState(State.Attempting(attempt, maxAttempts))
            if (connect()) {
                onState(State.Succeeded)
                return true
            }
            if (attempt < maxAttempts) delay(delayMs)
        }
        onState(State.GaveUp)
        return false
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.borntemp.app.obd.ConnectRetryPolicyTest"`
Expected: PASS, 3 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/borntemp/app/obd/ConnectRetryPolicy.kt \
        app/src/test/java/com/borntemp/app/obd/ConnectRetryPolicyTest.kt
git commit -m "feat(obd): add ConnectRetryPolicy with bounded-retry unit tests"
```

---

### Task 5: Manifest permissions + `MainActivity` permission requests

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/borntemp/app/MainActivity.kt:130-142`

**Interfaces:**
- Produces: `requestBluetoothPermissions()` now also requests `BLUETOOTH_SCAN` (API 31+) and `POST_NOTIFICATIONS` (API 33+), which Task 6's detection/notification code depends on being grantable.

- [ ] **Step 1: Add manifest permissions**

In `app/src/main/AndroidManifest.xml`, change:
```xml
    <!-- Android 12+ -->
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <!-- Location: needed for BT on Android < 12 AND for ABRP telemetry on all versions -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <!-- ABRP telemetry -->
    <uses-permission android:name="android.permission.INTERNET" />
```
to:
```xml
    <!-- Android 12+ -->
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <!-- Auto-connect background scan — filters on service UUID only, never derives location -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <!-- Location: needed for BT on Android < 12 AND for ABRP telemetry on all versions -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <!-- ABRP telemetry -->
    <uses-permission android:name="android.permission.INTERNET" />
    <!-- Auto-connect foreground service (whole-drive background session) -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

- [ ] **Step 2: Extend `requestBluetoothPermissions()`**

In `app/src/main/java/com/borntemp/app/MainActivity.kt`, change:
```kotlin
    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
```
to:
```kotlin
    private fun requestBluetoothPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Manual check**

Fresh-install the app (or clear its permissions in Settings → Apps → BornTemp → Permissions) and launch it; confirm the permission prompt now also covers Nearby devices (scan) and Notifications, and that accepting all of them still lets a manual "CONNECTER" work as before.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/borntemp/app/MainActivity.kt
git commit -m "feat(android): request BLUETOOTH_SCAN/POST_NOTIFICATIONS for auto-connect"
```

---

### Task 6: `ObdBeaconReceiver` + `ObdForegroundService`

**Files:**
- Create: `app/src/main/java/com/borntemp/app/obd/ObdBeaconReceiver.kt`
- Create: `app/src/main/java/com/borntemp/app/obd/ObdForegroundService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/borntemp/app/viewmodel/ObdSessionController.kt` (arm detection after a successful manual connect)
- Modify: `app/src/main/java/com/borntemp/app/MainActivity.kt` (arm detection on launch)

**Interfaces:**
- Consumes: `MonitoredDeviceStore` (Task 1), `ObdSessionController`/`ObdSessionHolder` (Tasks 2–3), `ConnectRetryPolicy` (Task 4).
- Produces: `ObdBeaconReceiver.armDetection(context: Context)` (called from `MainActivity`, `ObdSessionController`, and the receiver's own boot/Bluetooth-toggle handling), `ObdForegroundService.EXTRA_DEVICE_ADDRESS` (the Intent extra key the receiver uses to start the service).

These two classes are mutually referential (the service re-arms the receiver's scan when it stops; the receiver starts the service when a scan matches), so they're written and committed as one deliverable — neither compiles meaningfully alone.

- [ ] **Step 1: Write `ObdBeaconReceiver.kt`**

```kotlin
package com.borntemp.app.obd

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * Watches for the monitored OBDLink CX's BLE advertisement (filtered on its
 * custom UART service UUID) so [ObdForegroundService] can start the moment
 * the car powers the adapter on — even if BornTemp isn't running. Also
 * re-arms the scan after a reboot or a Bluetooth toggle, since Android does
 * not persist registered scans across either.
 */
class ObdBeaconReceiver : BroadcastReceiver() {

    companion object {
        private val SERVICE_UUID: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        private const val ACTION_OBD_FOUND = "com.borntemp.app.ACTION_OBD_FOUND"

        private fun scanPendingIntent(context: Context): PendingIntent {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            return PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, ObdBeaconReceiver::class.java).setAction(ACTION_OBD_FOUND),
                flags
            )
        }

        private fun hasScanPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
            } else true
        }

        /**
         * Re-registers the background scan for the saved monitored device.
         * No-ops quietly if there's no monitored device yet, the scan
         * permission isn't granted, or Bluetooth is off — in all three
         * cases the caller (MainActivity) surfaces the reason in the UI.
         */
        @SuppressLint("MissingPermission")
        fun armDetection(context: Context) {
            if (MonitoredDeviceStore(context).deviceAddress == null) return
            if (!hasScanPermission(context)) return
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: return
            if (!adapter.isEnabled) return
            val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner ?: return

            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
            scanner.startScan(listOf(filter), settings, scanPendingIntent(context))
        }

        @SuppressLint("MissingPermission")
        private fun disarmDetection(context: Context) {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanPendingIntent(context))
        }
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            BluetoothAdapter.ACTION_STATE_CHANGED -> armDetection(context)
            ACTION_OBD_FOUND -> handleScanResults(context, intent)
        }
    }

    @SuppressLint("MissingPermission", "DEPRECATION")
    private fun handleScanResults(context: Context, intent: Intent) {
        val results: List<ScanResult> =
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT) ?: return
        val monitoredAddress = MonitoredDeviceStore(context).deviceAddress ?: return
        val match = results.firstOrNull { it.device.address == monitoredAddress } ?: return
        if (match.device.bondState != BluetoothDevice.BOND_BONDED) return

        disarmDetection(context)
        val serviceIntent = Intent(context, ObdForegroundService::class.java)
            .putExtra(ObdForegroundService.EXTRA_DEVICE_ADDRESS, match.device.address)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
```

- [ ] **Step 2: Write `ObdForegroundService.kt`**

```kotlin
package com.borntemp.app.obd

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.borntemp.app.MainActivity
import com.borntemp.app.ObdSessionHolder
import com.borntemp.app.viewmodel.ConnectionState
import com.borntemp.app.viewmodel.ObdSessionController
import com.borntemp.app.viewmodel.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground counterpart to a manual "CONNECTER" tap: started by
 * [ObdBeaconReceiver] when the OBDLink CX is detected, it drives the same
 * [ObdSessionController] singleton the UI uses, so there is only ever one
 * BLE connection to the adapter in this process. Stops itself (and
 * re-arms detection) once the controller reports disconnected, or once
 * [ConnectRetryPolicy] gives up after 5 attempts.
 */
class ObdForegroundService : Service() {

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        private const val CHANNEL_ID = "obd_session"
        private const val FAILURE_CHANNEL_ID = "obd_session_failure"
        private const val NOTIFICATION_ID = 1
        private const val FAILURE_NOTIFICATION_ID = 2
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionJob: Job? = null
    private val retryPolicy = ConnectRetryPolicy()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        if (address == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundWithNotification(buildProgressNotification("Connexion..."))
        sessionJob?.cancel()
        sessionJob = serviceScope.launch { runSession(address) }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun runSession(address: String) {
        val controller = ObdSessionHolder.controllerFor(this)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = bluetoothManager.adapter?.bondedDevices?.firstOrNull { it.address == address }
        if (device == null) {
            finishAndReArm()
            return
        }

        val connected = retryPolicy.run(
            onState = { state ->
                when (state) {
                    is ConnectRetryPolicy.State.Attempting ->
                        updateNotification(buildProgressNotification(
                            "Connexion... (tentative ${state.attempt}/${state.maxAttempts})"
                        ))
                    ConnectRetryPolicy.State.Succeeded -> { /* live notification takes over below */ }
                    ConnectRetryPolicy.State.GaveUp -> postFailureNotification()
                }
            },
            connect = { attemptConnect(controller, device) }
        )

        if (connected) {
            updateNotification(buildLiveNotification(controller.uiState.value))
            val updaterJob = serviceScope.launch {
                controller.uiState.collect { state ->
                    if (state.connectionState == ConnectionState.CONNECTED) {
                        updateNotification(buildLiveNotification(state))
                    }
                }
            }
            controller.uiState.first { it.connectionState != ConnectionState.CONNECTED }
            updaterJob.cancel()
        }
        finishAndReArm()
    }

    private suspend fun attemptConnect(controller: ObdSessionController, device: BluetoothDevice): Boolean {
        controller.connect(device)
        val settled = controller.uiState.first {
            it.connectionState == ConnectionState.CONNECTED || it.connectionState == ConnectionState.ERROR
        }
        return settled.connectionState == ConnectionState.CONNECTED
    }

    private fun finishAndReArm() {
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        ObdBeaconReceiver.armDetection(applicationContext)
        stopSelf()
    }

    private fun buildProgressNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BornTemp")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent())
            .build()

    private fun buildLiveNotification(state: UiState): Notification {
        val temp = state.batteryData.avgTemp
        val soc = state.batteryData.soc
        val text = buildString {
            append(if (temp != null) "%.1f°C".format(temp) else "-- °C")
            if (soc != null) append(" · %.0f%%".format(soc))
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BornTemp — connecté")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent())
            .build()
    }

    private fun postFailureNotification() {
        val notification = NotificationCompat.Builder(this, FAILURE_CHANNEL_ID)
            .setContentTitle("BornTemp")
            .setContentText("Échec de connexion à l'OBDLink CX — vérifiez l'adaptateur")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .build()
        NotificationManagerCompat.from(this).notify(FAILURE_NOTIFICATION_ID, notification)
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun updateNotification(notification: Notification) {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Session OBD", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    FAILURE_CHANNEL_ID, "Échecs de connexion OBD", NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionJob?.cancel()
    }
}
```

- [ ] **Step 3: Add the manifest `<service>` and `<receiver>` entries**

In `app/src/main/AndroidManifest.xml`, inside `<application>`, right after the `<activity>` block and before the `<provider>` block, add:
```xml
        <service
            android:name=".obd.ObdForegroundService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />

        <receiver
            android:name=".obd.ObdBeaconReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.bluetooth.adapter.action.STATE_CHANGED" />
                <action android:name="com.borntemp.app.ACTION_OBD_FOUND" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 4: Arm detection after a successful manual connect**

In `app/src/main/java/com/borntemp/app/viewmodel/ObdSessionController.kt`, add the import:
```kotlin
import com.borntemp.app.obd.ObdBeaconReceiver
```
and change the line added in Task 2 Step 1.6:
```kotlin
            monitoredDeviceStore.deviceAddress = device.address
```
to:
```kotlin
            monitoredDeviceStore.deviceAddress = device.address
            ObdBeaconReceiver.armDetection(application)
```
so auto-connect becomes active immediately after the very first manual connect, without waiting for a reboot or Bluetooth toggle.

- [ ] **Step 5: Arm detection on app launch**

In `app/src/main/java/com/borntemp/app/MainActivity.kt`, in `onCreate()`, change:
```kotlin
        requestBluetoothPermissions()
```
to:
```kotlin
        requestBluetoothPermissions()
        com.borntemp.app.obd.ObdBeaconReceiver.armDetection(this)
```

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Manual in-vehicle verification**

Requires the real OBDLink CX plugged into the Cupra Born — cannot be automated:
1. Manual-connect once via the UI (populates `MonitoredDeviceStore`), then force-stop the app.
2. Power off/on the OBDLink CX (ignition off/on or unplug/replug) → confirm a notification appears, the app auto-connects, and the notification's temp/SoC updates with the app closed.
3. Turn the car off → confirm auto-stop: notification clears, and `SessionCapture`'s `.log`/`.csv` files are finalized (pull via `adb pull` from `Android/data/com.borntemp.app/files/Download/` as in the earlier investigation, and check the `# Ended:` footer line is present).
4. Reboot the phone with the adapter already advertising → confirm `BOOT_COMPLETED` re-arms detection (may take a few seconds after boot for Bluetooth to come up; `ACTION_STATE_CHANGED` is the backstop).
5. Simulate a failed connect (e.g. briefly hold the adapter busy with another tool) → confirm 5 retries roughly 3s apart in the notification text, then the distinct failure notification, then a fresh advertisement later still triggers a new attempt.
6. Open the app while the background service is mid-session → confirm the cockpit shows live data immediately (no "disconnected" flash), and that nothing opens a second GATT connection (check the session `.log` file afterward for exactly one `INIT` sequence, not two interleaved ones).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/borntemp/app/obd/ObdBeaconReceiver.kt \
        app/src/main/java/com/borntemp/app/obd/ObdForegroundService.kt \
        app/src/main/AndroidManifest.xml \
        app/src/main/java/com/borntemp/app/viewmodel/ObdSessionController.kt \
        app/src/main/java/com/borntemp/app/MainActivity.kt
git commit -m "feat(obd): auto-connect via BLE detection + background foreground service"
```

---

### Task 7: Auto-connect status banner in the UI

**Files:**
- Modify: `app/src/main/java/com/borntemp/app/viewmodel/BatteryModels.kt` (new enum)
- Modify: `app/src/main/java/com/borntemp/app/MainActivity.kt` (compute status, pass to `MainScreen`)
- Modify: `app/src/main/java/com/borntemp/app/MainScreen.kt` (thread the new parameter to `CockpitScreen`)
- Modify: `app/src/main/java/com/borntemp/app/screens/CockpitScreen.kt` (render the banner)

**Interfaces:**
- Produces: `enum class AutoConnectStatus { ARMED, NO_DEVICE_SAVED, NO_PERMISSION, BLUETOOTH_OFF }`, computed in `MainActivity` and passed down as a plain parameter (not part of `UiState`, since it depends on Activity-level permission checks, not on the session controller).

- [ ] **Step 1: Add the enum**

In `app/src/main/java/com/borntemp/app/viewmodel/BatteryModels.kt`, right after the existing `enum class ConnectionState { ... }` block, add:
```kotlin
enum class AutoConnectStatus {
    ARMED,
    NO_DEVICE_SAVED,
    NO_PERMISSION,
    BLUETOOTH_OFF
}
```

- [ ] **Step 2: Compute the status in `MainActivity`**

In `app/src/main/java/com/borntemp/app/MainActivity.kt`, add a private helper:
```kotlin
    private fun autoConnectStatus(): com.borntemp.app.viewmodel.AutoConnectStatus {
        val hasScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        val monitoredAddress = com.borntemp.app.obd.MonitoredDeviceStore(this).deviceAddress
        val adapter = viewModel.getBluetoothAdapter(this)
        return when {
            monitoredAddress == null -> com.borntemp.app.viewmodel.AutoConnectStatus.NO_DEVICE_SAVED
            !hasScanPermission -> com.borntemp.app.viewmodel.AutoConnectStatus.NO_PERMISSION
            adapter?.isEnabled != true -> com.borntemp.app.viewmodel.AutoConnectStatus.BLUETOOTH_OFF
            else -> com.borntemp.app.viewmodel.AutoConnectStatus.ARMED
        }
    }
```
Then in `setContent { BornTempTheme { ... } }`, right after `val pairedDevices = viewModel.getPairedDevices(this)`, add:
```kotlin
                val autoConnectStatus = autoConnectStatus()
```
and pass it into the `MainScreen(...)` call by adding a new argument:
```kotlin
                MainScreen(
                    uiState = uiState,
                    pairedDevices = pairedDevices,
                    autoConnectStatus = autoConnectStatus,
```
(leave every other existing argument as-is).

- [ ] **Step 3: Thread the parameter through `MainScreen`**

In `app/src/main/java/com/borntemp/app/MainScreen.kt`, add `autoConnectStatus: com.borntemp.app.viewmodel.AutoConnectStatus` to the `MainScreen` function signature (right after `pairedDevices: List<BluetoothDevice>,`), and forward it to `CockpitScreen(...)`'s call by adding `autoConnectStatus = autoConnectStatus,` (right after `pairedDevices = pairedDevices,`, matching wherever that call currently is inside the `"home"` route branch).

- [ ] **Step 4: Render the banner in `CockpitScreen`**

In `app/src/main/java/com/borntemp/app/screens/CockpitScreen.kt`, add `autoConnectStatus: com.borntemp.app.viewmodel.AutoConnectStatus` to the function signature (right after `pairedDevices: List<BluetoothDevice>,`). Then, right after the existing:
```kotlin
    var showDeviceDialog by remember { mutableStateOf(false) }
    val connected = uiState.connectionState == ConnectionState.CONNECTED
    val connecting = uiState.connectionState == ConnectionState.CONNECTING ||
                     uiState.connectionState == ConnectionState.INITIALIZING
```
add a banner text computation:
```kotlin
    val autoConnectBanner = when (autoConnectStatus) {
        com.borntemp.app.viewmodel.AutoConnectStatus.ARMED -> null
        com.borntemp.app.viewmodel.AutoConnectStatus.NO_DEVICE_SAVED ->
            if (uiState.connectionState == ConnectionState.DISCONNECTED)
                "Connectez-vous une fois manuellement pour activer la connexion automatique."
            else null
        com.borntemp.app.viewmodel.AutoConnectStatus.NO_PERMISSION ->
            "Connexion automatique désactivée — permission Bluetooth manquante."
        com.borntemp.app.viewmodel.AutoConnectStatus.BLUETOOTH_OFF ->
            "Connexion automatique désactivée — Bluetooth éteint."
    }
```
Then, as the first item inside the existing `LazyColumn { ... }` block (right after its opening `{`), add:
```kotlin
            autoConnectBanner?.let { message ->
                item {
                    androidx.compose.material3.Surface(
                        color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    ) {
                        androidx.compose.material3.Text(
                            text = message,
                            modifier = androidx.compose.ui.Modifier.padding(12.dp),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
```

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Manual check**

Fresh-install (no monitored device yet) → confirm the "connect once manually" banner shows on the disconnected home screen and disappears once connected. Revoke the Nearby-devices permission in Android Settings after one manual connect → confirm the "permission manquante" banner appears instead. Turn Bluetooth off → confirm the "Bluetooth éteint" banner appears.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/borntemp/app/viewmodel/BatteryModels.kt \
        app/src/main/java/com/borntemp/app/MainActivity.kt \
        app/src/main/java/com/borntemp/app/MainScreen.kt \
        app/src/main/java/com/borntemp/app/screens/CockpitScreen.kt
git commit -m "feat(ui): surface auto-connect status banner on the cockpit"
```
