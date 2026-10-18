# Plan: tune tracked-motion detection on real scenes

**Side:** phone · **Size:** medium

## Goal

Fit the object-level criteria of the tracked-displacement detector
([`../design/decisions/0013-motion-detection-tracked-displacement.md`](../design/decisions/0013-motion-detection-tracked-displacement.md))
to labelled real footage. Prove on the phones that it holds the false-trigger and battery
targets below.

## Where it stands (2026-10-17)

**Implemented:** noise model, lazy census block matching with a forward-backward check,
tracker with the particle and swarm rules, asynchronous luma readback on its own thread, the
`PMTR2` trace, `MotionReplayTest`, and the debug benchmark.

**Validated on synthetic scenes** (`MotionDetectorTest`, 160×120 at 15fps).

Detected:

- a person-sized object (~0.5s after fully in view);
- a 4×9px distant walker on `high` (and not on `low`);
- 0.25px/frame drift;
- a swaying tree.

Never fire, on `high`:

- flicker over a textured region (600 frames);
- auto-exposure hunting;
- a light switching on;
- σ=12 night noise (900 frames);
- 40 rain streaks per frame;
- five erratic insects;
- the camera being knocked.

**On device (BLU G5):**

- GLES 3 asynchronous readback works.
- 0 analysis frames dropped, 0 swap failures, 24fps held.
- Analysis cost: see *Battery* below.
- The full app unit suite passes (156 tests).

**Real scene (Pixel 6, street view, 2026-10-17).**

Daytime, 7.4 minutes of `PMTR2` trace. Ground truth was change in the street area, measured on
its own and checked by eye:

| detector | events | share of footage recorded |
| --- | --- | --- |
| old `high` | 8 | 89.7% (always on) |
| new `high` | 16 | 36% |
| new `medium` | 14 | 31% |
| new `low` | 4 | 7% |

- New `high` caught ~16 of ~18 car passes. Cars are 4-6px long at 160×120.
- Every new-detector event inspected was real: passing cars, and one car parking.
- Missed: cars running alongside a long wall at 421-432s.
- Two rules were loosened to get here. Tracks must now be seen in 1 frame in 3, not 1 in 2.
  The match test `ALPHA` went from 0.5 to 0.6: small objects leave about half their changed
  pixels unexplainable (the road they uncovered). 0.65 let synthetic rain through.

Night, replay of the clips the new detector recorded:

- Every confirmed track was on the street or the lot (car lights), none in the lit building
  beside it.
- No continuous night trace of quiet periods was captured, so night false-trigger rates are
  still unmeasured.

**Open: two runs recorded nothing.** On 2026-10-17 two record runs, started by toggling record
mode at 15:34 and 18:57, published no segments for 3h22m and 20min. The camera was healthy
throughout. The runs before (15:13) and after (19:20) recorded normally; the 19:20 run was still
recording steadily 75+ minutes in.

The cause is unknown. It wasn't thermal: the 18:57 run was cool. It wasn't a sensitivity change:
`medium` throughout. It wasn't a reused analysis thread: each toggle builds a new pipeline.
Whether the phone was pointed at the street during those runs is unconfirmed.

Next time it happens, pull the `PMTR2` trace and logcat for that period. Consider adding health
gauges (last-motion time, confirmed tracks, noise level) so a silent run shows from the
controller.

## Battery

Analysis cost per analysed frame, `medium`, 160×120. Device figures come from
`DebugMotionBenchReceiver` (debug build, so slower than release would be). JVM figures are
desktop.

| scene | JVM | BLU G5 (debug build) |
| --- | --- | --- |
| idle (still textured scene + noise) | 0.13ms | 1.40ms |
| one person walking | — | 3.92ms |
| rain (blocks active everywhere) | 1.7ms | 5.97ms |

At the G5's 12 analysed frames a second, that is ~2% of one core idle and ~7% in heavy rain.
The worst case started at 11.45ms. Three changes brought it down: lazy per-tile census,
branchless census trits, and no search under 8 changed pixels. Unboxed tracker association also
helped. Live on the G5, the full-census build measured 5.4ms mean on a featureless scene. Short
bursts at low clocks, preempted by the camera threads, read higher in wall-clock time than the
sustained benchmark.

Live gauges: `motion.analysisMeanMs` / `motion.analysisMaxMs` / `motion.framesDropped` in
`GET /api/camera/health`.

## Next steps

1. **Capture labelled traces** with the default trace settings (160×120 luma at the analysis
   rate, ~1GB/hour):

   ```sh
   adb shell am broadcast -n com.panopticon.phoneapp/.debug.DebugMotionTraceReceiver \
       -a com.panopticon.phoneapp.DEBUG_MOTION_TRACE_START --ei max_mb 4000
   ```

   Wanted scenes: an empty scene day and night; people near and far; a car; trees in wind;
   rain; insects at a lit porch; a light switching; auto-exposure at night. Point the cameras
   at your own property.
2. **Replay and score** through the shipping code and the old detector:

   ```sh
   ./gradlew :app:testDebugUnitTest --tests '*MotionReplayTest*' \
       -Pmotion.replay=<traces-or-clips> -Pmotion.replay.labels=<labels.txt> -Pmotion.replay.snapshots=true
   ```

   Labels are lines `<file> <start s> <end s> [name]`. The report gives events per source,
   recorded share, and per-label recall and latency, for both detectors.
3. **Refit** `MotionDetector.paramsFor` and the `BlockMotion` / `MotionTracker` constants
   against those numbers. Record the scenes they were fit to here.
4. **Pixel 6:** installed 2026-10-17; async readback works, 30fps, 0 dropped analysis frames,
   ~5-6ms per analysed frame on a busy street (debug build).

## Acceptance

- Zero events on labelled empty-scene spans, including flicker, rain and insects, at the
  default sensitivity.
- Every labelled person and car caught within 1s of being fully in view.
- BLU G5 analysis under ~3ms/frame at p95 on a quiet scene; no dropped analysis frames and no
  camera starvation over a day.

## Not in scope

Exclusion zones; ML models or native CV libraries; a live "motion now" API signal (kept out
by [`0005`](../design/decisions/0005-motion-detection.md)).
