# panopticon phone-app

Android half of Panopticon - turns a spare phone into a standalone security camera. This is a
**thin vertical slice** ("pair -> record -> sync a clip -> view it"), not the full app described
in `../docs/implementation/HANDOFF-phone-ux.md` and `../docs/implementation/phone-http-api.md`.
See those docs (and `../docs/design/ux-mocks/phone-ux-mock.html`) for the full intended design.

## What's here

- **`PanopticonService`** - foreground, `START_STICKY` service. Opens the back camera via
  Camera2, runs a **motion-gated** recording pipeline (rotating ~10s H.264 clips via
  `MediaRecorder`, written only while motion is present plus a short tail), and runs an embedded
  Ktor/Netty HTTP server.
- **Motion gate** - an always-on analysis stream (small YUV `ImageReader`) feeds a
  frame-difference `MotionDetector`; its verdict drives a `RecordingPhaseController` state
  machine (ARMED &lt;-&gt; RECORDING, with a trailer tail after motion stops). `motionSensitivity`
  from `/api/config` picks the threshold and takes effect on the next idle period. See the
  simplification note below for what the detector does and doesn't handle.
- **HTTP API** - implements a subset of `phone-http-api.md`: pairing (`POST`/`DELETE /api/pair`),
  device identity/status/config (`/api/device`, `/api/build-info`, `/api/status`, `/api/config`),
  mode (`/api/mode` - `live` is a stub), clip sync (`/api/clips`, `.../file`, `.../thumbnail`,
  `DELETE .../:filename`), and device-wide calibration (`POST /api/calibration/start`,
  `GET /api/calibration/status`, `DELETE /api/calibration/:runId`, `GET /api/calibration/result`).
  Every route except `POST /api/pair` requires `Authorization: Bearer <token>`.
- **Calibration** - `CalibrationRunner` runs a real **empirical zoom probe**: for every camera,
  at every `StreamConfigurationMap` output size, it applies a geometric range of zoom requests
  (`CONTROL_ZOOM_RATIO` on API 30+, `SCALER_CROP_REGION` on every API) and records what the HAL
  actually did - the effective crop rect read back, whether the requested ratio/position was
  honoured, which physical camera answered (optical→digital crossover), and a frame-sharpness
  score (variance of Laplacian). Per camera it derives `opticalRange` / `digitalRange` /
  `crossoverRatio` / `positionHonored` / `qualityCollapseRatio`. Cancellable, resilient to a
  weak HAL dropping the device mid-sweep, last result persisted to disk. See
  `calibration/ZoomMath.kt` for the pure geometry/metric helpers.
- **Mode** - `record` (motion-gated pipeline), `standby` (camera released), `live` (stub).
  `record` is sticky: calibration and live preview only run from `standby`, which must be
  entered explicitly (`POST /api/mode {"mode":"standby"}` or the Calibrate screen's "Stop
  recording").
- **Compose UI** - Home (device identity, storage, recording status), Connect (generate an
  invite code + show this phone's LAN address), Gallery (list clips, play via the system video
  viewer, delete), Calibrate (stop recording → run/re-run a sweep, live progress, per-camera
  optical/digital/position/quality readout).

## Deliberate simplifications (see code comments for exact locations)

- **Frame-difference motion only, thresholds un-tuned.** `MotionDetector` subsamples the luma
  plane on a 32x24 grid and counts cells whose brightness changed since the last frame - no
  background model, no CV library. It's knowingly naive about lighting steps (a light switching
  on trips it), auto-exposure/gain drift (a warmup guard + the per-cell delta threshold absorb
  small global shifts), and slow scene drift. The per-sensitivity thresholds in the code are
  starting points chosen by reasoning, **not measured against the Pixel 6 in real lighting** -
  that tuning (and any move to a real background-subtraction model) is follow-up work. Also **no
  pre-roll**: a clip starts at motion-detection time, since `MediaRecorder` can't back-date a
  buffer (pre-roll is tied to the future `MediaCodec`+`MediaMuxer` switch).
- **`live` mode is a stub.** `POST /api/mode {"mode":"live"}` flips the mode flag and tears down
  the recording pipeline (RECORD/LIVE stay mutually exclusive, per the architecture doc) but
  there's no real HLS encoder/relay behind it.
