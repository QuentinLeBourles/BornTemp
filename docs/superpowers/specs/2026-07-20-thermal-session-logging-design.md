# Thermal session logging + warmup prediction design

## Context

BornTemp already has a working live estimate of battery-temp behavior
(`ChargeAnalytics.classifyThermalTrajectory` — rolling temp slope, ETA to an
optimal temp, driving advice), but it's a heuristic fit from a single 2026-06-21
handoff document, not from this car's actual observed behavior, and none of it
is currently shown in the UI (see `docs/superpowers/discoveries` from the
2026-07-16 auto-connect session — computed every poll tick, never rendered).

Separately, a sibling project, `Born_to_rag` (CLI name `btf` — "prévisionnel de
temps de charge à partir de l'historique BornTemp"), already exists as a real
ML pipeline: it ingests `sessions.csv`/`samples.csv`, builds features (SOC
slope, temp slope), trains a speed model and a `HeatingModel`
(`d_temp/dt = f(battery_temp, charge_power, ambient_temp)`), and predicts
charge duration via `btf predict`. Its `ingest.py` module already validates a
CSV contract explicitly labeled "contrat PLAN 1, section T5" — a plan that was
referenced by name in the 2026-07-16 OBD auto-connect design doc
(`PLAN_1_borntemp_charge_logging.md`) but never actually written to disk. That
auto-connect design doc explicitly called out that its background
service/detection work was meant as shared foundation for exactly this kind of
session logging, and predicted (correctly) that a future plan would want to
log both driving and charging sessions through the same trigger.

`Born_to_rag`'s schema already anticipates non-charging sessions
(`charger_type: UNKNOWN`, `end_reason: unknown`) — it was designed with this
integration in mind, it just has never received real data. Today it only has
synthetic data from `simulate.py`. This design is that missing integration:
BornTemp exports real driving+charging session data in the contract
`Born_to_rag` already validates, and `Born_to_rag` gains a new prediction
function that uses its existing `HeatingModel` to answer "how long until my
battery reaches a good temperature for fast charging" — meaningful once real
driving-session data trains it, since today `HeatingModel` has only ever seen
charging samples.

## Goals

- BornTemp logs real driving and charging session data (SOC, battery temp,
  power, current, voltage) to `sessions.csv`/`samples.csv`, exactly matching
  `Born_to_rag`'s existing, already-validated contract.
- `Born_to_rag` gains a way to predict time-to-target-temperature using the
  same `HeatingModel` architecture it already has, now trained on real
  driving data in addition to charging data.
- No preconditioning exists on this vehicle; this closes that gap by letting
  the user check (offline, on their Mac, for now) how much longer to drive
  before the pack is warm enough to fast-charge well.

## Non-goals

- No BornTemp UI changes. This is background logging only — the prediction is
  consumed via `Born_to_rag`'s CLI, not shown live in the app. (A future phase
  will change this — see Forward compatibility — but is explicitly out of
  scope now.)
- No changes to `Born_to_rag`'s existing charge-duration prediction
  (`predict_duration`) or its ingest/feature/train pipeline — that pipeline
  already works generically across session "kinds" (it has no notion of
  charging vs. driving, only numeric SOC/temp/power samples), so it needs no
  changes to support the new data.
- No automatic data transfer between the phone and the Mac. Same manual
  `adb pull` workflow as today.
- No quantile (p10/p90) interval for the new warmup prediction — median-only
  for this pass; `HeatingModel` has a single regressor today, and there isn't
  enough real driving data yet to justify training quantile variants.

## Forward compatibility: future live server phase

Once enough real data has accumulated and the offline workflow has been used
for a while, the intended next phase is: BornTemp posts session data to a
server instead of writing local CSVs pulled via adb, the server runs
`Born_to_rag`'s ingest/train/predict pipeline, and responds with a live
prediction BornTemp can show while driving. That phase is **not** part of this
plan. It only changes the transport (HTTP round-trip instead of file pull) —
the ingest/features/train/predict pipeline designed here is meant to sit
underneath that server unchanged. This is why the CSV contract and the new
prediction function are being kept as clean, stable interfaces: whoever builds
that server phase should be able to call `Born_to_rag`'s existing functions
directly (`load_sessions`/`load_samples`/`predict_time_to_temp`) rather than
reworking them.

## Architecture

Two independently-deployed components, connected only by a file-based
contract — no runtime coupling.

- **BornTemp (Kotlin, on the phone)**: a new `ChargeSessionLogger` class,
  instantiated once in `ObdSessionController` alongside the existing
  `SessionCapture` and `AbrpTelemetryClient`. Called from the same per-tick
  tail of `readAllData()` that already feeds those two. Appends to two
  persistent files across the app's lifetime (not recreated per connection).
- **Born_to_rag (Python, on the Mac)**: no pipeline changes required. One new
  function (`predict_time_to_temp`) plus a CLI command
  (`btf predict-warmup`), using the existing `HeatingModel` unchanged.
- **Bridge**: manual `adb pull` of the two CSVs into `Born_to_rag`'s `data/`
  directory, same as done earlier in this project's session-log verification.

## Component 1 — `ChargeSessionLogger` (BornTemp)

