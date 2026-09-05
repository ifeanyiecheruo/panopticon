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
  mode (`/api/mode` - `live` is a stub), and clip sync (`/api/clips`, `.../file`,
  `.../thumbnail`, `DELETE .../:filename`). Every route except `POST /api/pair` requires
  `Authorization: Bearer <token>`.
- **Compose UI** - Home (device identity, storage, recording status), Connect (generate an
  invite code + show this phone's LAN address), Gallery (list clips, play via the system video
  viewer, delete).

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
- **Full `CameraCaptureSession` teardown+recreate every rotation** rather than a lighter in-place
  surface swap - simpler to reason about, doubles as a session-reconfigure stress test.
- **Bottom nav bar instead of the mock's left icon rail + top status pill** - visual language
  (dark/teal theme, `ui/theme/Theme.kt`) carried over; exact chrome layout wasn't a priority for
  this slice.
- **Gallery playback via `Intent.ACTION_VIEW`** to the system video player, not an in-app
  ExoPlayer/VideoView.

## Explicitly out of scope for this slice (not started)

Calibration (`/api/calibration/*`), live HLS view (`/live/*`, `/api/live/*`), digital zoom /
manual Camera2 controls (`/api/camera/*`), multi-camera switching (`/api/cameras*`), the
Controllers/Configuration/Calibrate screens, QR-code invite display (code/URL are shown as plain
text, which is enough for manual entry).

## Build / install / run

Requires a JDK (17+) and the Android SDK. No system-wide Gradle install needed - the wrapper
(`gradlew`/`gradlew.bat`) is committed.

Build/install/run/test tasks live in the **root Makefile** (`../Makefile`), alongside the
controller's - run `make help` from the repo root for the full list. From there:

```
make build-phone     # ./gradlew assembleDebug
make install-phone   # + install onto ADB_SERIAL (default: this project's test Pixel 6)
make run-phone       # + grant camera/notification perms + launch MainActivity
make test-phone      # ./gradlew test
```

Override the target device: `make install-phone ADB_SERIAL=<serial>` (see `adb devices -l`).

To reach the HTTP API from your dev machine: `adb -s <serial> forward tcp:8080 tcp:8080`, then
`curl http://127.0.0.1:8080/api/device` (401 without a token - pair first via the app's Connect
tab, which shows an invite code/URL, then `POST http://127.0.0.1:8080/api/pair?invite=<code>`
with a JSON body `{"publicKey": "...", "name": "...", "kind": "..."}`).

See `../docs/QUIRKS.md` for Camera2/MediaRecorder/HTTP-server findings from building this, and
which of the old prototype's quirks were reconfirmed vs. only carried forward.
