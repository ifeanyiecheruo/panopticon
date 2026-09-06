# panopticon phone-app

Android half of Panopticon - turns a spare phone into a standalone security camera. This is a
**thin vertical slice** ("pair -> record -> sync footage -> view it"), not the full app described
in `../docs/implementation/HANDOFF-phone-ux.md` and `../docs/implementation/phone-http-api.md`.
See those docs (and `../docs/design/ux-mocks/phone-ux-mock.html`) for the full intended design.

## What's here

- **`PanopticonService`** - foreground, `START_STICKY` service. Opens the back camera via
  Camera2, runs a **motion-gated** recording pipeline (`CameraGlPipeline`), and runs an embedded
  Ktor/Netty HTTP server.
- **Recording pipeline** (`CameraGlPipeline`) - **one** camera stream into a `SurfaceTexture`,
  fanned out on a GL thread (EGL recordable context, external-OES sampler). Each frame is
  rendered twice: downscaled into an FBO that `glReadPixels` pulls back for motion analysis, and
  full-size onto the input `Surface` of a persistent `MediaCodec` H.264 encoder
  (`eglPresentationTimeANDROID` carries the camera timestamp). The encoder runs **continuously**;
  a drain thread keeps a small in-RAM **pre-roll ring** of encoded access units. On motion a
  `MediaMuxer` opens from the ring at the keyframe covering `now - preRollMs` and writes forward,
  rotating ~10s *segments* gaplessly (sync-frame requested at the boundary, muxer swapped at the
  next keyframe with PTS rebased per segment); it closes a trailer tail after motion stops.
  Using a single stream + GL fan-out (rather than a separate analysis `ImageReader`) is what lets
  the pipeline run on HALs that reject two concurrent streams - see the note below and QUIRKS.md.
- **Motion gate** - the GL readback (a downscaled RGBA FBO, green channel taken as luma) feeds a
  frame-difference `MotionDetector`; its verdict gates the `MediaMuxer` (open on motion, hold for
  a trailer tail, then close). `motionSensitivity` from `/api/config` picks the threshold and is
  re-read periodically. See the simplification note below for what the detector does and doesn't
  handle.
- **HTTP API** - implements a subset of `phone-http-api.md`: pairing (`POST`/`DELETE /api/pair`),
  device identity/status/config (`/api/device`, `/api/build-info`, `/api/status`, `/api/config`),
  mode (`/api/mode`), segment sync (`/api/segments`, `.../file`, `.../thumbnail`,
  `DELETE .../:filename`), live view (`POST /api/live/start`, `DELETE /api/live/stop`,
  `GET /live/live.m3u8`, `GET /live/live-<n>.ts`), and device-wide calibration
  (`POST /api/calibration/start`, `GET /api/calibration/status`, `DELETE /api/calibration/:runId`,
  `GET /api/calibration/result`).
  Every route except `POST /api/pair` requires `Authorization: Bearer <token>`.
- **Calibration** - `CalibrationRunner` runs a real **empirical zoom probe**: for every camera,
  at every `StreamConfigurationMap` output size, it applies a geometric range of zoom requests
  (`CONTROL_ZOOM_RATIO` on API 30+, `SCALER_CROP_REGION` on every API) and records what the HAL
  actually did - the effective crop rect read back, whether the requested ratio was honoured,
  which physical camera answered (optical→digital crossover), a brightness-normalised
  median-of-frames sharpness score, and - for an off-centre crop - both the metadata round-trip
  *and* whether the pixels actually shifted (so a HAL that echoes a crop it doesn't apply is
  caught). Per camera it derives `opticalRange` / `digitalRange` / `crossoverRatio` /
  `positionHonored` / `positionMetadataLiedRatios` / `qualityCollapseRatio`. Cancellable,
  resilient to a weak HAL dropping the device mid-sweep, last result persisted to disk. See
  `calibration/ZoomMath.kt` for the pure geometry/metric helpers.
- **Mode** - `record` (motion-gated pipeline), `standby` (camera released), `live` (plain-HLS
  live preview - `camera/LivePipeline.kt` + `LiveHlsRelay.kt` + hand-rolled `camera/ts/TsMuxer.kt`,
  since `MediaMuxer` can't emit `.ts`). `record` is sticky: calibration and live preview only run
  from `standby`, which must be entered explicitly (`POST /api/mode {"mode":"standby"}` or the
  Calibrate screen's "Stop recording"). `live` is single-stream (camera straight into the encoder
  surface, no GL) and arms idle - `POST /api/live/start` begins broadcasting, a 15s inactivity
  watchdog stops it.
- **Compose UI** - Home (device identity, storage, recording status), Connect (generate an
  invite code + show this phone's LAN address), Gallery (list clips - contiguous segments grouped
  by time gap - play the run via the system video viewer, delete a whole clip), Calibrate (stop recording → run/re-run a sweep, live progress, per-camera
  optical/digital/position/quality readout).

## Deliberate simplifications (see code comments for exact locations)

- **Frame-difference motion only, thresholds un-tuned.** `MotionDetector` subsamples the luma
  plane on a 32x24 grid and counts cells whose brightness changed since the last frame - no
  background model, no CV library. It's knowingly naive about lighting steps (a light switching
  on trips it), auto-exposure/gain drift (a warmup guard + the per-cell delta threshold absorb
  small global shifts), and slow scene drift. The per-sensitivity thresholds in the code are
  starting points chosen by reasoning, **not measured against the Pixel 6 in real lighting** -
  that tuning (and any move to a real background-subtraction model) is follow-up work.
