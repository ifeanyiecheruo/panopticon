# 0014 — Thermal safety shutdown

**Status:** Accepted · implemented (phone-app, controller), 2026-10-18. Verified on the BLU G5
(API 28): the battery fallback by lowering its thresholds, and the thermal-status path with a
simulated severity. Neither has been seen in real sun yet, and the thermal-status path has not
yet run on a phone that reports a real severity (the Pixel 6).

## Context

A phone runs unattended all day, wherever it was propped up. If that spot gets direct sun for a
few hours, a phone recording continuously (camera, GL compositing, hardware encoder, motion
analysis) heats on top of the sun's heat. The platform's own defences come late and are blunt:
throttling first, then killing apps or shutting down. Meanwhile the battery has spent hours
above the temperature it should be charged at. The app should back off by itself before that,
and come back by itself once the spot is in shade again, without anyone at the phone.

## Decision

### The platform's thermal status where it exists, battery temperature only where it doesn't

Android 10 (API 29) added a dedicated API for exactly this: `PowerManager`'s thermal status, a
0..6 severity with a change listener. It is the device maker's own verdict, drawn from every
sensor the phone has (skin, SoC, battery) against limits tuned for that hardware. On a device
that has it, it is the **only** input. Battery temperature is not read there at all, because
second-guessing the maker's limits with one sensor and a generic threshold would be worse.
Headroom (API 30+) is not used either: it forecasts throttling, which is a different question.

On devices without it (the BLU G5's API 28), the fallback is the battery's own temperature:
`EXTRA_TEMPERATURE` on the sticky `ACTION_BATTERY_CHANGED` broadcast, which every API level
has and needs no privilege. Sun heats the battery, and the battery is what most needs
protecting. (Temperatures from `HardwarePropertiesManager` are privileged.) A device with
neither can never trip.

### Hysteresis, plus a dwell on the way down

`state/ThermalGuard.kt`. `ThermalPolicy` is the pure decision and is unit-tested.
`ThermalGuard` samples every 10s, and also immediately on every thermal-status change, through
`addThermalStatusListener`. The periodic sample still matters: the resume dwell is time-based,
and the battery fallback has no change callback.

| | thermal status (API 29+) | battery fallback (API < 29) |
|---|---|---|
| **Trip**, first sample at or over | `thermalCriticalLevel`, default **`severe`** (3): the platform's "greatly throttled" | `thermalCriticalTempC`, default **45°C** (the usual upper limit for charging Li-ion) |
| **Resume**, held at or under for 2 minutes | `thermalSafeLevel`, default **`moderate`** (2) | `thermalSafeTempC`, default **40°C** |

The resume default is `moderate`, not `light`, because `moderate` is a working phone's
normal state. Measured on the Pixel 6 (2026-10-18, indoors, recording on AC power):

- It had reached `severe`. With the camera off it fell to `moderate` within about 20s and to
  `light` within about 50s.
- After resuming, it settled back at `moderate` (headroom 0.93–0.98) and stayed there through
  22 minutes of recording without returning to `severe`.

Requiring `light` would only add delay to every resume. It would also make the guard read as a
duty cycle, when the phone is simply working normally.

No dwell on the way up: by the time either reading is over the line, the phone is already hot,
and neither reading is noisy. On the way down, any warm sample restarts the 2-minute wait.
Without it, a phone sitting right at the safe point would restart the camera, warm up a little,
and stop again on the next sample.

All four thresholds are persisted in `DeviceConfig` and settable per phone through
`POST /api/config`. The guard reads them on every sample, so a change applies within 10s.

- **Levels:** the critical level must be `light`..`critical`, and the safe level below it.
  Past `critical` the platform is already shedding load itself (`emergency`) or shutting down.
- **Temperatures:** critical must be 20–60°C, and safe at least 1°C below it. The 20°C floor
  exists so the fallback can be tripped on purpose on a desk.

The caps exist for one reason: a guard that waits until the phone's own shutdown point isn't
guarding anything.

### What shuts down, and what keeps running

On trip, `PanopticonService` stops everything that loads the SoC: the recording pipeline
(camera, GL, encoder, motion analysis), the live pipeline, and any calibration sweep in
progress. The foreground notification changes to "paused: overheating" with the temperature
and the resume point.

These keep running:

- **The HTTP server**, so a controller can see the shutdown and keep syncing finished clips.
  Serving files costs little compared with a camera pipeline.
- **The partial wake lock.** Without it the CPU suspends, and the guard's own sampler stops with
  it, so nothing would notice the phone cooling. An idle CPU holding a wake lock adds no
  meaningful heat.
- **The foreground service itself.** Stopping it would leave nothing to bring the camera back.

The first sample is taken synchronously in `onCreate`, before `onStartCommand` would open the
camera. A phone that boots hot, or is restarted hot by `START_STICKY`, never opens it.

### Mode intent survives the shutdown

`AppState.mode` keeps the mode that was asked for, and resuming re-applies it. While
overheated:

- `POST /api/mode` `record` / `standby` is accepted and recorded, and takes effect on resume.
  Asking for `record` while hot is reasonable to queue, since it is the unattended resting
  mode. Asking for `standby` means the phone stays off after it cools.
- `POST /api/mode` `live` and `POST /api/live/start` are **`503`**. Live preview is
  interactive, and would come back long after its viewer had left.
- `POST /api/calibration/start` is a **`409`** (`phone is overheated…`). A sweep in progress is
  cancelled at the trip.

### Advertised as its own status

`GET /api/status` reports `status: "overheated"`, ahead of every other status. It also reports
a `thermalShutdown` object: `active`, `source` (`thermalStatus` | `batteryTemperature` |
`none`), `reason` (the source that tripped it), `sinceMs`, and the reading and thresholds that
belong to the source (`level` with `criticalLevel`/`safeLevel`, or `batteryTempC` with
`criticalTempC`/`safeTempC`). The controller shows an **Overheated** pill on the phone
detail and a "Thermal guard" row with the reading and the resume point. Live preview and
calibration give an "overheated" outcome instead of a generic failure. Phones on older builds
send no `thermalShutdown` object; the controller treats that as absent, not as cool.

## Consequences

- A phone in the sun records nothing while it is hot. That is the trade: missing footage, in
  exchange for not baking the battery. The gap is visible as `overheated`, not silent.
- On API 29+ phones the scale is the maker's: what `severe` means in degrees differs from
  phone to phone. A phone that reaches `severe` during normal work indoors will pause. If that
  is too eager for one phone, move its `thermalCriticalLevel` up to `critical`.
- Charging heat is not under the app's control. A phone on a fast charger in the sun may stay
  hot even with the camera off. The guard holds until it cools, which is correct.
- A device that reports neither battery temperature nor thermal severity can never trip.
- Debug builds have `DebugThermalReceiver`. Over `adb`, without a paired controller, it can
  set a simulated severity (`ThermalGuard.simulatedSeverity`, null in release builds) or set
  the fallback temperatures. Its header shows how to trip either path on a desk.
