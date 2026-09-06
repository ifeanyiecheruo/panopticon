# Panopticon implementation handoff

Status as of 2026-09-06: both `phone-app/` and `controller/` exist as working, cross-verified
**thin vertical slices** — pair → record → sync a clip → view it, phone and controller talking
to each other over the real HTTP contract, tested against a real Pixel 6. This doc is the
starting point for whoever picks this up next (a fresh session, most likely) to build out the
remaining feature slices. Read this first; it links out rather than duplicating detail that
already lives elsewhere and would drift.

**Slices added since the initial handoff:**

- **Calibration (both sides), incl. the real empirical zoom probe.** phone-app's
  `CalibrationRunner` does a real per-camera / per-`StreamConfigurationMap`-size / per-zoom
  sweep — applies `CONTROL_ZOOM_RATIO` (API 30+) / `SCALER_CROP_REGION`, reads back the
  effective crop, tracks the active physical camera (optical→digital crossover), a
  brightness-normalised median-of-frames sharpness score, and — for an off-centre crop — both
  the `SCALER_CROP_REGION` metadata round-trip **and** whether the pixels actually shifted
  (`frameShifted`), so a HAL that echoes a crop it doesn't apply is caught. Derives
  `opticalRange` / `digitalRange` / `crossoverRatio` / `positionHonored` /
  `positionMetadataLiedRatios` / `qualityCollapseRatio` per camera. A new `standby` mode (RECORD
  is sticky) frees the camera for it. controller decodes the full per-resolution map, shows the
  per-camera summary in Phone detail (button disabled + reason while the phone is recording),
  and has `calibration.EffectiveRect` (requested zoom+centre → honoured crop).
  **Verified end to end on the Pixel 6 (lit scene) and the BLU G5:** ratio honouring across the
  full range; the optical→digital crossover at 1.15× on the Pixel 6 back camera (ultrawide→wide,
  via `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`); off-centre position honoured on the Pixel 6
  back, not on the front, neither lying about it in metadata; `qualityCollapseRatio ≈ 2.8×` on
  the Pixel 6 back vs. its declared 7× max. **Still open:** the controller-side **zoom-rect
  picker UI** that consumes `EffectiveRect` (deferred — it hangs off Live preview). See
  `docs/QUIRKS.md`'s "Calibration zoom probe" for the full device findings + the weak-HAL quirks the
  probe works around (API-gated-key `NoSuchFieldError`, session-recycle device disconnect,
  `ImageReader.close()` SIGSEGV race, front-camera control-interleaving readback corruption,
  metadata-only position check being unreliable).

- **Segments and clips.** A phone that's been recording for weeks accumulates thousands of ~10s
  files; nobody wants a gallery item per fragment. So the model splits in two: a **segment** is
  one file (what the code/API used to call a "clip"), and a **clip** is now a *contiguous run of
  segments* — the user-facing gallery item. The phone HTTP API is reworded to segments
  (`/api/segments…`, response key `"segments"`); grouping is a controller concern. The controller
  DB gains a `segments` table (one row per file, each with a `clip_id`) and a rebuilt `clips`
  group table (`active`/`trashed`/`purged` lifecycle + aggregate span/size/count); migration
  `002` copies existing rows over and a one-time `RegroupUnassignedSegments` backfill groups
  them. The syncer assigns each downloaded segment to the phone's open clip when it starts
  within `dbstore.GroupingGapMs` (**500ms**) of that clip's end, else opens a new clip. Both
  galleries (controller Preact + phone Compose) show one item per clip; the controller's
  `ClipPlayer` walks a clip's segments as a playlist and auto-advances to the next clip on end.
  Trash/restore/delete act on the whole clip. `regroup_test.go`, `SegmentGroupingTest.kt`, and
  reworked integration tests (`TestSyncLoop_GroupsContiguousSegments` / `_SplitsOnGap`) cover it.
  Verified against the real Pixel 6 archive: migration `002` + backfill on the existing
  `data/panopticon.db` collapsed its 15 contiguous segments into one clip.