- **Live view is plain HLS, not LL-HLS.** ~1s `.ts` segments (keyframes pinned by
  `KEY_I_FRAME_INTERVAL=1` + an explicit sync-frame timer), a 16-deep window (~16s DVR),
  `#EXT-X-START:-4`; ~4–6s glass-to-glass. The deep window + the controller's hls.js config +
  stall watchdog are what keep it from spiralling into permanent rebuffering (see
  `docs/QUIRKS.md`). RECORD/LIVE stay mutually exclusive (one camera-using pipeline at a time -
  deliberate, low-end phones don't multi-task encode well). LL-HLS stays carried-forward.
  `/live/*` is served behind the normal bearer token (the prototype's scoped GET-only token is
  deferred - the controller proxies live server-side).
- **No audio track** - video only, avoids `RECORD_AUDIO` permission entirely.
- **Calibration sweep is long and rare.** Every output size × every camera × ~14 zoom steps,
  with a fresh `CameraDevice` per resolution (some HALs disconnect the device on plain session
  recycling - see `docs/QUIRKS.md`). Tens of minutes on a phone with many resolutions and
  cameras; it's meant to be run once, phone stood down. The `MAX_RESOLUTIONS_PER_CAMERA` cap is
  a safety net, not a normal limit.
- **Sharpness + position checks want a lit, textured scene** and, for position, a device with a
  wide zoom range - against a blank/dark scene `frameShifted` and `qualityCollapseRatio` go
  inconclusive. Verified against a lit scene on the **Pixel 6** (API 36): crossover at 1.15×,
  off-centre position honoured on the back camera / not the front (neither lies), digital-zoom
  softening from ~2.8× vs. a declared 7× max. **BLU G5** (API 28, legacy path): ratio/crop
  honouring clean; its 2.0× max is too small to judge position. See `docs/QUIRKS.md`.
- **Debug-only `adb` triggers.** `src/debug/…/DebugCalibrationReceiver` drives a calibration
  sweep, `src/debug/…/DebugGlSoakReceiver` runs the standalone `GlSoakTest` GL/encoder soak, and
  `src/debug/…/DebugLiveReceiver` runs a live-broadcast probe (enters `live`, broadcasts, dumps
  `.ts` segments + a PASS/FAIL verdict). All declared only in `src/debug/AndroidManifest.xml`,
  never in release; fire with `adb shell am broadcast -n com.panopticon.phoneapp/.debug.<Receiver>`
  on devices whose Compose UI uiautomator/screencap can't touch.
- **One capture session for the life of the pipeline.** The camera stream, the GL thread, and
  the encoder stay up whether or not motion is present; only the `MediaMuxer` opens and closes.
  Segment rotation is gapless (`start[k+1] == start[k] + dur[k]`, ~1ms on the Pixel 6 / ~8ms on
  the BLU G5) because one encoder runs untouched and the muxer is swapped at a keyframe with PTS
  rebased per segment. The earlier design used a second stream (a YUV analysis `ImageReader`)
  alongside the video stream; the BLU G5's Unisoc SC9863A HAL rejects two concurrent streams
  (`sendRequestsBatch: Function not implemented` → the device errors out), which is why analysis
  now rides the single stream through GL. A 10-minute BLU soak (`GlSoakTest`) held 24 fps with
  zero dropped frames, 59 muxer rotations, no GL errors and flat memory. See QUIRKS.md.
- **Bottom nav bar instead of the mock's left icon rail + top status pill** - visual language
  (dark/teal theme, `ui/theme/Theme.kt`) carried over; exact chrome layout wasn't a priority for
  this slice.
- **Gallery playback via `Intent.ACTION_VIEW`** to the system video player, not an in-app
  ExoPlayer/VideoView. It opens the clip's first segment; there's no in-Gallery segment-to-segment
  advance (the controller's player does that).
- **"Segment" is the code's word, but the on-disk names weren't churned.** `SegmentStore` writes
  to a directory still literally named `clips/`, backed by SharedPreferences `panopticon_clips` /
  `clips_index_json`, and each file keeps a `clip_` filename prefix. Renaming any of those on an
  app update would orphan every already-recorded file and the index, for zero behavioural gain.

## Explicitly out of scope for this slice (not started)

Digital zoom / manual Camera2 controls (`/api/camera/...`), multi-camera switching
(`/api/cameras`), the Controllers/Configuration screens, QR-code invite display (code/URL are
shown as plain text, which is enough for manual entry). Live view is implemented as **plain
HLS** — LL-HLS, adaptive bitrate, live resolution changes and a scoped `/live/*` token are all
deferred. Calibration (routes, empirical zoom probe, Calibrate screen) is implemented; the
controller-side zoom-rect picker that consumes the effective-rect data is deferred (it's tied
to a manual-controls UI that doesn't exist yet).

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

See `../docs/QUIRKS.md` for Camera2/MediaCodec/OpenGL/HTTP-server findings from building this,
and which of the old prototype's quirks were reconfirmed vs. only carried forward.
