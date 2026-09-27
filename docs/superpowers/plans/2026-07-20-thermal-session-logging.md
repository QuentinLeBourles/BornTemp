# Thermal Session Logging + Warmup Prediction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **This plan spans two separate repositories.** Part A's tasks (1-2) apply to
> the BornTemp repo at `/Users/quentinlebourles/Documents/Projects/BornTemp`
> (Kotlin/Android). Part B's tasks (3-4) apply to the **Born_to_rag** repo at
> `/Users/quentinlebourles/Documents/Projects/Born_to_rag` (Python) — a
> completely different codebase, language, and git history. Do not assume a
> shared working directory between Parts A and B. The only thing connecting
> them is the CSV file contract (already defined and validated by
> `Born_to_rag/src/born_to_rag/ingest.py` — unchanged by this plan).

**Goal:** BornTemp logs real driving and charging session data to
`sessions.csv`/`samples.csv` in the contract Born_to_rag already validates;
Born_to_rag gains a `predict_time_to_temp` function/CLI command that uses its
existing `HeatingModel` to estimate time-to-target-battery-temperature, now
meaningful once real driving data trains it.

**Architecture:** BornTemp: a new pure-Kotlin, JVM-testable `ChargeSessionLogger`
class becomes a third consumer of `ObdSessionController`'s per-tick data
(alongside `SessionCapture` and the ABRP push), appending session/sample rows
to two persistent CSVs that accumulate across the app's lifetime. Born_to_rag:
no pipeline changes — `features.py`/`train.py` already treat "charging" and
"driving" samples identically (just numeric SOC/temp/power per session), so
the only new code is a warmup-time prediction function built on the existing
`HeatingModel`, plus a CLI command exposing it.

**Tech Stack:** Kotlin/JVM (BornTemp side, plain JUnit, no Robolectric),
Python/pytest (Born_to_rag side, existing `sklearn`/`pandas`/`typer` stack).

## Global Constraints

- **Timestamps in the BornTemp↔Born_to_rag contract are epoch milliseconds**,
  matching `sessions.csv`/`samples.csv`'s existing convention — this is a
  *different* unit than the ABRP contract (epoch seconds). Do not conflate
  the two conventions.
- **Power/current use BornTemp's native sign convention (+ = charge/regen)**
  in this export — the *opposite* of the already-shipped ABRP payload's
  negated convention (`fix(abrp): correct power/current sign convention`,
  commit `704175a`). Do not reuse that negation logic here.
- `ambient_temp_c` is always left empty/null in this export — do not
  backfill it from `ChargeEstimatorScreen`'s coolant-temperature UI proxy.
- This plan is purely additive on the BornTemp side: `SessionCapture`
  (`.log`/`_soh.csv`) and the ABRP push are untouched.
- No BornTemp UI changes in this plan.
- Born_to_rag's `predict_time_to_temp` returns a single (median) estimate —
  no p10/p90 interval this pass.
- Follow existing conventions: BornTemp — SharedPreferences-free pure
  Kotlin classes get plain JUnit tests, same tier as `ConnectRetryPolicy`.
  Born_to_rag — stub regressors (`_ConstantRegressor`) and `pytest.raises`
  for domain-rejection tests, same tier as `test_simulate.py`'s existing
  `predict_duration` tests.

---

# Part A — BornTemp (`/Users/quentinlebourles/Documents/Projects/BornTemp`)

### Task 1: `ChargeSessionLogger` (pure, JVM-testable core)

**Files:**
- Create: `app/src/main/java/com/borntemp/app/obd/ChargeSessionLogger.kt`
- Test: `app/src/test/java/com/borntemp/app/obd/ChargeSessionLoggerTest.kt`

**Interfaces:**
- Consumes: `ChargeState` enum (`app/src/main/java/com/borntemp/app/viewmodel/BatteryModels.kt` — values `UNKNOWN`, `NOT_CHARGING`, `AC_CHARGING`, `DC_CHARGING`; already exists, do not modify).
- Produces: `class ChargeSessionLogger(private val outputDir: File?)` with `fun recordSample(timestampMs: Long, socPercent: Float?, batteryTempC: Float?, chargePowerKw: Float?, chargeCurrentA: Float?, chargeVoltageV: Float?, chargeState: ChargeState)` and `fun endConnection()`. Consumed by Task 2's `ObdSessionController`.

Takes a plain `File?` (not an Android `Context`) specifically so it's testable
with a JUnit `@TempDir` — no Robolectric needed. `null` means "no writable
directory" (mirrors `SessionCapture.start()` returning `null` when external
storage is unavailable) and makes every method a harmless no-op.

**Behavior spec** (this is the full contract — implement exactly this):