- **Gapless recording, GPU texture fan-out, with pre-roll (phone-app only).** The recording
  pipeline is now `CameraGlPipeline` (was `CameraPipeline`). **One** camera stream feeds a
  `SurfaceTexture`; a GL thread samples that external-OES texture per frame and renders it twice
  — a small downscaled copy to an FBO for `glReadPixels` → frame-difference `MotionDetector`,
  *and* the full frame to an EGL window surface on `MediaCodec.createInputSurface()`. **The
  encoder runs continuously.** A motion-gated `MediaMuxer` is what writes to disk: an in-RAM
  **pre-roll ring** holds the last few seconds of encoded access units; when motion is seen, on
  the next keyframe a muxer opens, is primed from the ring back to ~`preRollMs` (default 3s)
  before the motion, and writes live from there. Rotation is a muxer swap at a keyframe (request
  a sync frame at the interval, roll on the next `BUFFER_FLAG_KEY_FRAME`) → consecutive segments
  contiguous; PTS rebased per segment, `createdAtMs` chained so `endMs[k] == createdAtMs[k+1]`.
  `trailerMs` after motion stops, the muxer finalises. **Why one stream:** `MediaRecorder` and
  `MediaCodec`-surface-input both need a camera session targeting `[analysis stream + video
  stream]`, which the **BLU G5's Unisoc SC9863A HAL rejects** (`sendRequestsBatch` → `-ENOSYS`,
  device errors out) — the earlier MediaRecorder→MediaCodec attempts and a "video-only burst"
  fallback are all superseded by this. **Verified on both devices:** a 10-min soak
  (`src/debug/GlSoakTest.kt`) on the BLU ran clean (24 fps camera==rendered==encoded, 0 dropped
  frames, 59 muxer rotations, 0 GL errors, flat memory), and the real pipeline records gapless
  (`start[k+1] - start[k] - dur[k]` within ~8ms BLU / ~1ms Pixel), with pre-roll, motion-gated,
  on both. With rotation gapless, `GroupingGapMs` / `SegmentGrouping.GAP_MS` are **500ms**.

- **SegmentStore in-memory index / Gallery delete performance (phone-app only).** The segment
  index is one JSON blob in SharedPreferences (dir/keys still literally say "clip" — see
  `phone-app/README.md`). The old code re-parsed it on every read and re-serialised + rewrote the
  whole blob on every add/delete, on the main thread; with a clip now spanning many segments,
  deleting a few clips back to back stacked enough full-blob round-trips + regroups to ANR the
  BLU G5 and OOM from repeated large allocations (the Pixel 6 hit the same wall with more
  deletes). Now: `SegmentStore` holds the parsed index in memory as the authoritative copy, reads
  hit it directly, mutations update the map and schedule **one coalesced background flush**;
  `deleteAll(filenames)` does N map/file removals + one flush regardless of N; `GalleryScreen`
  loads via `LaunchedEffect` and deletes on `Dispatchers.IO` behind a busy flag that no-ops
  re-entrant taps. A hard kill losing an unflushed mutation is harmless — `reconcile()` on next
  launch drops entries whose file is gone and re-probes untracked files. Verified on the BLU G5
  (delete a 35-segment clip, then hammer delete 8× — no skipped frames, no ANR, no crash).

- **Motion-gated recording (phone-app only).** The always-record pipeline is gone.
  `CameraGlPipeline` runs `motion/MotionDetector` (frame-difference on a 32×24 luma grid) off
  the GL readback and gates the muxer on it, with a trailer tail (and now pre-roll — see above).
  `RecordingStatus.IDLE` now means "armed", so a phone with nothing moving reports
  `status: "idle"` on `/api/status` and shows as **Standby** on the controller's Fleet (which
  already handled non-recording that way — no controller change needed). Both `MotionDetector`
  and `RecordingPhaseController` have JVM unit tests. **Not yet verified on the Pixel 6** — the
  per-sensitivity thresholds are reasoned starting points; tuning against real lighting (and any
  move to a real background-subtraction model) is the follow-up. Pre-roll is wired (the pre-roll
  ring in `CameraGlPipeline`).

