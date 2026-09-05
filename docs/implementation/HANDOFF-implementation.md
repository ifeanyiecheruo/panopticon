# Panopticon implementation handoff

Status as of 2026-09-05: both `phone-app/` and `controller/` exist as working, cross-verified
**thin vertical slices** — pair → record → sync a clip → view it, phone and controller talking
to each other over the real HTTP contract, tested against a real Pixel 6. This doc is the
starting point for whoever picks this up next (a fresh session, most likely) to build out the
remaining feature slices. Read this first; it links out rather than duplicating detail that
already lives elsewhere and would drift.

**Slices added since the initial handoff:**

- **Calibration (both sides).** phone-app has the full `/api/calibration/*` route set + a
  `CalibrationRunner` state machine (device-wide sweep, per-camera/step/within-step progress,
  cancellation, on-disk last-result) + a Calibrate screen. controller has the
  manufacturer+model `calibration` store, opportunistic ingest on pair / Phone-detail open,
  and a Phone-detail Calibration section with a Run/Re-run action. **Deferred within this
  slice:** the empirical measure-vs-declared probe — checks currently snapshot declared
  `CameraCharacteristics` and always pass (see `phone-app/README.md`'s "Deliberate
  simplifications"). That deepening is the natural next calibration pass and has the old
  prototype's `SCALER_CROP_REGION`/digital-zoom `QUIRKS.md` findings to re-verify against.

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

- ~~**Calibration.**~~ **Implemented** (both sides) — see "Slices added since the initial
  handoff" above. What remains is the empirical measure-vs-declared probe, called out there.
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
- **Motion-gated recording.** phone-app currently always records; the real
  `RecordingPhaseController`-style state machine (motion-start/trailer/motion-stop) doesn't exist.
  This is phone-app-only work, but changes what `POST /api/mode` and the recording pipeline
  actually do, so double-check nothing on the controller side assumes "always recording."
- **Multi-camera.** `/api/cameras*` doesn't exist; nothing controller-side switches cameras.

Single-side deferred items (unpair/force-unpair, bulk arm/stand-down, eviction-probe/tombstone
cleanup, QR pairing, the Controllers/Configuration screens, etc.) are listed in each project's
own README and don't need cross-project design work — just implementation.

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

No hard ordering. **Calibration** is done as a slice (above); its remaining piece is the
empirical measure-vs-declared probe. **Motion-gated recording** is phone-app-only
and unblocks removing the biggest "deliberate simplification" flagged in that project's README.
Live HLS view is almost certainly the largest single piece of remaining work (real-time muxing,
adaptive bitrate, hls.js integration on the controller frontend) — worth its own dedicated design
pass before implementation, mining the old prototype's `ARCHITECTURE.md`/`QUIRKS.md` for the
concurrency and HAL-quirk lessons it already paid for.