**Files**: `borntemp_sessions.csv`, `borntemp_samples.csv`, written to
`getExternalFilesDir(DIRECTORY_DOWNLOADS)` (same directory as
`SessionCapture`'s existing `.log`/`_soh.csv` files), opened in **append**
mode. Header written only when the file doesn't already exist — unlike
`SessionCapture`, these files are not recreated per connection; they
accumulate across the app's entire lifetime so a single `adb pull` gathers
every session recorded so far. This is purely **additive**: `SessionCapture`
(the `.log`/`_soh.csv` pair) and the ABRP push are untouched, and keep
serving their existing purposes (raw trace / spreadsheet trending, live
third-party telemetry) — `ChargeSessionLogger` is a third, independent
consumer of the same per-tick data, not a replacement for either.

**Session lifecycle**: a session starts (new `session_id`, `soc_start` /
`battery_temp_start_c` taken from the first sample) when polling begins, and
a new session starts whenever `vehicle_mode` transitions between
`NOT_CHARGING` and `AC_CHARGING`/`DC_CHARGING` — i.e. a single continuous BLE
connection that goes from driving straight into charging (or vice versa)
produces two session rows, not one. `session_id` is the connection-start
timestamp plus a per-segment suffix (e.g. `20260720-091533-0`,
`20260720-091533-1`), staying unique within the ever-growing file as
`Born_to_rag`'s `load_sessions`/`load_samples` require. A session **closes**
— appending its row to `sessions.csv` — either when the next segment starts
(mode transition) or when the connection drops; `soc_end` and `ended_at` are
taken from that segment's last recorded sample (not from the
transition/disconnect event itself, which may arrive without a fresh OBD
reading attached).

**`charger_type` mapping**: `NOT_CHARGING → "UNKNOWN"` (a driving segment),
`AC_CHARGING → "AC"`, `DC_CHARGING → "DC"`.

**`end_reason` mapping**:
- `"charge_complete"` — a charging segment (`AC`/`DC`) ends because
  `vehicle_mode` drops back to `NOT_CHARGING` while the connection is still
  alive.
- `"presence_loss"` — any segment ends because the BLE connection itself
  drops (car off / out of range), matching the vocabulary
  `Born_to_rag`'s `evaluate.py` already uses in its own comments.
- `"unknown"` — a driving segment (`UNKNOWN`) ends because charging started;
  not a completion or a presence loss, so it falls to the schema's existing
  catch-all value.

**`samples.csv` row per poll tick** (for whichever segment is currently
open): `session_id`, `timestamp` (**epoch milliseconds** — matching this
schema's existing convention, distinct from the ABRP contract's epoch
*seconds*; these must not be conflated), `soc_percent`, `battery_temp_c`,
`charge_power_kw` / `charge_current_a` / `charge_voltage_v` (BornTemp's
**native sign convention, + = charge/regen** — the opposite of the
ABRP-facing payload's negated convention fixed earlier this session; this
export must not reuse that negation), `ambient_temp_c` (**left empty/null** —
not backfilled from the coolant-temperature proxy `ChargeEstimatorScreen`
uses as a UI default, since feeding an unvalidated proxy into training data
as if it were ground truth risks quietly biasing the model; `HeatingModel`
already tolerates missing values natively).

**Failure handling**: same fail-open philosophy as `SessionCapture` and the
ABRP push — any IO error is swallowed and never interrupts polling.

## Component 2 — `predict_time_to_temp` (Born_to_rag)

**New function**, `simulate.py`, alongside the existing `predict_duration`:

```
predict_time_to_temp(model, current_temp, target_temp, power=None, ambient_temp=None) -> WarmupResult
```

Steps `HeatingModel`'s predicted `d_temp/dt` forward in fixed time
increments (mirroring `_simulate_one`'s existing step-loop structure) until
`current_temp` crosses `target_temp`, accumulating elapsed minutes. Guards
against a non-progressing simulation the same way `simulate.py` already
guards `MAX_STEPS`/a minimum-magnitude floor — if the model predicts a slope
that never reaches the target, the function raises rather than looping
forever or returning a misleading number.

**Scope**: single point (median) estimate only — no p10/p90 band this pass.

**Domain check**: reuse `model.speed.metadata`'s `battery_temp_min_c` /
`battery_temp_max_c` (valid for both models — they train on the same
features table) to reject out-of-domain requests, mirroring
`predict_duration`'s existing `_check_domain`. No domain check on `power`,
consistent with `predict_duration` not checking it either.

**CLI**: new `predict-warmup` command in `cli.py`, mirroring the existing
`predict` command:
`btf predict-warmup --temp-now X --temp-target Y [--power Z] --model-path ...`,
printing the estimated minutes and session count in the same style as the
existing `predict` output.

**Tests**: new cases in `tests/test_simulate.py`, mirroring the existing
`_ConstantRegressor`/`_make_model` stub pattern already used for
`predict_duration` — within-domain success, out-of-domain rejection, and
non-progressing-slope rejection.

## Data contract reference

Both `sessions.csv` columns (`session_id, started_at, ended_at, soc_start,
soc_end, charger_type, battery_temp_start_c, end_reason`) and `samples.csv`
columns (required: `session_id, timestamp, soc_percent, battery_temp_c`;
optional: `charge_power_kw, charge_current_a, charge_voltage_v,
ambient_temp_c`) are already defined and validated by
`Born_to_rag/src/born_to_rag/ingest.py` — this design does not change that
schema, only implements the BornTemp-side producer for it and the
Born_to_rag-side consumer of the new driving-regime data.

## Testing & verification

- **BornTemp**: unit tests for the session-splitting/charger_type/end_reason
  state machine (pure logic, no Android/BLE dependency, same JVM-testable
  tier as `ConnectRetryPolicy`). Manual in-vehicle verification: drive, plug
  in immediately after, confirm two session rows are produced with the right
  `charger_type`/`end_reason` transition; force-disconnect mid-drive, confirm
  `presence_loss`.
- **Born_to_rag**: unit tests for `predict_time_to_temp` per above. End-to-end
  check: `adb pull` real accumulated data, run `btf ingest` to validate the
  schema, `btf train`, then `btf predict-warmup` against a real cold-battery
  reading to sanity-check the output is plausible.
