# OBD auto-connect design

## Context

Today, connecting to the OBDLink CX requires opening BornTemp, picking the
paired device, and tapping "CONNECTER" every single drive — and the
connection only lives as long as `MainActivity` does, inside
`MainViewModel`'s `viewModelScope`. That was fine when BornTemp was the only
consumer of the adapter, but it doesn't hold up now that ABRP is being
reconfigured to get its live SoC/power data *from* BornTemp instead of
opening its own competing Bluetooth connection to the OBDLink CX (see the
2026-07-06 session investigation: ABRP's own Bluetooth OBD source was
colliding with BornTemp's, causing the ELM327 to repeatedly reset mid-drive).

For that hand-off to actually work, BornTemp needs to run unattended for the
whole drive — connect the moment the car is detected, keep polling and
pushing to ABRP with the screen off and the app closed, and clean up when the
drive ends — without ever risking a second, independent connection to the
adapter existing at the same time (the exact bug class that started this
whole investigation).

## Goals

- Auto-connect to the OBDLink CX when the car is detected, with no user
  interaction.
- Keep polling + pushing to ABRP for the entire drive, regardless of screen
  state or whether the app is in the foreground.
- Auto-disconnect/clean up when the drive ends.
- Structurally rule out a second concurrent connection to the adapter (from
  BornTemp itself — the ABRP-side conflict is being solved separately by
  reconfiguring ABRP's data source).
- Surface connect failures instead of failing silently.

## Non-goals

- Retrieving data from the Cupra/We Connect cloud API while away from the
  car (e.g. while charging). Raised during this brainstorm but scoped out —
  it's a separate subsystem (cloud OAuth polling vs. local BLE) and gets its
  own design.
- Any change to the PID parsing, thermal/SOH analytics, or the ABRP payload
  format — this design only changes *when/how* the existing session logic
  starts and runs.

## Forward compatibility: shared foundation for future OBD-triggered features

A separate, not-yet-brainstormed feature is planned (see `PLAN_1_borntemp_charge_logging.md`,
2026-07-16): logging charge-session telemetry (SOC/temp/power samples,
Room-backed, exported as CSV for an external forecasting pipeline). That
plan's current draft calls for its own foreground Service and car-detection
to know when a charge session starts — but that's the same job
`ObdForegroundService`/`ObdBeaconReceiver` already do here. The OBDLink CX
is powered off the OBD2 port whenever the car's low-voltage systems are
awake, which is true both while driving and while charging (the BMS has to
be awake to manage the charge), so our existing BLE-advertisement trigger
already fires for both cases without any change.

**Constraint for that future work:** charge-session logging must be built as
another consumer of the existing `ObdSessionController`'s poll loop/`uiState`
— the same way the ABRP push already just piggybacks on `readAllData()` —
not as a second independent Service. Two services both willing to hold the
BLE connection is exactly the bug class this whole design exists to rule
out; it doesn't stop mattering just because the second service would be
ours instead of ABRP's.

One more thing worth flagging for whoever writes that plan: its draft
proposes detecting charge start/end via a SOC-rising heuristic. That's
unnecessary — `readAllData()` already reads vehicle mode straight from UDS
PID `227448` (`ObdPids.parseVehicleMode` → `ChargeState.AC_CHARGING` /
`DC_CHARGING` / `NOT_CHARGING`), which is an authoritative signal, not an
inference. This design doesn't change that parsing (see Non-goals) — it's
just worth not reinventing next to it.

## Detection: what counts as "the car is here"

The OBDLink CX only advertises over BLE when it has power from the OBD2
port, so its BLE advertisement is used as the proxy for "car is on and in
range." No new hardware signal, no geofencing, no reliance on the car's own
Bluetooth (audio/hands-free) profile.

## Components

### New

- **`ObdSessionController`** (plain Kotlin class, not Android-lifecycle-
  bound) — the connect/init/probe/poll/ABRP-push logic that currently lives
  inline in `MainViewModel` (`connect()`, `startPolling()`, `readAllData()`,
  the ABRP-push tail end) moves here nearly verbatim. Owns the single
  `BluetoothObdManager` instance, `SessionCapture`, and the
  `AbrpTelemetryClient` call. Contains the bounded connect-retry state
  machine (see below).
- **`ObdSessionRepository`** (process-wide singleton wrapping a
  `MutableStateFlow<ObdUiState>`) — the single source of truth for
  connection state and latest `BatteryData`. `ObdSessionController` writes
  to it; `MainViewModel` and the foreground service's notification both read
  from it.
- **`ObdForegroundService`** (`Service`, foreground type
  `connectedDevice`) — starts/stops an `ObdSessionController` and renders the
  persistent notification from `ObdSessionRepository`. Started by the
  detection receiver with the target device; stops itself once the
  controller reports disconnected.
- **`MonitoredDeviceStore`** (SharedPreferences, same pattern as the
  existing `AbrpSettings`/`BatterySettings`) — persists the target OBDLink
  CX's MAC address. Overwritten every time the user manually connects via
  the existing device picker (so pairing a replacement adapter and
  connecting to it once retargets auto-connect to the new device); read by
  the detection receiver.
- **`ObdBeaconReceiver`** (manifest `BroadcastReceiver`) — receives the BLE
  scan match, verifies the device is bonded and matches
  `MonitoredDeviceStore`, and starts `ObdForegroundService`. Also handles
  `BOOT_COMPLETED` / `ACTION_STATE_CHANGED` to re-arm the background scan.

### Changed

- **`MainViewModel`** shrinks: `connect()`/`disconnect()`/`refreshNow()`
  become calls into `ObdSessionController`; `uiState` becomes derived from
  `ObdSessionRepository` instead of the ViewModel owning its own copy.
- **`MainActivity`** mostly unchanged — when opened while
  `ObdForegroundService` is already running, it observes the same
  repository and shows live data. There is only ever one state source, so
  there's nothing special to reconcile.

## Detection → session → auto-stop flow

**Arming** (once per boot / per Bluetooth-on event):
1. `MainActivity.onCreate()` and `ObdBeaconReceiver` (on `BOOT_COMPLETED` /
   `ACTION_STATE_CHANGED`) both call a shared `armDetection(context)`.
2. If `MonitoredDeviceStore` has a saved MAC and `BLUETOOTH_SCAN` is
   granted, it calls `BluetoothLeScanner.startScan(listOf(ScanFilter(
   serviceUuid = FFF0)), ScanSettings(LOW_POWER), pendingIntent)`. This
   registers with the OS and returns immediately — no foreground service, no
   battery cost, until a match appears.

**Trigger** (car powers the adapter on):
3. The OS delivers the scan-match broadcast to `ObdBeaconReceiver`, even if
   the app process is dead.
4. The receiver checks the found device is bonded and matches
   `MonitoredDeviceStore`. If so, it starts `ObdForegroundService` with that
   device and stops the scan for the duration of the session.

**Session** (mirrors today's manual flow, headless):
5. `ObdForegroundService.onStartCommand()` posts the initial notification and
   launches the connect-retry loop (below) in a service-scoped coroutine.
6. On success: same steps as today — `obdManager.connect()` →
   `SessionCapture.start()` → `initializeElm()` → UDS `1003` session →
   one-shot probe → `startPolling()`.
7. The poll loop is unchanged (`readAllData()` each tick, ABRP push at the
   tail if enabled) — it now lives in the service's coroutine scope instead
   of `viewModelScope`.
8. The notification updates each tick with a compact summary (temp/SoC) from
   `ObdSessionRepository`.

**Connect retry / failure notification:**
9. If `connect()` fails, wait ~3s and retry, up to **5 attempts total**. The
   ongoing notification reflects progress ("Connexion... tentative 2/5").
10. If all 5 attempts fail, post a distinct heads-up notification ("Échec de
    connexion à l'OBDLink CX — vérifiez l'adaptateur"), separate from the
    quiet session notification.
11. Either way (gave up, or a later successful session ends), the service
    stops and re-arms the background scan (step 2), so the next advertisement
    starts a fresh cycle.

**Auto-stop** (car turns off / goes out of range):
12. `BluetoothObdManager`'s existing `onConnectionStateChange →
    STATE_DISCONNECTED` path fires as it does today.
13. `ObdSessionController` reports disconnected; `ObdForegroundService`
    closes `SessionCapture`, removes the notification, calls `stopSelf()`,
    and re-arms the background scan.

## Permissions

- `BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"` —
  filtering is on the adapter's service UUID (`FFF0`), not derived location,
  so `ACCESS_FINE_LOCATION` stays scoped to its current ABRP-GPS purpose only
  and is not a prerequisite for scanning.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- `POST_NOTIFICATIONS` (Android 13+), requested via the existing
  `MainActivity` permission-request pattern.

## Edge cases

- **Permission not granted/revoked**: `armDetection()` no-ops.
  `MainActivity` shows a persistent banner ("Connexion automatique
  désactivée — permission Bluetooth manquante") when a monitored device is
  saved but the scan couldn't be armed.
- **Bluetooth off**: can't arm; `ACTION_STATE_CHANGED` re-arms automatically
  when it's switched back on.
- **No monitored device yet** (fresh install): `armDetection()` no-ops until
  one manual connect populates `MonitoredDeviceStore`. The UI notes this the
  first time ("Connectez-vous une fois manuellement pour activer la
  connexion automatique").
- **App force-stopped by the user**: Android withholds all broadcasts,
  including our scan match, until the app is manually reopened. This is an
  OS-level restriction with no workaround — documented so it isn't mistaken
  for a bug later.
- **Poll-loop / ABRP-push error handling** is unchanged from today's
  fail-open behavior in `SessionCapture` / `AbrpTelemetryClient`.

## Testing & verification

Follows the existing split in the repo (plain JUnit for pure logic, nothing
Android-framework/BLE-dependent):

**Unit-testable (JVM):**
- The connect-retry state machine in `ObdSessionController` — a fake
  `connect()` that fails N times then succeeds, or always fails, asserting
  attempt count, notification-state transitions, and that it stops (rather
  than looping forever) after 5 attempts.
- `MonitoredDeviceStore` — trivial SharedPreferences wrapper, same tier as
  the existing untested `AbrpSettings`/`BatterySettings`; no dedicated test
  needed for consistency.

**Manual, in-vehicle verification (no way to automate without the real
adapter + car):**
1. Manual-connect once, then force-stop or swipe-kill the app.
2. Power the OBDLink CX on → confirm notification appears, auto-connects,
   temps update with the app closed.
3. Turn the car off → confirm auto-stop: notification clears, capture file
   finalized.
4. Reboot the phone with the adapter already advertising → confirm
   `BOOT_COMPLETED` (backed by `ACTION_STATE_CHANGED`) re-arm catches it.
5. Force a failed connect → confirm 5 retries at ~3s, then the failure
   notification, then the scan re-arms.
6. Open the app mid-session → confirm the UI shows live state immediately,
   and that nothing opens a second GATT connection.