- **Live HLS view — plain HLS (both sides).** `live` mode is no longer a stub. **phone-app:** a
  dedicated `camera/LivePipeline.kt` runs a *single* camera stream straight into a `MediaCodec`
  encoder input surface (no GL — live does no motion analysis, so the BLU G5 two-stream problem
  doesn't arise), drained to `camera/LiveHlsRelay.kt` which produces a rolling **plain-HLS**
  playlist (whole ~1s `.ts` segments, 16-deep window ≈ 16s DVR, `#EXT-X-START:-4`) + `live-<n>.ts`
  files in cache. Keyframes are pinned to ~1s by `KEY_I_FRAME_INTERVAL=1` **plus** an explicit
  `REQUEST_SYNC_FRAME` timer (the hint alone isn't tight enough on this camera→encoder-surface
  path). `MediaMuxer` can't emit MPEG-TS, so the muxer is hand-rolled: `camera/ts/TsMuxer.kt`,
  ported verbatim from the prototype, with `TsMuxerTest` guarding the 188-byte-packet invariant.
  Entering `live` arms the pipeline idle (camera warm, nothing encoding); `POST /api/live/start`
  begins broadcasting; a 15s no-`GET` watchdog returns it to armed-idle. `http/routes/LiveRoutes.kt`
  serves `/api/live/start|stop` + `/live/live.m3u8` + `/live/live-<n>.ts`, all behind the normal
  bearer token. RECORD and LIVE stay mutually exclusive (deliberate — low-end phones don't
  multi-task encoders well). **controller:** `liveproxy.go` proxies `/live/<phoneID>/*` from the
  phone with the stored token (hls.js in the webview fetches same-origin, token stays
  server-side); `App.StartLivePreview`/`StopLivePreview` move the phone into/out of `live` and
  remember the prior mode; `frontend/src/components/LivePreview.tsx` is an hls.js `<video>` in
  Phone detail with a Watch/Stop button (disabled + reason while the phone records, mirroring
  calibration). `LivePreview.tsx` also carries real hls.js live config + a stall watchdog
  (`hls.startLoad()` + seek-to-`liveSyncPosition` on stalled progress / buffer-stall / fatal
  network error) — the minimal subset of the prototype's hls.js workarounds that plain HLS needs
  to not spiral into permanent rebuffering. `hls.js` (1.7.2, Apache-2.0) is **vendored** under
  `controller/frontend/src/vendor/hlsjs/` (full + minified ESM + `.d.ts` + LICENSE + update
  steps), not an npm dependency — the frontend build does no registry fetch for it.
  **Verified end to end:** the `DebugLiveReceiver` probe confirmed valid `mpegts`/`h264` output
  on both devices; then through a real `wails dev` controller (hls.js in the webview) the Pixel 6
  played 2.5+ min continuously (~50 segments, zero 404s, no stalls) and the BLU produced regular
  ~0.96s segments at real time. **Deferred:** LL-HLS + the rest of its hls.js latency workarounds
  (catalogued in `docs/QUIRKS.md`), adaptive bitrate, live resolution changes, a scoped `/live/*`
  token.

## Where things live

Single git repo (`panopticon/`, monorepo — see "Explicit decisions" below), plain linear
history, no submodules:

```
phone-app/          Android app (Kotlin/Compose) - runs on the phone acting as a camera
controller/          Desktop app (Go/Wails + Preact/TS frontend) - the client machine's UI
tools/dbstore/       sqlc+goose codegen CLI (shared infra, see below)
tools/go-deps/       *.gen.json -> Make .stamp plumbing (shared infra, see below)
tools/mock-phone/    throwaway HTTP server standing in for a phone-app, for controller dev/tests
docs/                design docs (this file's directory), QUIRKS.md, storyboards, UX mocks
Makefile             single entry point for build/install/run/test/generate - `make help`
go.work              ties controller/ and tools/*'s separate Go modules together (required -
                     see docs/QUIRKS.md's "go run refuses to cross a module boundary" entry)
```

`panopticon-prototype/` (a sibling directory, *not* inside this repo) is an earlier, abandoned
attempt at this same product on a different stack (Node/TS server + Kotlin phone app). Its
`QUIRKS.md`/`ARCHITECTURE.md` were mined for lessons (see `docs/QUIRKS.md`'s "Carried forward"
section) but none of its code was reused.

## What's built and verified

- **phone-app**: `PanopticonService` (foreground service) runs `CameraGlPipeline` (Camera2 →
  one `SurfaceTexture` → GPU fan-out to motion analysis + a continuous `MediaCodec` encoder →
  motion-gated `MediaMuxer` with pre-roll) in `record` mode, or `LivePipeline` (single stream →
  encoder → hand-rolled MPEG-TS → plain-HLS relay) in `live` mode, plus an embedded Ktor HTTP
  server implementing the pairing/device/status/config/mode/**segments**/**live** subset of
  `phone-http-api.md`. Compose UI: Home, Connect, Gallery. See `phone-app/README.md` for the full
  "what's here" / "what's deferred" breakdown.
- **controller**: Go/Wails tray app with embedded SQLite (sqlc+goose managed, see below),
  pairing (Add-phone), a background sync loop, plain-HLS live preview (a `/live/<phoneID>/*`
  proxy + an hls.js `<video>` in Phone detail), Fleet/Phone-detail/Gallery/Trash screens in
  TypeScript+JSX on Preact. See `controller/README.md` for the same breakdown.
- **Cross-verified together**, not just independently: the controller has actually paired with
  the real Pixel 6, synced real clips from it, and self-unpaired — this is real interop, not two
  slices built in isolation against a shared paper spec.
- **Tooling**: `make install-tools` sets up node/npm (nvm-pinned via `.nvmrc`), `wails`, and
  `vite` privately under `.local/` (doesn't touch anything machine-wide except the Android SDK,
  which is deliberately left machine-wide — see the Makefile's own comments). `make generate`
  regenerates sqlc/goose code for `controller/internal/dbstore`, stamp-tracked so it only reruns
  when `schemas/db/*.sql` or `queries/*.sql` actually change; `build-controller`/`test-controller`
  depend on it so this is invisible in normal use.

Run `make help` from the repo root for the full task list. `make e2e` runs both apps together
for a full manual session (needs a phone connected and paired first).

## Dev environment notes for whoever picks this up

- This is developed on **Windows**, with a **devkitPro-bundled MSYS2 `make`** that has a long
  list of confirmed quirks (env vars stripped from spawned processes, its own `$(CURDIR)` and
  `command -v` resolving through a different mount alias than recipe shells actually use, pipes
  into `head`/`awk` intermittently breaking, `export` not reaching parse-time `$(shell)` calls,
  and more) — **read `docs/QUIRKS.md` before assuming odd-looking Makefile code is over-engineered
  or wrong.** Every entry there is a real, reproduced failure with a workaround, not speculative
  hardening.
- A Pixel 6 (adb serial varies by machine/USB port — don't hardcode it, `check-adb-devices` in
  the Makefile handles multi-device disambiguation) is the primary test device this was built and
  verified against. `adb devices` occasionally reports a physically-connected device as
  `offline` — `adb kill-server && adb start-server` fixes it (see `docs/QUIRKS.md`).
- The controller binary occasionally fails its **very first** launch right after a fresh build
  with a WebView2 `80080005: Server execution failed` error, then launches fine on retry —
  observed to be a transient race (not reproduced on a second attempt in the same session), not
  a real regression. Don't chase this unless it starts happening consistently.
- `controller/internal/appdirs` resolves `data/`/`archive/` relative to the **current working
  directory at launch**, not a stable per-OS app-data path — meaning running the binary from the
  wrong cwd (e.g. via the root Makefile's `run-controller`, which launches from the repo root)
  creates a stray `data/`/`archive/` at the repo root instead of under `controller/`. Harmless
  (gitignored) but worth cleaning up (`rm -rf data archive` at repo root) if `git status` looks
  dirty after testing. Fixing this properly (`os.UserConfigDir()` or similar) is listed as
  deferred work in `controller/README.md`.

## What's deferred — and what needs BOTH sides

Each project's own README has the authoritative, maintained list (`phone-app/README.md`'s
"Explicitly out of scope for this slice", `controller/README.md`'s "What's deferred") — check
those directly rather than trusting a copy here, since they'll be kept current and this doc won't.

Worth calling out specifically: several deferred features require coordinated work **on both
phone-app and controller together**, not just one side in isolation — these are natural
candidates for "the next slice":

- ~~**Calibration.**~~ **Implemented** (both sides), including the real empirical zoom probe —
  see "Slices added since the initial handoff" above. What remains: the Pixel 6 verification run
  and the controller-side zoom-rect picker UI.
- ~~**Live HLS view.**~~ **Implemented as plain HLS** (both sides) — see "Slices added since the
  initial handoff" below. Deferred within it: LL-HLS (`EXT-X-PART`/parts + the hls.js latency
  workarounds the prototype paid for, catalogued in `docs/QUIRKS.md`), adaptive bitrate, live
  resolution changes, a scoped `/live/*` token, and a sustained on-device verification run.
- **Manual Camera2 controls / digital zoom.** `/api/camera/*` routes don't exist on phone-app;
  controller's Phone-detail has no adjuster UI. The old prototype's `QUIRKS.md` has extensive,
  hard-won findings here (`SCALER_CROP_REGION` not honoring position, digital zoom quality
  collapsing below the declared max, etc.) that are very likely still relevant on real hardware —
  re-verify against the Pixel 6 rather than assuming, same approach this session took for the
  quirks that *were* re-verified (see `docs/QUIRKS.md`'s "Reconfirmed on Pixel 6" section for the
  pattern to follow).
- ~~**Motion-gated recording.**~~ **Implemented** (phone-app only) — see "Slices added since the
  initial handoff" above. Remaining: on-device threshold tuning, a real
  background-subtraction model.

- **Unpair / force-unpair (controller only).** `internal/unpair` + `App.UnpairPhone` /
  `App.ForceUnpairPhone` + a Phone-detail "Unpair" section. Safe unpair does the
  unsynced-clips warning and refuses to drop local state unless the phone is reachable and the
  token actually revoked; force unpair drops it regardless. Integration-tested. No phone-app
  change (`DELETE /api/pair` already existed).
- **Multi-camera.** `/api/cameras*` doesn't exist; nothing controller-side switches cameras.

Remaining single-side deferred items (bulk arm/stand-down, eviction-probe/tombstone
cleanup, QR pairing, the Controllers/Configuration screens, etc.) are listed in each project's
own README and don't need cross-project design work — just implementation. (Unpair/force-unpair
is done — see above.)

## Explicit decisions made this session (worth not re-litigating)

- **Monorepo, plain linear history** — phone-app and controller started as separate git repos
  (built by parallel background agents against the shared API contract), then were combined via
  `git filter-repo` + cherry-pick into one repo with every original commit's author/date/message
  preserved and no synthetic merge commits. `git subtree` was tried first and explicitly rejected
  in favor of this approach.
- **`tools/` as separate Go modules**, not packages inside `controller/`'s own module — `mock-phone`,
  `dbstore`, and `go-deps` each have their own `go.mod`. This is *why* `go.work` is required (see
  docs/QUIRKS.md) — a tradeoff accepted deliberately for reusability across any future Go module
  in this repo, not just controller's.
- **sqlc + goose, ported from `../morsel`** (a sibling, unrelated project) as the template for
  SQL codegen + migrations, via the new `tools/dbstore`/`tools/go-deps` — see
  `controller/internal/dbstore/README.md` for the hand-written/generated split and
  `tools/dbstore/README.md` / `tools/go-deps/README.md` for how the tools themselves work.
- **Generated code lives in a `generated/` directory**, not alongside hand-written source, and
  where a per-file `.gen.ts`-style suffix wasn't achievable (Wails' bindings generator has no such
  option) a dedicated directory was used instead — see `controller/frontend`'s
  `generated/wailsjs/` (via `wailsjsdir` in `wails.json`) and `controller/internal/dbstore/queries/`
  (sqlc output, `.sql.go` files carry a `// Code generated by sqlc. DO NOT EDIT.` header).