1. `recordSample` is a no-op if `chargeState == ChargeState.UNKNOWN`, or if
   `socPercent == null`, or if `batteryTempC == null`, or if `outputDir ==
   null`. (A tick with an unresolved vehicle-mode PID or a missing SOC/temp
   reading tells us nothing definitive — skip it entirely rather than guess.)
2. Map `chargeState` to a `chargerType` string: `AC_CHARGING → "AC"`,
   `DC_CHARGING → "DC"`, `NOT_CHARGING → "UNKNOWN"`.
3. If no segment is currently open, open one (see step 5) using this sample
   as the start.
4. If a segment is open and `chargerType` differs from the open segment's
   charger type, close the open segment (see step 6), then open a new one
   (see step 5) using this sample as the new segment's start.
5. **Opening a segment**: if this is the first segment since construction or
   since the last `endConnection()` call, generate `connectionStamp` from
   this sample's `timestampMs` using the pattern `yyyyMMdd-HHmmss` (same
   `SimpleDateFormat` pattern `SessionCapture` already uses) and reset the
   segment counter to 0; otherwise increment the segment counter. The new
   session ID is `"$connectionStamp-$segmentCounter"`. Record this sample's
   `socPercent` as the segment's `soc_start`, `batteryTempC` as
   `battery_temp_start_c`, and `timestampMs` as `started_at`.
