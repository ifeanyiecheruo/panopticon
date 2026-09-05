# Quirks (panopticon phone-app)

This is a **new** project doc, separate from `panopticon-prototype/QUIRKS.md` (the old,
abandoned prototype's findings). Same format: what we assumed → what actually happens → what we
do about it → where. Entries under "Reconfirmed on Pixel 6" were directly re-verified against
real hardware while building this vertical slice (`phone-app/`). Entries under "Carried forward"
were adopted as defensive workarounds from the old doc without being independently re-tested here
- don't mistake them for confirmed-in-this-project.

Test device for everything below: **Google Pixel 6 (`oriole`), Android 16 (API 36), build
`BP2A.250605.031.A2`**, adb serial `1C281FDF6005H0`. Encoder observed in logcat:
`ExynosC2H264EncComponent` (Exynos hardware AVC encoder) - a different SoC family from the old
prototype's Pixel 9a (Tensor), which is itself a reason some findings below don't transfer
directly.

## Contents

- [Camera2 / recording pipeline](#camera2--recording-pipeline)
  - [Reconfirmed on Pixel 6](#reconfirmed-on-pixel-6)
  - [Carried forward, not yet re-verified in this project](#carried-forward-not-yet-re-verified-in-this-project)
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

### Carried forward, not yet re-verified in this project

Out of scope for this vertical slice (no manual Camera2 controls, no live HLS pipeline, no
calibration, no motion-gated recording, no multi-camera) - see
`panopticon-prototype/QUIRKS.md` for the original write-ups:

- `SCALER_CROP_REGION` position isn't honored, and the device lies about it
- Digital zoom quality collapses well below the declared max, invisible to crop-region metadata
- Crop readback is unreliable
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
- Two concurrent camera surfaces for record + motion sampling never configure (moot here - this
  slice has no motion-sampling surface at all, real motion detection is out of scope)
- CORS needs explicit header exposure for hls.js (adopted defensively in `PanopticonHttpServer.kt`
  for ranged clip downloads generally - `exposeHeader(Content-Range/Content-Length)` - but not
  verified against an actual browser `fetch()`/hls.js client, only via `curl`, since there's no
  live HLS pipeline in this slice to test it against)
- All `Browser / hls.js` and `Server / Node` sections (no live pipeline, no Node server in this
  project - the controller is a separate, not-yet-built project)

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