- **Private per-project tool isolation** (`make install-tools`) for everything except the Android
  SDK, which stays machine-wide deliberately (too large to duplicate per-project for no real
  isolation benefit) — see the Makefile's own "## Tooling" section comments for the full reasoning.
- **`controller/build/` renamed to `controller/installer-src/`** — Wails' installer/packaging
  scaffolding (NSIS script, platform manifests, app icon) plus compiled output, made explicit via
  `wails.json`'s `build:dir` key rather than left as an unlabeled generic `build/`.

## Suggested next steps

No hard ordering. **Calibration**, **motion-gated recording**, and **live HLS view** are all
done as slices (above); their remaining pieces are noted there.

Largest remaining pieces:
- **Manual Camera2 controls / digital zoom** (`/api/camera/*`, plus the controller-side zoom-rect
  picker that consumes `calibration.EffectiveRect`) — re-verify the prototype's
  `SCALER_CROP_REGION`/digital-zoom quirks against real hardware first.
- **LL-HLS upgrade for live view** if the plain-HLS latency (~6–10s) proves too high — the
  prototype's `EXT-X-PART` machinery and its hls.js latency workarounds are catalogued in
  `docs/QUIRKS.md`'s carried-forward section, ready to adopt.
- **Multi-camera** (`/api/cameras*`) and the eviction-probe/tombstone-cleanup loop.

Still open on shipped slices: on-device motion-threshold tuning (Pixel 6). Live view is verified
end to end (phone → `liveproxy.go` → hls.js in a real `wails dev` webview) on the Pixel 6 and
BLU G5; an LL-HLS upgrade (for lower than the current ~4–6s latency) stays deferred with its
recipe in `docs/QUIRKS.md`.