6. **Closing a segment**: append one row to `sessions.csv` — `session_id`,
   `started_at`, `ended_at` (the *last recorded sample's* `timestampMs` in
   this segment, not the closing event's timestamp), `soc_start`, `soc_end`
   (the last recorded sample's `socPercent`), `charger_type`,
   `battery_temp_start_c`, `end_reason`. `end_reason` is `"charge_complete"`
   if the closing segment's charger type was `"AC"` or `"DC"` **and** the
   reason for closing is a charger-type change to `"UNKNOWN"`; it is
   `"presence_loss"` if closing is triggered by `endConnection()`; otherwise
   (any other charger-type transition, e.g. `"UNKNOWN"→"AC"`, `"AC"→"DC"`)
   it is `"unknown"`.
7. Every call that isn't a no-op (step 1) and isn't only a segment-close
   (i.e. every sample that has an open segment after steps 3-4) appends one
   row to `samples.csv`: `session_id` (the currently open segment's),
   `timestamp` (`timestampMs`), `soc_percent`, `battery_temp_c`,
   `charge_power_kw` (`chargePowerKw`, may be empty), `charge_current_a`
   (`chargeCurrentA`, may be empty), `charge_voltage_v` (`chargeVoltageV`,
   may be empty), `ambient_temp_c` (**always empty**). Also update the open
   segment's "last recorded sample" (`timestampMs`, `socPercent`) used by
   step 6.
8. `endConnection()`: if a segment is open, close it (step 6) with
   `end_reason = "presence_loss"`. Then reset `connectionStamp` to `null` and
   the segment counter to 0, so the next `recordSample` call starts a fresh
   connection stamp. No-op if no segment is open or `outputDir == null`.
9. **File writing**: `sessions.csv` and `samples.csv` live directly in
   `outputDir`. Each file's header is written once, only if the file does
   not already exist when the logger is constructed (check `File.exists()`
   before opening); both files are then opened once, in **append** mode, and
   kept open for the lifetime of the `ChargeSessionLogger` instance. Numeric
   fields that are `null` are written as an empty CSV cell (matching
   `SessionCapture.sohSample`'s existing `f(x)` helper pattern — reuse that
   exact idea, a local `fun f(x: Float?) = x?.toString() ?: ""`). Any `IOException`
   while writing is caught and swallowed (fail-open — logging must never
   crash or interrupt polling).

**Column headers** (write exactly these, matching
`Born_to_rag/src/born_to_rag/ingest.py`'s `SESSIONS_REQUIRED_COLUMNS` /
`SAMPLES_ALL_COLUMNS`):
```
sessions.csv: session_id,started_at,ended_at,soc_start,soc_end,charger_type,battery_temp_start_c,end_reason
samples.csv:  session_id,timestamp,soc_percent,battery_temp_c,charge_power_kw,charge_current_a,charge_voltage_v,ambient_temp_c
```

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.borntemp.app.obd

import com.borntemp.app.viewmodel.ChargeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChargeSessionLoggerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sessionsCsv(dir: File) = File(dir, "sessions.csv").readLines()
    private fun samplesCsv(dir: File) = File(dir, "samples.csv").readLines()

    @Test
    fun `unknown charge state is a no-op`() {
        val dir = tempFolder.newFolder()
        val logger = ChargeSessionLogger(dir)

        logger.recordSample(1_000L, 50f, 20f, 10f, 5f, 400f, ChargeState.UNKNOWN)

        assertEquals(1, sessionsCsv(dir).size) // header only
        assertEquals(1, samplesCsv(dir).size)  // header only
    }

    @Test
    fun `null soc or temp is a no-op`() {
        val dir = tempFolder.newFolder()
        val logger = ChargeSessionLogger(dir)

        logger.recordSample(1_000L, null, 20f, 10f, 5f, 400f, ChargeState.NOT_CHARGING)
        logger.recordSample(2_000L, 50f, null, 10f, 5f, 400f, ChargeState.NOT_CHARGING)

        assertEquals(1, samplesCsv(dir).size)
    }

    @Test
    fun `first sample opens a segment and writes one samples row`() {
        val dir = tempFolder.newFolder()
        val logger = ChargeSessionLogger(dir)

        logger.recordSample(1_000L, 50f, 20f, 10f, 5f, 400f, ChargeState.NOT_CHARGING)

        val samples = samplesCsv(dir)
        assertEquals(2, samples.size) // header + 1 row
        assertTrue(samples[1].startsWith("19700101-000001-0,1000,50.0,20.0,10.0,5.0,400.0,"))
    }

    @Test
    fun `charger type transition closes old segment and opens a new one`() {
        val dir = tempFolder.newFolder()
        val logger = ChargeSessionLogger(dir)

        logger.recordSample(1_000L, 50f, 20f, null, null, null, ChargeState.NOT_CHARGING)
        logger.recordSample(2_000L, 55f, 21f, 80f, 200f, 400f, ChargeState.DC_CHARGING)

        val sessions = sessionsCsv(dir)
        assertEquals(2, sessions.size) // header + 1 closed segment
        assertTrue(sessions[1].contains(",UNKNOWN,20.0,unknown"))

        val samples = samplesCsv(dir)
        assertEquals(3, samples.size) // header + 2 rows across 2 segments
        assertTrue(samples[1].contains("19700101-000001-0"))
        assertTrue(samples[2].contains("19700101-000001-1"))
    }

    @Test
    fun `charging segment ending back to not-charging is charge_complete`() {
        val dir = tempFolder.newFolder()
        val logger = ChargeSessionLogger(dir)

        logger.recordSample(1_000L, 50f, 20f, 80f, 200f, 400f, ChargeState.DC_CHARGING)
        logger.recordSample(2_000L, 60f, 22f, null, null, null, ChargeState.NOT_CHARGING)

        val sessions = sessionsCsv(dir)
        assertTrue(sessions[1].contains(",DC,20.0,charge_complete"))
    }

    @Test
    fun `endConnection closes the open segment as presence_loss`() {
        val dir = tempFolder.newFolder()
        val logger = ChargeSessionLogger(dir)

        logger.recordSample(1_000L, 50f, 20f, null, null, null, ChargeState.NOT_CHARGING)
        logger.endConnection()

        val sessions = sessionsCsv(dir)
        assertEquals(2, sessions.size)
        assertTrue(sessions[1].contains(",presence_loss"))
    }

    @Test
    fun `endConnection with no open segment is a no-op`() {
        val dir = tempFolder.newFolder()
        val logger = ChargeSessionLogger(dir)

        logger.endConnection()

        assertEquals(1, sessionsCsv(dir).size)
    }

    @Test
    fun `session id restarts its segment counter after endConnection`() {
        val dir = tempFolder.newFolder()
        val logger = ChargeSessionLogger(dir)

        logger.recordSample(1_000L, 50f, 20f, null, null, null, ChargeState.NOT_CHARGING)
        logger.endConnection()
        logger.recordSample(3_000L, 51f, 20f, null, null, null, ChargeState.NOT_CHARGING)

        val samples = samplesCsv(dir)
        assertTrue(samples[1].contains("-0,"))
        assertTrue(samples[2].contains("-0,")) // fresh connection, counter reset
    }

    @Test
    fun `header is written once, not duplicated across instances pointed at the same dir`() {
        val dir = tempFolder.newFolder()
        ChargeSessionLogger(dir).recordSample(1_000L, 50f, 20f, null, null, null, ChargeState.NOT_CHARGING)

        // Simulate an app restart: a fresh logger instance, same directory.
        val logger2 = ChargeSessionLogger(dir)
        logger2.recordSample(2_000L, 51f, 20f, null, null, null, ChargeState.NOT_CHARGING)

        val samples = samplesCsv(dir)
        assertEquals(1, samples.count { it.startsWith("session_id,") })
    }

    @Test
    fun `null outputDir makes every call a harmless no-op`() {
        val logger = ChargeSessionLogger(null)
        logger.recordSample(1_000L, 50f, 20f, null, null, null, ChargeState.NOT_CHARGING)
        logger.endConnection()
        // No assertion beyond "doesn't throw" — there's no directory to inspect.
    }
}
```

Note: the implementation in Step 3 pins `nameFormat` to UTC explicitly
(unlike `SessionCapture`'s human-facing log timestamps, which intentionally
use local wall-clock time) specifically so `session_id` generation is
deterministic regardless of the machine's default timezone — both in tests
and in the field, avoiding any DST-transition edge case where two segments
opened on the same real day could otherwise collide on local wall-clock
time. With UTC pinned, `Date(1000L)` (1 second past epoch) always formats to
`19700101-000001`, which is the literal used in the test assertions above.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.borntemp.app.obd.ChargeSessionLoggerTest"`
Expected: FAIL to compile — `ChargeSessionLogger` is unresolved.

- [ ] **Step 3: Write `ChargeSessionLogger.kt`**

```kotlin
package com.borntemp.app.obd

import com.borntemp.app.viewmodel.ChargeState
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Logs real driving and charging session data to `sessions.csv` /
 * `samples.csv`, in the exact contract `Born_to_rag`'s `ingest.py` already
 * validates. A third, independent consumer of `ObdSessionController`'s
 * per-tick data alongside `SessionCapture` and the ABRP push — additive,
 * doesn't touch either.
 *
 * Unlike `SessionCapture`, these two files accumulate across the app's
 * entire lifetime (append mode, header written once) rather than being
 * recreated per connection, so a single `adb pull` gathers every session
 * recorded so far.
 *
 * A "session" here is one charger-type segment: a new one starts whenever
 * the vehicle transitions between not-charging and AC/DC charging (or
 * between AC and DC directly), so a single continuous BLE connection that
 * goes from driving straight into charging produces two rows, not one.
 */
class ChargeSessionLogger(outputDir: File?) {

    // Pinned to UTC (unlike SessionCapture's human-facing log timestamps,
    // which intentionally use local wall-clock time) so session_id
    // generation is deterministic regardless of the device's timezone —
    // avoids a DST-transition edge case where two segments opened on the
    // same real day could otherwise collide on local wall-clock time.
    private val nameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val sessionsWriter: BufferedWriter? = outputDir?.let { openWriter(it, "sessions.csv", SESSIONS_HEADER) }
    private val samplesWriter: BufferedWriter? = outputDir?.let { openWriter(it, "samples.csv", SAMPLES_HEADER) }

    private var connectionStamp: String? = null
    private var segmentCounter = 0
    private var currentSessionId: String? = null
    private var currentChargerType: String? = null
    private var segmentSocStart: Float? = null
    private var segmentTempStartC: Float? = null
    private var segmentStartedAtMs: Long? = null
    private var lastSocPercent: Float? = null
    private var lastTimestampMs: Long? = null

    private fun openWriter(dir: File, filename: String, header: String): BufferedWriter? {
        return try {
            val target = File(dir, filename)
            val isNew = !target.exists()
            val w = BufferedWriter(FileWriter(target, /* append = */ true))
            if (isNew) {
                w.write(header)
                w.write("\n")
                w.flush()
            }
            w
        } catch (_: Exception) {
            null
        }
    }

    fun recordSample(
        timestampMs: Long,
        socPercent: Float?,
        batteryTempC: Float?,
        chargePowerKw: Float?,
        chargeCurrentA: Float?,
        chargeVoltageV: Float?,
        chargeState: ChargeState,
    ) {
        if (samplesWriter == null || sessionsWriter == null) return
        if (chargeState == ChargeState.UNKNOWN) return
        if (socPercent == null || batteryTempC == null) return

        val chargerType = when (chargeState) {
            ChargeState.AC_CHARGING -> "AC"
            ChargeState.DC_CHARGING -> "DC"
            ChargeState.NOT_CHARGING -> "UNKNOWN"
            ChargeState.UNKNOWN -> return // unreachable — guarded above
        }

        if (currentSessionId == null) {
            openSegment(timestampMs, chargerType, socPercent, batteryTempC)
        } else if (chargerType != currentChargerType) {
            closeSegment(endReasonFor(currentChargerType!!, chargerType))
            openSegment(timestampMs, chargerType, socPercent, batteryTempC)
        }

        writeSampleRow(timestampMs, socPercent, batteryTempC, chargePowerKw, chargeCurrentA, chargeVoltageV)
        lastSocPercent = socPercent
        lastTimestampMs = timestampMs
    }

    fun endConnection() {
        if (currentSessionId != null) closeSegment("presence_loss")
        connectionStamp = null
        segmentCounter = 0
    }

    private fun openSegment(timestampMs: Long, chargerType: String, socPercent: Float, batteryTempC: Float) {
        if (connectionStamp == null) {
            connectionStamp = nameFormat.format(Date(timestampMs))
            segmentCounter = 0
        } else {
            segmentCounter += 1
        }
        currentSessionId = "$connectionStamp-$segmentCounter"
        currentChargerType = chargerType
        segmentSocStart = socPercent
        segmentTempStartC = batteryTempC
        segmentStartedAtMs = timestampMs
        lastSocPercent = socPercent
        lastTimestampMs = timestampMs
    }

    private fun closeSegment(endReason: String) {
        val w = sessionsWriter ?: return
        val row = buildString {
            append(currentSessionId); append(',')
            append(segmentStartedAtMs); append(',')
            append(lastTimestampMs); append(',')
            append(f(segmentSocStart)); append(',')
            append(f(lastSocPercent)); append(',')
            append(currentChargerType); append(',')
            append(f(segmentTempStartC)); append(',')
            append(endReason); append('\n')
        }
        try {
            w.write(row)
            w.flush()
        } catch (_: Exception) { /* fail open */ }
        currentSessionId = null
        currentChargerType = null
    }

    private fun endReasonFor(oldChargerType: String, newChargerType: String): String =
        if ((oldChargerType == "AC" || oldChargerType == "DC") && newChargerType == "UNKNOWN")
            "charge_complete"
        else
            "unknown"

    private fun writeSampleRow(
        timestampMs: Long,
        socPercent: Float,
        batteryTempC: Float,
        chargePowerKw: Float?,
        chargeCurrentA: Float?,
        chargeVoltageV: Float?,
    ) {
        val w = samplesWriter ?: return
        val row = buildString {
            append(currentSessionId); append(',')
            append(timestampMs); append(',')
            append(socPercent); append(',')
            append(batteryTempC); append(',')
            append(f(chargePowerKw)); append(',')
            append(f(chargeCurrentA)); append(',')
            append(f(chargeVoltageV)); append(',')
            append(""); append('\n') // ambient_temp_c always empty — see design doc
        }
        try {
            w.write(row)
            w.flush()
        } catch (_: Exception) { /* fail open */ }
    }

    private fun f(x: Float?): String = x?.toString() ?: ""

    companion object {
        private const val SESSIONS_HEADER =
            "session_id,started_at,ended_at,soc_start,soc_end,charger_type,battery_temp_start_c,end_reason"
        private const val SAMPLES_HEADER =
            "session_id,timestamp,soc_percent,battery_temp_c,charge_power_kw,charge_current_a,charge_voltage_v,ambient_temp_c"
    }
}
```

- [ ] **Step 4: Fix the session-id timestamp literal, then run tests to verify they pass**

Before running, resolve the real formatted-timestamp placeholder from the
note in Step 1 (print `SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(1000L))`
once, e.g. in a scratch `@Test` or a REPL, and substitute the real value into
the test assertions).

Run: `./gradlew :app:testDebugUnitTest --tests "com.borntemp.app.obd.ChargeSessionLoggerTest"`
Expected: PASS, all cases green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/borntemp/app/obd/ChargeSessionLogger.kt \
        app/src/test/java/com/borntemp/app/obd/ChargeSessionLoggerTest.kt
git commit -m "feat(obd): add ChargeSessionLogger for Born_to_rag session export"
```

---

### Task 2: Wire `ChargeSessionLogger` into `ObdSessionController`

**Files:**
- Modify: `app/src/main/java/com/borntemp/app/viewmodel/ObdSessionController.kt`

**Interfaces:**
- Consumes: `ChargeSessionLogger` (Task 1).

- [ ] **Step 1: Add the import and field**

In `app/src/main/java/com/borntemp/app/viewmodel/ObdSessionController.kt`, add
the import alongside the existing `com.borntemp.app.obd.*` imports:
```kotlin
import com.borntemp.app.obd.ChargeSessionLogger
```
and add the field right after the existing `private val capture = SessionCapture(application)`
line:
```kotlin
    private val capture = SessionCapture(application)
    private val sessionLogger = ChargeSessionLogger(
        application.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
    )
```
(Same directory `SessionCapture` already writes into — `sessions.csv`/
`samples.csv` will sit alongside the existing `borntemp_*.log`/`_soh.csv`
files.)

- [ ] **Step 2: Call `recordSample` every tick**

In `readAllData()`, right after the existing `analytics.push(ChargeAnalytics.Sample(...))`
block (the one using `socHmi = socDisplay`, `tempAvg = finalAvg`, `powerKw =
effectivePower`, `current = effectiveCurrent`, ending around where
`avgPowerKw`/`socSlope`/`tempSlope` are computed next), insert:
```kotlin
        sessionLogger.recordSample(
            timestampMs = now,
            socPercent = socDisplay,
            batteryTempC = finalAvg,
            chargePowerKw = effectivePower,
            chargeCurrentA = effectiveCurrent,
            chargeVoltageV = voltage,
            chargeState = chargeState,
        )
```
(`now`, `socDisplay`, `finalAvg`, `effectivePower`, `effectiveCurrent`,
`voltage`, and `chargeState` are all already in scope at this point in the
function — this is a pure addition, no other lines change.)

- [ ] **Step 3: Call `endConnection` on disconnect**

In `disconnect()`, right after the existing `capture.close()` line, add:
```kotlin
        capture.close()
        sessionLogger.endConnection()
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Manual smoke test**

If a connected device is available: install (`./gradlew :app:installDebug`),
connect (manually or via auto-connect), let it run for a minute or two, then
disconnect. Pull the new files and confirm they look right:
```bash
adb pull /storage/emulated/0/Android/data/com.borntemp.app/files/Download/sessions.csv .
adb pull /storage/emulated/0/Android/data/com.borntemp.app/files/Download/samples.csv .
cat sessions.csv
cat samples.csv
```
Expected: `sessions.csv` has a header row plus one row per charger-type
segment; `samples.csv` has a header row plus one row per poll tick, with
`charge_power_kw`/`charge_current_a` in BornTemp's native sign (positive
while charging, negative while driving/discharging) — **not** the ABRP
payload's negated convention.

Two specific scenarios worth checking if you get the chance to drive/charge
during this verification (not required to complete this task — real
in-vehicle conditions aren't always available on demand):
- Drive, then plug in immediately after arriving (same connection) — confirm
  `sessions.csv` gets **two** rows from that one connection: a `charger_type
  = UNKNOWN` row ending in `end_reason = unknown`, followed by an
  `AC`/`DC` row.
- Let a charging session finish naturally (car stops charging while still
  connected) — confirm `end_reason = charge_complete` on that row. Then
  force-disconnect (walk away / power off the adapter) mid-session — confirm
  `end_reason = presence_loss` on whichever row was open at the time.

If no device is available, or these specific scenarios can't be exercised
right now, state that explicitly rather than fabricating the result — the
unit tests from Task 1 already cover this logic; this step is a real-world
sanity check, not the primary verification.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/borntemp/app/viewmodel/ObdSessionController.kt
git commit -m "feat(obd): log real sessions to sessions.csv/samples.csv for Born_to_rag"
```

---

# Part B — Born_to_rag (`/Users/quentinlebourles/Documents/Projects/Born_to_rag`)

> **Different repo, different language.** Everything below happens inside
> `/Users/quentinlebourles/Documents/Projects/Born_to_rag`, a Python project
> (uv/pyproject.toml, pytest). It has no dependency on BornTemp's Kotlin code
> and doesn't need Part A to be done first to be implemented — it only needs
> Part A's *output format* (already fixed by the existing, unchanged
> `ingest.py` contract) to eventually be useful with real data.

### Task 3: `predict_time_to_temp` (pure, unit-tested)

**Files:**
- Modify: `src/born_to_rag/simulate.py`
- Test: `tests/test_simulate.py`

**Interfaces:**
- Consumes: `ForecastModel`, `HeatingModel` (`src/born_to_rag/train.py` —
  already exist, do not modify). `HeatingModel.predict(X: pd.DataFrame) ->
  np.ndarray` expects columns `["battery_temp_mid", "charge_power",
  "ambient_temp_mid"]` (already established by `HEATING_FEATURE_COLUMNS` in
  `train.py`).
- Produces: `class WarmupResult` (`minutes: float`, `n_sessions: int`),
  `class WarmupUnreachableError(ValueError)`,
  `predict_time_to_temp(model: ForecastModel, current_temp: float, target_temp: float, power: float | None = None, ambient_temp: float | None = None) -> WarmupResult`.
  Consumed by Task 4's CLI command.

- [ ] **Step 1: Write the failing tests**

Add to `tests/test_simulate.py` (the existing `_ConstantRegressor` stub and
`predict_duration`/`ExtrapolationError` imports already exist in this file —
extend the import line and add these test cases and one new helper):

```python
from born_to_rag.simulate import (
    ExtrapolationError,
    WarmupUnreachableError,
    predict_duration,
    predict_time_to_temp,
)


def _make_warmup_model(slope_value: float, temp_min=-10.0, temp_max=40.0) -> ForecastModel:
    speed = SpeedModel(
        median_model=_ConstantRegressor(1.0),
        low_model=_ConstantRegressor(0.8),
        high_model=_ConstantRegressor(1.2),
        metadata={
            "soc_min": 0.0,
            "soc_max": 100.0,
            "battery_temp_min_c": temp_min,
            "battery_temp_max_c": temp_max,
        },
    )
    heating = HeatingModel(model=_ConstantRegressor(slope_value), metadata={"fallback": False})
    return ForecastModel(speed=speed, heating=heating, metadata={"n_sessions": 7})


def test_predict_time_to_temp_warming_succeeds():
    model = _make_warmup_model(0.5)
    result = predict_time_to_temp(model, current_temp=5.0, target_temp=15.0)
    assert result.minutes == pytest.approx(20.0, rel=0.05)
    assert result.n_sessions == 7


def test_predict_time_to_temp_cooling_succeeds():
    model = _make_warmup_model(-0.5)
    result = predict_time_to_temp(model, current_temp=30.0, target_temp=20.0)
    assert result.minutes == pytest.approx(20.0, rel=0.05)


def test_predict_time_to_temp_already_at_target_returns_zero():
    model = _make_warmup_model(0.5)
    result = predict_time_to_temp(model, current_temp=15.0, target_temp=15.0)
    assert result.minutes == 0.0


def test_predict_time_to_temp_rejects_current_temp_out_of_domain():
    model = _make_warmup_model(0.5, temp_min=-10.0, temp_max=40.0)
    with pytest.raises(ExtrapolationError):
        predict_time_to_temp(model, current_temp=-20.0, target_temp=15.0)


def test_predict_time_to_temp_rejects_target_temp_out_of_domain():
    model = _make_warmup_model(0.5, temp_min=-10.0, temp_max=40.0)
    with pytest.raises(ExtrapolationError):
        predict_time_to_temp(model, current_temp=5.0, target_temp=50.0)


def test_predict_time_to_temp_raises_when_never_reaches_target():
    model = _make_warmup_model(0.0)  # flat slope, never warms
    with pytest.raises(WarmupUnreachableError):
        predict_time_to_temp(model, current_temp=5.0, target_temp=15.0)


def test_predict_time_to_temp_raises_when_moving_away_from_target():
    model = _make_warmup_model(-0.5)  # cooling, but target is above current
    with pytest.raises(WarmupUnreachableError):
        predict_time_to_temp(model, current_temp=5.0, target_temp=15.0)
```

(`pytest` and `SpeedModel`/`HeatingModel`/`ForecastModel` are already
imported at the top of `tests/test_simulate.py` — only the `simulate`
import line needs extending as shown, plus the new `_make_warmup_model`
helper and test functions appended.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_simulate.py -v`
Expected: FAIL to collect — `WarmupUnreachableError`/`predict_time_to_temp`
unresolved imports.

- [ ] **Step 3: Add `WarmupResult`, `WarmupUnreachableError`, and `predict_time_to_temp` to `simulate.py`**

In `src/born_to_rag/simulate.py`, add these constants near the existing
`STEP_SOC`/`MIN_SPEED`/`MAX_STEPS` constants:
```python
STEP_MINUTES = 1.0  # time-step size for the warmup simulation
MIN_SLOPE = 1e-3  # °C/min, floor below which a slope counts as "not progressing"
MAX_WARMUP_STEPS = 600  # 10 hours at STEP_MINUTES=1.0 — generous but bounded
```

Add the new exception class near `ExtrapolationError`:
```python
class WarmupUnreachableError(ValueError):
    """Le modèle prédit que la cible de température ne sera jamais atteinte."""
```

Add the new dataclass near `SimulationResult`:
```python
@dataclass
class WarmupResult:
    minutes: float
    n_sessions: int
```

Add the domain-check helper (mirrors `_check_domain`, reusing the same
metadata since `HeatingModel` trains on the same features table as
`SpeedModel`):
```python
def _check_temp_domain(model: ForecastModel, current_temp: float, target_temp: float) -> None:
    meta = model.speed.metadata
    lo, hi = meta["battery_temp_min_c"], meta["battery_temp_max_c"]
    for label, value in (("current_temp", current_temp), ("target_temp", target_temp)):
        if not (lo <= value <= hi):
            raise ExtrapolationError(
                f"{label}={value} est hors du domaine couvert par l'entraînement "
                f"[{lo:.1f}, {hi:.1f}] — prédiction refusée pour éviter l'extrapolation."
            )
```

Add the main function:
```python
def predict_time_to_temp(
    model: ForecastModel,
    current_temp: float,
    target_temp: float,
    power: float | None = None,
    ambient_temp: float | None = None,
) -> WarmupResult:
    """Prédit le temps (minutes) pour que la batterie passe de `current_temp`
    à `target_temp`, à `power` kW et `ambient_temp` °C constants, en
    simulant `d_temp/dt = HeatingModel(battery_temp, power, ambient_temp)`
    par pas de `STEP_MINUTES`.
    """
    n_sessions = model.metadata.get("n_sessions", 0)
    if target_temp == current_temp:
        return WarmupResult(minutes=0.0, n_sessions=n_sessions)

    _check_temp_domain(model, current_temp, target_temp)

    warming = target_temp > current_temp
    temp = current_temp
    minutes = 0.0
    steps = 0

    while steps < MAX_WARMUP_STEPS:
        if warming and temp >= target_temp:
            break
        if not warming and temp <= target_temp:
            break
        steps += 1

        d_temp_dt = model.heating.predict(
            pd.DataFrame(
                {
                    "battery_temp_mid": [temp],
                    "charge_power": [power],
                    "ambient_temp_mid": [ambient_temp if ambient_temp is not None else np.nan],
                }
            )
        )[0]

        if warming and d_temp_dt <= MIN_SLOPE:
            raise WarmupUnreachableError(
                f"À {temp:.1f}°C, le modèle prédit un réchauffement de {d_temp_dt:.3f} °C/min "
                f"— la cible {target_temp:.1f}°C ne sera jamais atteinte dans ces conditions."
            )
        if not warming and d_temp_dt >= -MIN_SLOPE:
            raise WarmupUnreachableError(
                f"À {temp:.1f}°C, le modèle prédit un refroidissement de {d_temp_dt:.3f} °C/min "
                f"— la cible {target_temp:.1f}°C ne sera jamais atteinte dans ces conditions."
            )

        temp += d_temp_dt * STEP_MINUTES
        minutes += STEP_MINUTES

    if steps >= MAX_WARMUP_STEPS:
        raise WarmupUnreachableError(
            f"La cible {target_temp:.1f}°C n'est pas atteinte après {MAX_WARMUP_STEPS} minutes simulées "
            "— abandon pour éviter une boucle infinie."
        )

    return WarmupResult(minutes=minutes, n_sessions=n_sessions)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest tests/test_simulate.py -v`
Expected: PASS, all cases green (including the pre-existing
`predict_duration` tests, still passing unmodified).

- [ ] **Step 5: Commit**

```bash
git add src/born_to_rag/simulate.py tests/test_simulate.py
git commit -m "feat(simulate): add predict_time_to_temp warmup estimator"
```

---

### Task 4: `predict-warmup` CLI command

**Files:**
- Modify: `src/born_to_rag/cli.py`

**Interfaces:**
- Consumes: `predict_time_to_temp`, `WarmupUnreachableError` (Task 3).

- [ ] **Step 1: Extend the `simulate` import**

In `src/born_to_rag/cli.py`, change:
```python
from born_to_rag.simulate import ExtrapolationError, predict_duration
```
to:
```python
from born_to_rag.simulate import (
    ExtrapolationError,
    WarmupUnreachableError,
    predict_duration,
    predict_time_to_temp,
)
```

- [ ] **Step 2: Add the `predict-warmup` command**

Right after the existing `predict` command (after its closing `raise
typer.Exit` / `typer.echo` block, before the `if __name__ == "__main__":`
line), add:
```python
@app.command()
def predict_warmup(
    temp_now: float = typer.Option(..., "--temp-now", help="Température batterie actuelle (°C)"),
    temp_target: float = typer.Option(..., "--temp-target", help="Température batterie cible (°C)"),
    power: float | None = typer.Option(None, "--power", help="Puissance instantanée (kW), si connue"),
    model_path: Path = typer.Option(Path(DEFAULT_MODEL_PATH), "--model-path"),
) -> None:
    """Prédit le temps pour atteindre --temp-target °C depuis --temp-now °C."""
    try:
        model = load_model(model_path)
    except FileNotFoundError:
        typer.echo(f"Erreur : modèle introuvable ({model_path}). Lancer `btf train` d'abord.", err=True)
        raise typer.Exit(code=1)

    try:
        result = predict_time_to_temp(model, temp_now, temp_target, power=power)
    except (ExtrapolationError, WarmupUnreachableError, ValueError) as exc:
        typer.echo(f"Erreur : {exc}", err=True)
        raise typer.Exit(code=1)

    typer.echo(
        f"Batterie {temp_now:.0f}°C → {temp_target:.0f}°C : "
        f"{result.minutes:.0f} min. Basé sur {result.n_sessions} sessions."
    )
```
(Typer auto-derives the command name `predict-warmup` from the function name
`predict_warmup`, matching the existing `predict`/`train`/`evaluate`/`ingest`
naming style — no explicit `name=` needed.)

- [ ] **Step 3: Compile-check via existing CLI smoke test**

There's no dedicated `test_cli.py` in this project (consistent with the
existing codebase — `cli.py`'s commands are thin wrappers, not unit-tested
directly). Verify manually instead:
```bash
uv run btf --help
```
Expected: `predict-warmup` appears in the command list alongside
`ingest`/`train`/`evaluate`/`predict`.

If a trained model already exists at `models/forecast_model.joblib` (from
prior `btf train` runs against the existing simulated `data/`), also run:
```bash
uv run btf predict-warmup --temp-now 5 --temp-target 15
```
Expected: either a plausible `"Batterie 5°C → 15°C : N min. Basé sur M
sessions."` line, or a clean `Erreur : ...` message (e.g. an
`ExtrapolationError` if 5-15°C isn't within the current toy model's trained
domain) — not a stack trace. If no trained model exists yet, state that
explicitly rather than fabricating output.

- [ ] **Step 4: Commit**

```bash
git add src/born_to_rag/cli.py
git commit -m "feat(cli): add predict-warmup command"
```