- **No audio track** - video only, avoids `RECORD_AUDIO` permission entirely.
- **`MediaRecorder`, not raw `MediaCodec`+`MediaMuxer`** - simpler for this slice; means no
  control over keyframe interval or explicit sync-frame requests (`MediaRecorder` doesn't expose
  either). See `docs/QUIRKS.md` for what this meant for reconfirming old keyframe-cadence findings.
- **Calibration sweep is long and rare.** Every output size × every camera × ~14 zoom steps,
  with a fresh `CameraDevice` per resolution (some HALs disconnect the device on plain session
  recycling - see `docs/QUIRKS.md`). Tens of minutes on a phone with many resolutions and
  cameras; it's meant to be run once, phone stood down. The `MAX_RESOLUTIONS_PER_CAMERA` cap is
  a safety net, not a normal limit.
- **Frame-sharpness metric needs a lit, textured target.** `qualityCollapseRatio`'s exact value
  isn't trustworthy without a resolution-chart run (see `docs/QUIRKS.md`); the softening trend
  is real. Verified end to end on the **Pixel 6** (API 36 - `CONTROL_ZOOM_RATIO` + logical
  multi-camera; probe located the ultrawide→wide handoff at 1.15x) and the **BLU G5** (API 28 -
  legacy `SCALER_CROP_REGION` path).
- **Debug-only `adb` calibration trigger.** `src/debug/…/DebugCalibrationReceiver` (declared in
  `src/debug/AndroidManifest.xml`, never in release) drives a sweep via
  `adb shell am broadcast` on devices whose Compose UI uiautomator/screencap can't touch.
- **Full `CameraCaptureSession` teardown+recreate on every clip rotation and every ARMED&lt;-&gt;RECORDING
  transition** rather than a lighter in-place surface swap - simpler to reason about, doubles as a
  session-reconfigure stress test. The analysis `ImageReader` persists across those; only the
  session and `MediaRecorder` churn.
- **Bottom nav bar instead of the mock's left icon rail + top status pill** - visual language
  (dark/teal theme, `ui/theme/Theme.kt`) carried over; exact chrome layout wasn't a priority for
  this slice.
- **Gallery playback via `Intent.ACTION_VIEW`** to the system video player, not an in-app
  ExoPlayer/VideoView.

## Explicitly out of scope for this slice (not started)

Live HLS view (`/live/...`, `/api/live/...`), digital zoom / manual Camera2 controls
(`/api/camera/...`), multi-camera switching (`/api/cameras`), the Controllers/Configuration
screens, QR-code invite display (code/URL are shown as plain text, which is enough for manual
entry). Calibration (routes, empirical zoom probe, Calibrate screen) is implemented; the
controller-side zoom-rect picker that consumes the effective-rect data is deferred (it's tied
to Live preview, which doesn't exist yet).

## Build / install / run

Requires a JDK (17+) and the Android SDK. No system-wide Gradle install needed - the wrapper
(`gradlew`/`gradlew.bat`) is committed.

Build/install/run/test tasks live in the **root Makefile** (`../Makefile`), alongside the
controller's - run `make help` from the repo root for the full list. From there:

```
make build-phone     # ./gradlew assembleDebug
make install-phone   # + install onto the connected device
make run-phone       # + launch MainActivity (app requests camera/notification perms itself)
make test-phone      # ./gradlew test
```

`run-phone` doesn't pre-grant permissions — `MainActivity` requests camera (and, on API 33+,
notification) access itself on first launch, same as a real user would see. Use `make
grant-phone`/`make revoke-phone` to pre-grant (skip that dialog while iterating on something
unrelated to permissions) or reset back to ungranted (to re-test the request/denial flow).

If more than one device is visible to `adb`, these fail with a list of devices and ask you to
target one explicitly: `make install-phone ADB_SERIAL=<serial>` (see `adb devices -l`).

To reach the HTTP API from your dev machine: `adb -s <serial> forward tcp:8080 tcp:8080`, then
`curl http://127.0.0.1:8080/api/device` (401 without a token - pair first via the app's Connect
tab, which shows an invite code/URL, then `POST http://127.0.0.1:8080/api/pair?invite=<code>`
with a JSON body `{"publicKey": "...", "name": "...", "kind": "..."}`).

See `../docs/QUIRKS.md` for Camera2/MediaRecorder/HTTP-server findings from building this, and
which of the old prototype's quirks were reconfirmed vs. only carried forward.
