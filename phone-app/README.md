# panopticon phone-app

Android half of Panopticon - turns a spare phone into a standalone security camera. This is a
**thin vertical slice** ("pair -> record -> sync a clip -> view it"), not the full app described
in `../docs/implementation/HANDOFF-phone-ux.md` and `../docs/implementation/phone-http-api.md`.
See those docs (and `../docs/design/ux-mocks/phone-ux-mock.html`) for the full intended design.

## What's here

- **`PanopticonService`** - foreground, `START_STICKY` service. Opens the back camera via
  Camera2, records continuously into rotating ~10s H.264 clips via `MediaRecorder`, and runs an
  embedded Ktor/Netty HTTP server.
- **HTTP API** - implements a subset of `phone-http-api.md`: pairing (`POST`/`DELETE /api/pair`),
  device identity/status/config (`/api/device`, `/api/build-info`, `/api/status`, `/api/config`),
  mode (`/api/mode` - `live` is a stub), clip sync (`/api/clips`, `.../file`, `.../thumbnail`,
  `DELETE .../:filename`), and device-wide calibration (`POST /api/calibration/start`,
  `GET /api/calibration/status`, `DELETE /api/calibration/:runId`, `GET /api/calibration/result`).
  Every route except `POST /api/pair` requires `Authorization: Bearer <token>`.
- **Calibration** - `CalibrationRunner` sweeps every camera `CameraManager` reports, walking a
  fixed ordered set of steps per camera with full camera/step/within-step progress, cancellation,
  and a last-result JSON persisted to disk (so `GET /api/calibration/result` answers after an app
  restart without re-running). See the simplification note below for what "measure" means here today.
- **Compose UI** - Home (device identity, storage, recording status), Connect (generate an
  invite code + show this phone's LAN address), Gallery (list clips, play via the system video
  viewer, delete), Calibrate (run/re-run a sweep, live progress, per-camera per-check breakdown).

## Deliberate simplifications (see code comments for exact locations)

- **No real motion detection.** The camera always records ("always motion" gate) - a real
  motion-gated `RecordingPhaseController`-style state machine is out of scope for this slice.
- **`live` mode is a stub.** `POST /api/mode {"mode":"live"}` flips the mode flag and tears down
  the recording pipeline (RECORD/LIVE stay mutually exclusive, per the architecture doc) but
  there's no real HLS encoder/relay behind it.
- **No audio track** - video only, avoids `RECORD_AUDIO` permission entirely.
- **`MediaRecorder`, not raw `MediaCodec`+`MediaMuxer`** - simpler for this slice; means no
  control over keyframe interval or explicit sync-frame requests (`MediaRecorder` doesn't expose
  either). See `docs/QUIRKS.md` for what this meant for reconfirming old keyframe-cadence findings.
- **Calibration records _declared_ Camera2 capabilities, not empirically measured ones.** Each
  check reads a `CameraCharacteristics` value and reports it as both `declared` and `measured`
  with `ok = true`; the run/step/progress state machine, persistence, cancellation and the whole
  wire contract are real. Opening a `CameraCaptureSession`, applying each control, and flagging
  where the HAL's effective value diverges from what it declared (the actual point of
  calibration) is deferred - it needs real-hardware iteration against the digital-zoom /
  `SCALER_CROP_REGION` findings in the old prototype's `QUIRKS.md`. Calibration does **not** tear
  down the recording pipeline (reading characteristics needs no exclusive camera access).
- **Full `CameraCaptureSession` teardown+recreate every rotation** rather than a lighter in-place
  surface swap - simpler to reason about, doubles as a session-reconfigure stress test.
- **Bottom nav bar instead of the mock's left icon rail + top status pill** - visual language
  (dark/teal theme, `ui/theme/Theme.kt`) carried over; exact chrome layout wasn't a priority for
  this slice.
- **Gallery playback via `Intent.ACTION_VIEW`** to the system video player, not an in-app
  ExoPlayer/VideoView.

## Explicitly out of scope for this slice (not started)

Live HLS view (`/live/...`, `/api/live/...`), digital zoom / manual Camera2 controls
(`/api/camera/...`), multi-camera switching (`/api/cameras`), the Controllers/Configuration
screens, QR-code invite display (code/URL are shown as plain text, which is enough for manual
entry). Calibration's routes + Calibrate screen exist now (see above) - what's out of scope is
the empirical measure-vs-declared probing, noted under "Deliberate simplifications".

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
