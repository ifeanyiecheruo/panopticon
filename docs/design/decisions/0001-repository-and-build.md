# 0001 — Repository, modules, and build infrastructure

**Status:** Accepted · implemented.

## Context

Two applications on unrelated stacks (Android/Kotlin/Gradle, Go/Wails/Vite) plus codegen
tooling, developed partly by parallel agents, on a Windows dev machine whose MSYS2 `make` has a
long list of environment/path quirks (see [`../../quirks/dev-tooling-windows.md`](../../quirks/dev-tooling-windows.md)).
The build had to be drivable from one place regardless of stack.

## Decision

### Monorepo with plain linear history

`phone-app/` and `controller/` began as separate git repos and were combined via `git
filter-repo` + cherry-pick, preserving every original commit's author/date/message with **no
synthetic merge commits**. `git subtree` was tried first and explicitly rejected.

### `tools/` as separate Go modules

`tools/mock-phone`, `tools/dbstore`, `tools/go-deps` each have their own `go.mod`, not packages
inside `controller/`'s module. A repo-root `go.work` ties them together and is **committed** (not
gitignored) because `make generate` depends on it — `go run ../..` across a module boundary fails
without it.

### SQL codegen: sqlc + goose, pure-Go SQLite

Ported from the sibling `../morsel` project as the template. The controller uses the pure-Go
`modernc.org/sqlite` driver — no CGO, no gcc toolchain — matching the prototype's `node:sqlite`
reasoning. Migrations are goose (`internal/dbstore/schemas/db/*.sql`, auto-applied on `Open()`);
query code is sqlc-generated (`queries/*.sql` → `queries/*.sql.go`).

### Generated code in dedicated locations

Never interleaved with hand-written source. `controller/frontend/generated/wailsjs/` (via
`wailsjsdir` in `wails.json`), `controller/internal/dbstore/queries/*.sql.go` (sqlc, with a
`DO NOT EDIT` header).

### Private per-project tool isolation

`make install-tools` installs node/npm (nvm-pinned via `.nvmrc`), `wails`, `vite` under
`.local/`, touching nothing machine-wide — **except the Android SDK**, left machine-wide
deliberately (too large to duplicate per-project for no isolation benefit).

### `controller/build/` → `controller/installer-src/`

Wails packaging scaffolding (NSIS, manifests, icon) named explicitly via `wails.json`'s
`build:dir` rather than an unlabeled generic `build/`.

### The root `Makefile` is the single entry point

`make help` lists everything; `make e2e` runs both apps together. `build`/`test` targets depend
on `generate` so codegen is invisible in normal use.

## Consequences

- One `git log`, one `make`, no submodule dance.
- `go.work` must stay in sync with the module list; a new `tools/*` module means editing it.
- The Windows `make` quirks are load-bearing workarounds — see the quirks doc before "fixing"
  Makefile code that looks over-engineered.
- Contributors need the Android SDK installed machine-wide; everything else is `make
  install-tools`. See [`../../../CONTRIBUTING.md`](../../../CONTRIBUTING.md).
