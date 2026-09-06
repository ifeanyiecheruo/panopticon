# Quirks

This is a **new** project doc, separate from `panopticon-prototype/QUIRKS.md` (the old,
abandoned prototype's findings). Same format: what we assumed → what actually happens → what we
do about it → where. Entries under "Reconfirmed on Pixel 6" were directly re-verified against
real hardware while building this vertical slice (`phone-app/`). Entries under "Carried forward"
were adopted as defensive workarounds from the old doc without being independently re-tested here
- don't mistake them for confirmed-in-this-project.

Primary test device for most of the entries below: **Google Pixel 6 (`oriole`), Android 16 (API
36), build `BP2A.250605.031.A2`**, adb serial `1C281FDF6005H0`. Encoder observed in logcat:
`ExynosC2H264EncComponent` (Exynos hardware AVC encoder) - a different SoC family from the old
prototype's Pixel 9a (Tensor), which is itself a reason some findings below don't transfer
directly. A second device, a **BLU G5, Android 9 (API 28)**, also gets used opportunistically as
a low-end/old-API check - see "Foreground service" below for what it caught that the Pixel 6
(API 36) couldn't have.

## Contents

- [Camera2 / recording pipeline](#camera2--recording-pipeline)
  - [Reconfirmed on Pixel 6](#reconfirmed-on-pixel-6)
  - [Carried forward, not yet re-verified in this project](#carried-forward-not-yet-re-verified-in-this-project)
- [Foreground service](#foreground-service)
- [HTTP server](#http-server)
- [Development tooling (Windows) - new findings this project](#development-tooling-windows---new-findings-this-project)

## Camera2 / recording pipeline

### Reconfirmed on Pixel 6

#### `CameraCaptureSession` teardown+recreate does NOT reproduce the old "configure-then-fail-async" quirk here
**Old prototype's claim (Pixel 9a):** a session can report `onConfigured` successfully and then
fail at the very first capture request submission, with nothing catchable at the call site.
**What we did:** this slice's clip-rotation design (`CameraPipeline.beginSegment()`) fully tears
down and recreates the `CameraCaptureSession` + `MediaRecorder` on every ~10s clip rotation -
effectively a repeated stress test of session (re)configuration. We kept the old workaround
anyway (configure → wait 500ms → check for an async failure signal → retry up to 3x, see
`CameraPipeline.kt`), instrumented to log every retry.
**Actually observed:** over roughly 20 consecutive rotations (~3.5 minutes of continuous
recording) plus 2 full stop/restart cycles (triggered via `POST /api/mode` switching to `live`
and back), **zero** async configure failures were logged - every session configured and started
cleanly on the first attempt. The retry path never fired.
**Conclusion:** not reproduced on this device/encoder combination. We're keeping the defensive
retry (it's cheap and a `CameraCaptureSession` is documented as capable of this failure mode in
general), but this specific Pixel 6 + Exynos encoder pairing didn't exhibit it under normal
conditions. Worth retesting under thermal/memory pressure if it ever becomes suspect again.
**Where:** `phone-app/app/src/main/kotlin/com/panopticon/phoneapp/camera/CameraPipeline.kt`
(`beginSegment()`).

#### Camera2 calls did not throw synchronously here, but the defensive wrapping stayed
**Old prototype's claim:** `createCaptureSession`/`setRepeatingRequest`/etc. observed throwing
`CameraAccessException` synchronously under HAL stress, not just via callbacks.
**What we did:** every Camera2 call in `CameraPipeline` is wrapped in try/catch regardless
(`openCameraDevice`, `createCaptureSession`, `beginSegment`'s `setRepeatingRequest`/`start()`).
**Actually observed:** no synchronous throws seen in this slice's testing - the only exception
logged during the whole session was an expected `kotlinx.coroutines.JobCancellationException`
when `stop()` cancelled the run loop's coroutine job during a mode switch (working as intended,
not a HAL failure).
**Conclusion:** not reproduced here either, same caveat as above - kept as defensive wrapping
since it costs nothing and the old finding was itself real (just device-specific).
**Where:** `CameraPipeline.kt`.

#### MediaRecorder's default keyframe cadence was very regular on this device - but it's a different code path than the old finding
**Old prototype's claim:** `MediaFormat.KEY_I_FRAME_INTERVAL` (a **MediaCodec** parameter) was
honored wildly irregularly (~1.6s-4.7s) when targeting ~2s, breaking a live-HLS segmenter's
timeline assumptions.
**Important caveat:** this slice deliberately uses `MediaRecorder`, not raw `MediaCodec` (see
"Simplifications" in `CameraPipeline.kt`'s class doc) - `MediaRecorder` has **no public API** for
setting the I-frame interval or requesting explicit sync frames at all (`setVideoEncodingIFrameInterval`
does not exist on `android.media.MediaRecorder`; that surface is MediaCodec-only). So this isn't a
direct re-verification of the same claim - we can only measure whatever cadence the device's
default encoder produces, not control it.
**What we measured:** `CameraPipeline.logKeyframeCadence()` extracts real keyframe timestamps from
every finished clip via `MediaExtractor` and logs the deltas. Across ~19 clips (~10s each, so ~9
keyframes/clip expected at a naive "1/sec" guess), the actual pattern was extremely consistent:
`[1031, 998, 998, 998, 998, 998, 998, 998, 998]` (ms) on essentially every single clip, run after
run. I.e. this device's default AVC encoder (`ExynosC2H264EncComponent`) produces a keyframe
almost exactly once per second, with negligible jitter (<35ms), unprompted.
**Conclusion:** no irregularity observed - but since neither the trigger mechanism nor the target
interval match the old finding's MediaCodec-based setup, treat this as "different pipeline, no
problem seen" rather than "the old bug is fixed." A future MediaCodec-based live pipeline for this
project should still budget for the old finding being real on *some* devices.
**Where:** `CameraPipeline.kt` (`logKeyframeCadence()`, `buildRecorder()`).

#### Unsupported-encoder-size guard ran successfully, but its failure mode (black frames) was not reproduced
**Old prototype's claim:** requesting a recording size the AVC encoder can't actually handle
(despite the camera declaring it) silently produces a black output surface, no error.
**What we did:** `CameraPipeline.pickRecordingSize()` cross-references the camera's
`StreamConfigurationMap` sizes against `MediaCodecInfo.VideoCapabilities.isSizeSupported()` before
ever recording, same approach as the old `recordingSizes()`.
**Actually observed:** on this Pixel 6, the intersection logic ran on every camera open and always
resolved to 1280x720 (both camera and encoder agree it's supported) - we never hit a case where
the two capability sets disagreed, so **the actual black-frame failure mode was not reproduced or
exercised** here. The guard is present but untested against a real disagreement.
**Where:** `CameraPipeline.kt` (`pickRecordingSize()`).

#### A thumbnail came back black - explained by test-environment lighting, not a codec bug
While verifying `GET /api/clips/:filename/thumbnail` end-to-end, the extracted JPEG frame was
essentially solid black. Logcat showed `[BandingDetection] Input environment_lux: ~0.07-0.08`
consistently throughout the entire test session (from the phone's own flicker/ambient-light
sensor), meaning the physical test environment was genuinely near-zero light (phone lens
covered/in a dark space), not a rendering bug. **Not treated as a reproduction of any known
quirk** - flagged here only so a future reader doesn't mistake "we saw a black thumbnail" for "we
found the black-frame bug." Re-verify visually in normal lighting if this ever needs re-checking.
**Where:** observed via `ClipStore.thumbnailFor()`'s output during manual testing, not a code change.

### Calibration zoom probe

The empirical zoom probe (`phone-app`'s `calibration/CalibrationRunner`) was built and run to
completion on both the **Pixel 6 (API 36)** — `CONTROL_ZOOM_RATIO` + logical-multi-camera path —
and the **BLU G5 (API 28)** — legacy `SCALER_CROP_REGION` path. Findings below.

#### An API-gated `CaptureResult`/`CaptureRequest` key throws `NoSuchFieldError` even behind a runtime `SDK_INT` guard
**What we assumed:** wrapping a use of `CaptureResult.CONTROL_ZOOM_RATIO` (API 30) in
`if (Build.VERSION.SDK_INT >= R) { ... }` is enough to keep it off older devices.
**What actually happens (BLU G5, API 28):** ART verifies the whole method when it's first run
and resolves *every* field reference in it, guard or not — so the first zoom sample threw
`java.lang.NoSuchFieldError: No static field CONTROL_ZOOM_RATIO ... in class CaptureResult`.
Same trap for `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` (API 29), `CONTROL_ZOOM_RATIO_RANGE`
(API 30), and `getPhysicalCameraIds()` / `REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA`
(API 28) on anything below their level.
**What we do:** every such key lives in its own `@RequiresApi` object
(`calibration/ZoomApiCompat.kt`: `ZoomRatioApi30`, `ActivePhysicalIdApi29`, `LogicalCameraApi28`).
A separate class is only loaded/verified when it's actually referenced, which only happens
inside the SDK check — so older devices never touch the missing field.
**Where:** `calibration/ZoomApiCompat.kt`, `calibration/CalibrationRunner.kt` (`hasZoomRatio` /
`hasActivePhysicalId`).

#### Releasing an `ImageReader` while a frame callback is in flight is a native `SIGSEGV` (Pixel 6)
**What we assumed:** wrapping the `onImageAvailable` body in `try/catch` + a lock is enough to
release the reader safely from another thread when a resolution's probe finishes.
**What actually happens (Pixel 6, mid-sweep, ~resolution 16/24):** `reader.close()` on the
sweep coroutine thread unmaps the native `YUV_420_888` plane buffer while an `onImageAvailable`
callback on the reader's Handler thread is still inside `buffer.get(bytes)` → `Fatal signal 11
(SIGSEGV), code 1 (SEGV_MAPERR) ... in tid PanopticonCalib`. A `catch` can't save you from a
native memory fault; the process just dies (and `START_STICKY` restarts the service, losing the
run). Not seen on the BLU G5 — its slower frame rate made the window much smaller.
**What we do:** teardown order is now `stopRepeating()` → set a `readerClosing` flag under the
frame lock → `setOnImageAvailableListener(null)` → post a no-op to the callback Handler and
await it (guarantees any running callback has returned, since that thread is serial) → only
then `session.close()` / `reader.close()`.
**Where:** `calibration/CalibrationRunner.kt` (`probeResolution`, the `finally` block).

#### Closing a `CameraCaptureSession` and opening the next one on the same still-open `CameraDevice` disconnects the device entirely (BLU G5)
**What we assumed:** keep one `CameraDevice` open for a camera and cycle
`ImageReader` + `CameraCaptureSession` per output resolution.
**What actually happens (BLU G5):** the *first* resolution probes fine; creating the *second*
session's request throws `IllegalStateException: CameraDevice was already closed` /
`ServiceSpecificException: The camera device has been disconnected (code 4)` — the HAL dropped
the whole device on the session teardown, not just the session. The old prototype flagged the
adjacent "two concurrent record + analysis surfaces never configure" case; this is a related
weak-HAL failure on plain session recycling.
**What we do:** the probe opens a **fresh `CameraDevice` per resolution** (open → one session →
sweep the zoom steps → close → ~600 ms settle → next). Slower, but every resolution is actually
probed. `openCameraDeviceWithRetry` also retries `ERROR_MAX_CAMERAS_IN_USE` (a just-closed
camera reports busy briefly on this device), and a per-run 1.2 s grace delay covers the motion
pipeline's still-in-flight teardown when calibration is entered straight from RECORD.
**Where:** `calibration/CalibrationRunner.kt` (`probeCamera`, `openCameraDeviceWithRetry`).

#### Interleaving `CONTROL_ZOOM_RATIO` and `SCALER_CROP_REGION` in one session's repeating request corrupts the readback (Pixel 6 front camera)
**What we did first:** the per-zoom "position honoured?" sub-probe swapped the session's
*repeating* request to an off-centre `SCALER_CROP_REGION`, then the next step swapped it back to
`CONTROL_ZOOM_RATIO`.
**What actually happens (Pixel 6, camera 1 / front):** the primary readback went junk —
`requested 1.19× → reported 1.0×`, `requested 1.43× → reported 1.19×` (lagging one step),
`ratioHonored = false` almost everywhere, `positionHonored = false` with six fail ratios. The
back camera (0) was fine; the front camera can't cleanly alternate the two zoom controls on the
repeating stream.
**What we do:** the repeating request now holds *one* control for the whole resolution, and
**both the primary readback and the position sub-probe are one-shot `session.capture()` calls**
(`captureOneShot`) whose `TotalCaptureResult` is guaranteed to belong to that request. With
that, the front camera reads back clean (`reported ≈ requested` across 1.0–10.0×,
`positionHonored = true`). Lesson for any future Camera2 probing: don't infer a value from
"latest result of a churning repeating request" — capture the exact request and read *its*
result.
**Where:** `calibration/CalibrationRunner.kt` (`captureOneShot`, `probeResolution`).

#### Frame-sharpness (variance-of-Laplacian) is only trustworthy against a lit, textured target
The sharpness values are tiny (single/low-double digits) unless the camera sees a genuinely
lit, detailed scene — "lights on in the room" isn't enough if the phone is face-down or aimed
at a blank surface. On both device runs `qualityCollapseRatio` fired somewhere in the 1.3–4.9×
band, but the underlying `sharpnessRelToBaseline` curve was noisy enough that the *exact* ratio
shouldn't be trusted; the *shape* (digital zoom softens as you push past ~2–3× on the Pixel 6
main sensor, faster on the fixed-focus front camera) is real. Re-run against a resolution chart
before quoting a number.
**Where:** `calibration/ZoomMath.varianceOfLaplacian` / `deriveQualityCollapse`.

#### Device findings

**Pixel 6 (API 36), camera 0 / back — logical multi-camera, physicals `2` + `3`:**
`CONTROL_ZOOM_RATIO_RANGE = 0.67–7.0`, `SCALER_CROPPING_TYPE = CENTER_ONLY`. The probe
**empirically located the optical→digital handoff at 1.15×**: `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`
is `3` (ultrawide, `LENS_FOCAL_LENGTH` 2.35 mm) for requested ratios ≤ 0.96×, flips to `2`
(main wide, 6.81 mm) at 1.15× and stays there to 7.0× — so `opticalRange 0.67–1.15`,
`digitalRange 1.15–7.0`. **Every requested ratio was honoured** (`reportedRatio ≈ requested`
across the whole range), and — despite `CENTER_ONLY` cropping — the off-centre position probe
came back honoured with the one-shot capture fix. 24/24 YUV output sizes, all 14 zoom steps.

**Pixel 6, camera 1 / front — single sensor:** `CONTROL_ZOOM_RATIO_RANGE = 1.0–10.0`,
all-digital (`crossoverMethod = "single-camera"`). Every ratio honoured 1.0–10.0×, position
honoured, 24/24 sizes. (This is the camera whose readback was junk before the one-shot fix —
see above.)

**BLU G5 (API 28), both cameras — single physical sensor, legacy `SCALER_CROP_REGION` path:**
`crossoverMethod = "single-camera"`, `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM = 2.0`, no
`CONTROL_ZOOM_RATIO_RANGE`. `SCALER_CROPPING_TYPE = FREEFORM`; reported crop area tracked the
request cleanly across all 24 sizes and **the off-centre crop's position was honoured** at
every ratio.

**Net:** the old prototype's "`SCALER_CROP_REGION` position isn't honoured, and the device lies
about it" finding **did not reproduce** on either device once the probe stopped corrupting its
own readback. Worth re-checking with a tighter position tolerance and on more hardware before
calling it settled.

### Carried forward, not yet re-verified in this project

Out of scope for this vertical slice (no manual Camera2 controls, no live HLS pipeline, no
multi-camera; calibration and motion-gated recording now exist but the specific quirks below
still haven't been re-tested against the Pixel 6) - see `panopticon-prototype/QUIRKS.md` for the
original write-ups:

- `SCALER_CROP_REGION` position isn't honored, and the device lies about it — the calibration
  probe measures exactly this (`positionHonored` / `positionFailRatios` per camera). **Did not
  reproduce** on the Pixel 6 or the BLU G5 (see "Device findings" in the zoom-probe section);
  keep it on the list until re-checked with a tighter tolerance and on more hardware.
- Digital zoom quality collapses well below the declared max, invisible to crop-region metadata —
  the probe's `qualityCollapseRatio` targets this. The *shape* showed up on both devices
  (softening past ~2–3×) but the exact ratio needs a lit resolution-chart run to trust (see the
  zoom-probe section).
- Crop readback is unreliable — the probe reads `SCALER_CROP_REGION` back from a one-shot
  capture of the exact request; clean on both devices once it stopped reading stale
  repeating-request results.
- Manual controls aren't reliably honored at all
- `AE_MODE_OFF` (manual exposure) needs `MANUAL_SENSOR`, despite being independently selectable
- `CONTROL_AE_LOCK` is a metering freeze, not a manual-exposure dial
- `CONTROL_MODE=USE_SCENE_MODE` silently overrides the individual 3A controls
- `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES` reports duplicate values
- A `CaptureRequest` template mismatch broke calibration carry-through between modes - this
  project's `CameraPipeline` does consistently build from `TEMPLATE_RECORD` (following the old
  lesson proactively), but since this slice's `live` mode is a stub with no real capture pipeline
  of its own, there's nothing yet to actually cross-check consistency against.
- The HAL only honors roughly every other explicit sync-frame request
- The automatic keyframe timer and explicit requests fight each other
- In-place bitrate changes are silently ignored
- Concurrent `MediaCodec` access crashes natively, and a decoder that's thrown once throws forever
- **Two concurrent camera surfaces for record + motion sampling never configure** - the old
  prototype's claim (Pixel 9a / Tensor). **No longer moot:** this slice's motion-gated
  `CameraPipeline` now configures exactly that combination - an analysis `ImageReader`
  (`YUV_420_888`, ~QVGA) plus the `MediaRecorder` surface - in its RECORDING-phase session
  (`createCaptureSession(listOf(analysis.surface, recorderSurface), ...)`, `TEMPLATE_RECORD`).
  It has **not** been run on the Pixel 6 yet. If the RECORDING session fails to configure on
  hardware, the fallback is to alternate surfaces instead of co-configuring them: analyse only
  during ARMED, drop the analysis surface while RECORDING and rely on a fixed max-clip / trailer
  timeout for motion-stop rather than live detection during a recording. `MotionDetector` /
  `RecordingPhaseController` don't change either way - only how `CameraPipeline` wires sessions.
- CORS needs explicit header exposure for hls.js (adopted defensively in `PanopticonHttpServer.kt`
  for ranged clip downloads generally - `exposeHeader(Content-Range/Content-Length)` - but not
  verified against an actual browser `fetch()`/hls.js client, only via `curl`, since there's no
  live HLS pipeline in this slice to test it against)
- All `Browser / hls.js` and `Server / Node` sections (no live pipeline, no Node server in this
  project - the controller is a separate, not-yet-built project)

## Foreground service

### `startForeground`'s 3-arg (foregroundServiceType) overload doesn't exist before API 29
**Assumed:** calling the 3-argument `startForeground(id, notification, foregroundServiceType)`
overload is safe on any API level as long as the *type value itself* is only supplied on API 29+
(`if (SDK_INT >= Q) FOREGROUND_SERVICE_TYPE_CAMERA else 0`) - i.e. that the guard only needed to
protect the *value*, not the *method call*.
**Actually:** confirmed via a real crash on a BLU G5 (Android 9, API 28): the 3-arg overload
doesn't exist at all in the `Service` class before API 29 (Q) - calling it unconditionally throws
`java.lang.NoSuchMethodError: No virtual method startForeground(ILandroid/app/Notification;I)V`
immediately on `onStartCommand`, and since the service is `START_STICKY`, Android just kept
restarting and re-crashing it in a loop.
**Workaround:** branch on the overload itself, not just the argument value - call the 2-arg
`startForeground(id, notification)` below API 29, and only use the 3-arg
`foregroundServiceType`-carrying overload on API 29+.
**Where:** `phone-app/app/src/main/kotlin/com/panopticon/phoneapp/service/PanopticonService.kt`
(`onStartCommand`).

## HTTP server

### Bind-retry-on-crash-loop - implemented, not stress-tested via an induced crash
**Old prototype's claim:** a crashed process's socket isn't released fast enough for Android's
service auto-restart, causing a repeating `BindException` crash loop without a generous retry
budget.
**What we did:** `PanopticonHttpServer.start()` retries `embeddedServer(...).start()` on
`BindException` up to 20 attempts / ~30s total, same budget as the old prototype.
**Not tested:** we didn't deliberately crash the service mid-session to confirm the retry budget
is actually sufficient on this device - the server bound successfully on its very first attempt
every time we started/restarted the app during this project. Treat as "implemented defensively,
carried forward," not "reconfirmed."
**Where:** `phone-app/app/src/main/kotlin/com/panopticon/phoneapp/http/PanopticonHttpServer.kt`.

### End-to-end verified on real hardware (not a quirk, a confirmation)
Via `adb forward tcp:8080 tcp:8080` + `curl` against the running Pixel 6: bearer-token auth gate
(401 on missing/invalid token, every route but `POST /api/pair`), invite redemption
(`POST /api/pair?invite=...`), `GET /api/device`, `/api/build-info`, `/api/status`, `/api/config`,
`/api/mode` (both directions, including the recording pipeline correctly stopping/restarting
around a `live`->`record` round trip), `GET /api/clips` (delta list), full-file and byte-`Range`
downloads of `/api/clips/:filename/file` (`206 Partial Content` with a correct `Content-Range`),
`/api/clips/:filename/thumbnail`, `DELETE /api/clips/:filename` (with a following `404`), and
self-unpair via `DELETE /api/pair` (with a following `401` on the now-revoked token). All matched
`phone-http-api.md`'s documented shapes and status codes.

## Development tooling (Windows) - new findings this project

### `gradlew.bat` invoked bare through `cmd.exe //c` isn't found, even from the right cwd
**Assumed:** once `cd /d <path> && gradlew.bat ...` has changed into the project directory (which
it genuinely does - confirmed via a bare `cd` printout), a bare `gradlew.bat` invocation would be
found the same way Explorer/PowerShell would find it.
**Actually:** `cmd.exe //c "cd /d <path> && gradlew.bat ..."` (invoked from this environment's
Bash tool, itself calling `cmd.exe` explicitly per the old prototype's Makefile convention)
reliably printed `'gradlew.bat' is not recognized as an internal or external command` - even
though `dir`/`where` in the same session found the file, and a bare `cd` (no `%cd%` var
substitution - see next entry) confirmed the cwd was correct.
**Workaround:** prefix with `.\`: `.\gradlew.bat ...` resolved and ran correctly every time.
**Where:** used throughout manual testing in this session; worth carrying into `phone-app/Makefile`'s
`GRADLEW` variable (explicit `./gradlew.bat`, not a bare `gradlew.bat`, when routed through `cmd.exe`).

### `%cd%` inside a `cmd.exe //c "cd /d X && echo %cd%"` one-liner reports the *pre-cd* directory
**Assumed:** `%cd%` expands to the current directory at the point the `echo` command actually
runs, i.e. after `cd /d X` has taken effect earlier on the same line.
**Actually:** cmd.exe expands `%variables%` once, at parse time, before executing *any* command on
the line (without delayed expansion, `!var!`) - so `%cd%` in a `cd /d X && echo %cd%` one-liner
shows the directory the shell was in *before* the line ran, not after the `cd`. This produced a
confusing false lead while debugging the entry above (the cwd looked "one directory too shallow").
**Workaround:** use a bare `cd` (no arguments) to print the actual current directory instead of
`echo %cd%`, or enable delayed expansion (`setlocal enabledelayedexpansion` + `!cd!`) if a variable
is really needed.
**Where:** debugging notes only, not encoded in any script - worth remembering if `Makefile` ever
needs to introspect cwd from a `cmd.exe`-routed recipe.

### adb can mark a still-connected device "offline" until `adb kill-server`/`start-server`
**Assumed:** `adb devices` reliably reflects a physically connected, USB-debugging-enabled
device's real state.
**Actually:** with two devices attached (this Pixel 6, serial `1C281FDF6005H0`, plus a second
unrelated device), the Pixel 6 showed as `offline` in `adb devices` at the start of this session
despite being physically connected and previously working - `adb -s 1C281FDF6005H0 shell ...`
failed with `device offline` for every command.
**Workaround:** `adb kill-server && adb start-server` (then re-run `adb devices`) recovered it
immediately, no physical reconnection needed.
**Where:** dev workflow note - worth adding to `phone-app/Makefile`'s `install:phone`/`run:phone`
troubleshooting output if this recurs.

### This MSYS2 `make` also strips `GOPATH`/`USERPROFILE`/`TMP`/`TEMP`, breaking `go` inside recipes
**Assumed:** the old prototype's finding (`LOCALAPPDATA`/`USERPROFILE`/`APPDATA`/`ANDROID_HOME`
stripped from every process this `make` spawns) was a closed, fully-enumerated list scoped to
Android tooling.
**Actually:** building a unified root `Makefile` that also drives the Go-based controller hit the
same stripping for Go's toolchain env vars, in two stages: first `go test`/`go build` inside a
recipe failed with `neither GOMODCACHE nor GOPATH is set` (Go's Windows build computes a default
`GOPATH` from `%USERPROFILE%`, which is empty here); after pinning `GOPATH` explicitly, it failed
again with `build cache is required, but could not be located: GOCACHE is not defined and
%LocalAppData% is not defined`, and separately `go: creating work dir: mkdir
C:\Windows\go-build...: Access is denied` (Go's temp-dir fallback walks `TMP`/`TEMP`/`USERPROFILE`,
all empty here, and lands on `C:\Windows` via a last-resort Windows API call, which isn't
writable). Also confirmed this `make`'s own `$HOME`/`PATH` inside a recipe are **not** the
invoking interactive shell's values either (`$HOME` resolved to this `make`'s own
`/home/<user>`, not `/c/Users/<user>`; `$PATH` started with `/home/<user>/bin`, not the real
`PATH`) - a broader form of the same isolation than the original entry described.
**Workaround:** same fix as `ADB` above, extended to Go: `export GOPATH := $(firstword $(wildcard
/c/Users/*/go))`, plus explicit `export TMP`/`TEMP`/`GOCACHE` pointed at scratch directories under
that resolved `GOPATH` (created via `$(shell mkdir -p ...)` at Makefile-parse time), so nothing
Go-related depends on an env var this `make` might strip.
**Where:** `Makefile` (root), the `ifneq (,$(findstring MINGW,...))` Windows block.

### This `make`'s recipe shell eats a literal backslash before a letter (`\g` → `g`)
**Assumed:** a Makefile variable holding a literal Windows-style relative path prefix (`.\gradlew.bat`)
would reach `cmd.exe` unchanged when interpolated into a recipe line, the same way it reads in the
Makefile source.
**Actually:** the recipe line is first evaluated by `make`'s own shell (`sh`, POSIX-like) before
`cmd.exe` ever sees it, and POSIX shells treat a backslash before an ordinary character as "take
the next character literally" (dropping the backslash) - so `.\gradlew.bat` reached `cmd.exe` as
`.gradlew.bat` (no backslash, no separator between `.` and `gradlew`), which `cmd.exe` correctly
reported as not found. This is distinct from - and adds a further wrinkle on top of - the older
"bare `gradlew.bat` isn't found" entry above; the fix for that one (`.\gradlew.bat` instead of a
bare name) is exactly what tripped this new issue when moved from a one-off interactive command
into a `Makefile` recipe.
**Workaround:** double the backslash in the Makefile source (`.\\gradlew.bat`) so that after the
recipe shell's escape processing, `cmd.exe` still receives a single `\`.
**Where:** `Makefile` (root), the `GRADLEW` variable.

### This `make`'s own `$(CURDIR)` resolves through the wrong mount alias for an OneDrive-rooted repo
**Assumed:** GNU Make's built-in `$(CURDIR)` variable reflects the same working directory a
recipe's own shell would report via `pwd`.
**Actually:** for this repo specifically (its real path is under `C:\Users\<user>\OneDrive\...`),
`$(CURDIR)` resolved to `/home/<user>/OneDrive/...` - a path that doesn't exist at all in this
`make`'s own recipe shell (`mkdir -p` on it failed trying to create `/home` itself, permission
denied), even though a bare `pwd` run as a recipe command correctly printed
`/c/Users/<user>/OneDrive/...`. Root cause not fully diagnosed, but consistent with this
`make`/MSYS2 build having its own internal mount-alias table it consults for `$(CURDIR)`
specifically, separate from (and less complete than) whatever its spawned recipe shells actually
use for real filesystem paths.
**Workaround:** never use `$(CURDIR)` in this Makefile; use `$(shell pwd)` instead (confirmed
correct) for anything that needs the repo root as an absolute path (`ROOT` in the Makefile).
**Where:** `Makefile` (root), the `ROOT` variable.

### This `make`'s `command -v <tool>` can resolve through a *working-but-different* path alias too
**Assumed:** if `command -v nvm` resolves and the resulting path passes a `[ -f ... ]` existence
check inside a recipe, that same path string is safe to bake into a generated script for later use
outside `make`.
**Actually:** `command -v nvm` inside a recipe returned `/home/<user>/AppData/Roaming/nvm/nvm` -
unlike the `$(CURDIR)` case above, this path *does* resolve inside this `make`'s own recipe shells
(nvm-windows' install directory apparently being one of a small set of user-profile paths this
MSYS2 build's `/home/<user>` mount aliases to), which made it easy to mistake for a genuinely
portable path. A shim script written with this path baked in worked when run via `make`, then
failed with "No such file or directory" run directly from an ordinary interactive Git Bash prompt,
where `/home/<user>/AppData/...` isn't a valid path at all.
**Workaround:** don't trust `command -v`'s literal output for anything that needs to be portable
outside this one `make`'s own shells - construct the path independently instead. Here: nvm-windows'
root is always `%APPDATA%\nvm` by its own fixed convention, so `NVM_ROOT` is built from
`/c/Users/$(shell whoami)/AppData/Roaming/nvm` rather than `dirname` of `command -v nvm`'s output;
`command -v nvm` is still used, but only as an existence check (installed vs. not), never as a path
source.
**Where:** `Makefile` (root), the `NVM_ROOT` variable.

### Gradle's unit-test task needs `local.properties`' `sdk.dir` even when `assembleDebug` doesn't
**Assumed:** since `ANDROID_HOME` being stripped from this `make`'s recipes never broke
`assembleDebug` in practice, Gradle must be resolving the SDK location some other reliable way
that `test` would share.
**Actually:** `assembleDebug` kept succeeding only because its relevant tasks were already
`UP-TO-DATE` from a previous (IDE- or manually-configured) run and never actually needed to
re-resolve the SDK location; `./gradlew test`, run fresh after the monorepo restructuring
recreated `phone-app/` from git history (which never tracked the gitignored `local.properties`),
failed immediately with "SDK location not found" - the one thing that reliably tells Gradle where
the SDK is regardless of environment variables is `local.properties`' `sdk.dir` line (normally
auto-written by Android Studio, silently relied upon rather than actually understood).
**Workaround:** a `phone-app-local-properties` Make target unconditionally (re)writes
`phone-app/local.properties` from the same `ANDROID_SDK_ROOT_WIN`/`_POSIX` this Makefile already
resolves for `install-tools-android-sdk`, and both `build-phone` and `test-phone` depend on it -
cheap enough (one line) to just always rewrite rather than track staleness.
**Where:** `Makefile` (root), the `phone-app-local-properties` target.

### `go run <relative-path>` refuses to cross a module boundary, even to a directory with its own go.mod
**Assumed:** `go run ../some/other/dir` (a plain filesystem path, not an import path) builds and
runs whatever package lives at that path, the same way it would if that path were a subdirectory
of the calling module.
**Actually:** when the *target* directory has its own `go.mod` (i.e. it's a different module, as
every `tools/*` module here deliberately is - see the "tools/ as separate modules" convention),
`go run`/`go build` refuse outright: `directory ...\tools\dbstore outside main module or its
selected dependencies`. This is true even for a path that's a perfectly valid, buildable module on
its own - the restriction is specifically about crossing a module boundary via a bare relative
path, and it bit both `controller/internal/dbstore/queries.gen.json`'s declared generator command
(`go run ../../../tools/dbstore ...`) and `tools/mock-phone`'s own documented usage from
`controller/README.md` (`go run ../tools/mock-phone/cmd/mockphone`) - the latter had apparently
never actually been run exactly as documented until this was diagnosed.
**Workaround:** a `go.work` file at the repo root listing every module (`controller`,
`tools/dbstore`, `tools/go-deps`, `tools/mock-phone`) makes `go run`/`go build` workspace-aware,
resolving relative paths across any module the workspace lists rather than just the calling
module's own tree. Committed (not gitignored, unlike most projects' `go.work`) since `make
generate` genuinely depends on it existing, not just as a per-developer convenience.
**Where:** `go.work` (root); `controller/internal/dbstore/queries.gen.json`;
`tools/dbstore/README.md`, `tools/go-deps/README.md`.

### A parse-time `$(shell go ...)` call doesn't see this make's own `export`s, even though every recipe does
**Assumed:** once a Makefile variable is `export`ed, every subsequent `$(shell ...)` call in that
same Makefile sees it in its environment - recipe-time or parse-time, no difference.
**Actually:** confirmed the opposite empirically: a `$(shell go ...)` call used to compute a target's
prerequisite list (i.e. evaluated while Make is still reading the Makefile, via `$(eval $(call
...))`) fails with the by-now-familiar `neither GOMODCACHE nor GOPATH is set`, even though the very
same `export GOPATH := ...` line reliably reaches every ordinary recipe body elsewhere in this same
file (extensively verified throughout the rest of this Makefile). A minimal repro nailed it down
further: `$(info $(shell echo $$GOPATH))` placed *after* the `export GOPATH := ...` line still
prints empty. Whatever this `make` does to apply `export` to a `$(shell)` call's environment, it
isn't available yet during the file's own top-to-bottom parse pass, only once recipes start running.
**Workaround:** don't rely on the `export` for a parse-time `$(shell ...)` call - pass the already-
computed Makefile variables explicitly as an inline environment prefix instead: `$(shell
GOPATH="$(GOPATH)" TMP="$(TMP)" TEMP="$(TEMP)" GOCACHE="$(GOCACHE)" go run ...)`. The values are
already known as ordinary Make variables regardless of whether their `export` has "taken" yet, so
this sidesteps the question entirely.
**Where:** `Makefile` (root), the `gen-stamp-rule` define (used to compute each `.gen.json.stamp`
target's prerequisites via `go-deps get`).

### Piping a real command's output into `awk` inside a recipe's `$$(...)` can break, same as `head`
**Assumed:** the earlier-documented "piping a glob through `head` inside `$(shell)` intermittently
breaks" quirk (see `panopticon-prototype/QUIRKS.md`) was specific to `$(shell)` (Makefile-parse-time
execution) and to `head`.
**Actually:** hit the same failure mode (`awk: ... fatal: error reading input file '-': Broken
pipe`) from a completely ordinary *recipe* (build-time, not parse-time) piping real `adb devices`
output into `awk` via `count=$$("$(ADB)" devices | awk '...')` - neither `$(shell)` nor `head` were
involved this time, just this `make`'s pipe handling in general being unreliable under this
specific MSYS2 build.
**Workaround:** avoid the pipe entirely - redirect the producer's output to a temp file, then have
the consumer read that file instead of stdin: `"$(ADB)" devices > "$$devices_list"; count=$$(awk
'...' "$$devices_list")`. Slightly more verbose, but never touches a pipe, so there's nothing left
for this bug to trigger on.
**Where:** `Makefile` (root), the `check-adb-devices` target.
