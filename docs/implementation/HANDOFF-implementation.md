# Panopticon implementation handoff

Status as of 2026-09-05: both `phone-app/` and `controller/` exist as working, cross-verified
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
  **Verified end to end:** ratio honouring + the optical/digital crossover on the Pixel 6 (probe
  located the ultrawide→wide handoff at 1.15×) and the BLU G5. **Still open:** a Pixel 6 re-run
  with the current probe (the frame-content position check needs a wide-zoom device + a
  textured/lit scene — the Pixel keeps dropping off USB); and the controller-side **zoom-rect
  picker UI** that consumes `EffectiveRect` (deferred — it hangs off Live preview). See
  `docs/QUIRKS.md`'s "Calibration zoom probe" for the device findings + the weak-HAL quirks the
  probe works around (API-gated-key `NoSuchFieldError`, session-recycle device disconnect,
  `ImageReader.close()` SIGSEGV race, front-camera control-interleaving readback corruption,
  metadata-only position check being unreliable).

- **Motion-gated recording (phone-app only).** The always-record pipeline is gone. `CameraPipeline`
  now runs an always-on analysis `ImageReader` → `motion/MotionDetector` (frame-difference on a
  32×24 luma grid) → `motion/RecordingPhaseController` (ARMED ⇄ RECORDING with a trailer tail).
  `RecordingStatus.IDLE` now means "armed", so a phone with nothing moving reports
  `status: "idle"` on `/api/status` and shows as **Standby** on the controller's Fleet (which
  already handled non-recording that way — no controller change needed). Both `MotionDetector`
  and `RecordingPhaseController` have JVM unit tests. **Not yet verified on the Pixel 6** — the
  per-sensitivity thresholds are reasoned starting points; tuning against real lighting (and any
  move to a real background-subtraction model, plus pre-roll once the `MediaCodec`+`MediaMuxer`
  switch happens) is the follow-up.

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

- **phone-app**: `PanopticonService` (foreground service) runs a Camera2 + `MediaRecorder`
  pipeline recording rotating clips, plus an embedded Ktor HTTP server implementing the pairing/
  device/status/config/mode/clips subset of `phone-http-api.md`. Compose UI: Home, Connect,
  Gallery. See `phone-app/README.md` for the full "what's here" / "what's deferred" breakdown.
- **controller**: Go/Wails tray app with embedded SQLite (sqlc+goose managed, see below),
  pairing (Add-phone), a background sync loop, Fleet/Phone-detail/Gallery/Trash screens in
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
- **Live HLS view.** phone-app's `POST /api/mode {"mode":"live"}` is a stub (flips the mode flag,
  no real encoder/relay); controller has no live-preview UI. `ARCHITECTURE.md`-equivalent design
  detail for this doesn't exist yet in this project's own docs (the *old prototype* did build a
  full LL-HLS pipeline — see `panopticon-prototype/QUIRKS.md`'s HLS-related entries before
  re-deriving those lessons from scratch).
- **Manual Camera2 controls / digital zoom.** `/api/camera/*` routes don't exist on phone-app;
  controller's Phone-detail has no adjuster UI. The old prototype's `QUIRKS.md` has extensive,
  hard-won findings here (`SCALER_CROP_REGION` not honoring position, digital zoom quality
  collapsing below the declared max, etc.) that are very likely still relevant on real hardware —
  re-verify against the Pixel 6 rather than assuming, same approach this session took for the
  quirks that *were* re-verified (see `docs/QUIRKS.md`'s "Reconfirmed on Pixel 6" section for the
  pattern to follow).
- ~~**Motion-gated recording.**~~ **Implemented** (phone-app only) — see "Slices added since the
  initial handoff" above. Remaining: on-device threshold tuning, pre-roll, a real
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

No hard ordering. **Calibration** and **motion-gated recording** are both done as slices
(above); their remaining pieces are noted there.
Live HLS view is almost certainly the largest single piece of remaining work (real-time muxing,
adaptive bitrate, hls.js integration on the controller frontend) — worth its own dedicated design
pass before implementation, mining the old prototype's `ARCHITECTURE.md`/`QUIRKS.md` for the
concurrency and HAL-quirk lessons it already paid for.
