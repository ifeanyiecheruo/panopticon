# Contributing to Panopticon

Panopticon is a monorepo with two applications on unrelated stacks plus shared build tooling.
Everything is driven from the **root `Makefile`** — `make help` lists every task.

```
phone-app/        Android app (Kotlin / Jetpack Compose) — the phone acting as a camera
controller/       Desktop app (Go / Wails + Preact/TS frontend) — the client machine's UI
tools/dbstore/    sqlc + goose codegen CLI (shared infra)
tools/go-deps/    *.gen.json → Make .stamp plumbing (shared infra)
tools/mock-phone/ throwaway HTTP server standing in for a phone-app, for controller dev/tests
docs/             architecture, decisions, specs, status & plans, quirks — see docs/README.md
Makefile          single entry point for build / install / run / test / generate
go.work           ties controller/ and tools/*'s separate Go modules together (required)
```

`panopticon-prototype/` (a sibling directory, **not** in this repo) is an earlier abandoned
attempt on a different stack. Its `QUIRKS.md` / `ARCHITECTURE.md` were mined for lessons; none of
its code was reused.

## Prerequisites

- **A JDK (17+)** and the **Android SDK**. The Android SDK is the one tool kept machine-wide
  (too large to duplicate per project for no isolation benefit); everything else is installed
  privately by `make install-tools`. No system-wide Gradle — the wrapper is committed.
- **Go** (a recent toolchain).
- `make install-tools` then installs node/npm (nvm-pinned via `.nvmrc`), `wails`, and `vite`
  under `.local/`, touching nothing else machine-wide.

```bash
make install-tools   # one-time
```

## Build, run, test

```bash
make build           # build both apps
make test            # run both test suites
make e2e             # run both apps together for a manual session (needs a phone paired first)
make help            # everything
```

### phone-app

```bash
make build-phone     # ./gradlew assembleDebug
make install-phone   # + install onto the connected device
make run-phone       # + launch MainActivity (the app requests camera/notification perms itself)
make test-phone      # ./gradlew test
```

`run-phone` doesn't pre-grant permissions — `MainActivity` requests them on first launch, same
as a real user sees. `make grant-phone` / `make revoke-phone` pre-grant or reset (to iterate on
something unrelated to permissions, or to re-test the request/denial flow).

If more than one device is visible to `adb`, device-targeted tasks fail with a device list and
ask you to pick one: `make install-phone ADB_SERIAL=<serial>` (`adb devices -l`).

To reach the phone's HTTP API from your dev machine:

```bash
adb -s <serial> forward tcp:8080 tcp:8080
curl http://127.0.0.1:8080/api/device      # 401 without a token
# pair first via the app's Connect tab (shows an invite code/URL), then:
curl -X POST 'http://127.0.0.1:8080/api/pair?invite=<code>' \
  -d '{"publicKey":"...","name":"...","kind":"..."}'
```

### controller

```bash
make build-controller     # wails build → installer-src/bin/panopticon-controller.exe
make run-controller       # build + launch (tray icon; keeps running in the background)
make test-controller      # go test ./...
```

For hot-reload dev mode (an interactive foreground process, not a Makefile task):

```bash
cd controller && wails dev
```

`wails dev` also serves `http://localhost:34115` for calling bound Go methods from an ordinary
browser tab — the main way the Go backend is exercised manually.

On first launch the controller creates `data/` (SQLite DB + single-instance lock) and
`archive/` (downloaded segments, one subdir per phone) **next to wherever the binary runs from**
— both gitignored, runtime state not source. (A stable per-OS location is
[planned](docs/status/controller-app-data-dir.md).) Closing the window hides it (sync
keeps running); only the tray's "Quit" exits. A second launch prints a message and exits.

### Testing the controller without a real phone

`tools/mock-phone` implements just enough of the API to exercise pairing + sync:

```bash
go run ./tools/mock-phone/cmd/mockphone -addr :8091 -invite TESTCODE1234 -segments 6
```

`-segments N` seeds N fake segments as two back-to-back runs split by a gap, so the controller's
segment→clip grouping collapses them into 2 gallery clips. `-live-arm-ms` (default 1500) mimics
the cold-camera start so the live-start retry path is testable.

`controller/internal/integrationtest` runs automated tests against an in-process fake server
(pairing with a real Ed25519 identity, error classification, download + cursor advancement,
grouping vs splitting on a gap, evicted-segment-is-a-normal-skip). `go test ./...`.

## Code generation

`make generate` regenerates sqlc/goose-derived code for `controller/internal/dbstore`,
**stamp-tracked** so it only reruns when `schemas/db/*.sql` or `queries/*.sql` actually change.
`build-controller` / `test-controller` depend on it, so it's invisible in normal use. To add a
migration or a query, see `controller/internal/dbstore/README.md` and
[`tools/dbstore/README.md`](tools/dbstore/README.md).

Generated code lives in dedicated locations, never interleaved with hand-written source:
`controller/frontend/generated/wailsjs/` and `controller/internal/dbstore/queries/*.sql.go`.

## Dev environment notes

- **Developed on Windows** with a **devkitPro-bundled MSYS2 `make`** that has a long list of
  confirmed quirks (env vars stripped from spawned processes; its own `$(CURDIR)` and
  `command -v` resolving through a different mount alias than recipe shells use; pipes into
  `head`/`awk` intermittently breaking; `export` not reaching parse-time `$(shell)` calls; and
  more). **Read [`docs/quirks/dev-tooling-windows.md`](docs/quirks/dev-tooling-windows.md)
  before assuming odd-looking Makefile code is over-engineered.** Every entry there is a real,
  reproduced failure with a workaround.
- `adb devices` occasionally reports a physically-connected device as `offline` —
  `adb kill-server && adb start-server` fixes it.
- The controller binary occasionally fails its **very first** launch after a fresh build with a
  WebView2 `80080005: Server execution failed`, then launches fine on retry — a known transient
  race, not a regression.
- Test devices this is built against: a **Google Pixel 6** (`oriole`, API 36) as primary, and a
  **BLU G5** (API 28) as a low-end / old-API check. `adb` serials vary by machine/USB port —
  don't hardcode them; `check-adb-devices` in the Makefile handles disambiguation.

## Repository conventions

- **Linear history, no merge commits.** The two apps were combined with `git filter-repo` +
  cherry-pick preserving every original commit; keep it that way.
- **Commit or push only when asked.** If you're on the default branch, branch first.
- **`go.work` is committed** and must list every `tools/*` module — a new module means editing
  it, or `make generate` breaks.
- **Don't "clean up" code that looks over-engineered** until you've checked
  [`docs/quirks/`](docs/quirks/) — much of the odd-looking Makefile and Camera2 code is a
  documented workaround.

## Where documentation goes

See [`docs/README.md`](docs/README.md) for the full map. In short:

| Change | Doc to touch |
|---|---|
| new cross-cutting design decision | [`docs/design/decisions/`](docs/design/decisions/) |
| a subsystem changes shape | its [`docs/design/components/`](docs/design/components/) file |
| the wire contract changes | [`docs/design/http-api.md`](docs/design/http-api.md) (authoritative — change it first) |
| deferred work gets built | fold the plan into the relevant component doc / ADR, reduce [`docs/status/`](docs/status/)'s plan to a "done" line |
| a new reproduced device/toolchain quirk | [`docs/quirks/`](docs/quirks/), by domain |
