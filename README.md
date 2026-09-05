# Panopticon

Panopticon repurposes old Android phones as standalone security cameras. A Kotlin app on the
phone previews its camera continuously and records to a local ring buffer capped at a fraction of
available storage — no cloud, no subscription. A Go/Wails desktop app on a separate client
machine pairs with one or more phones, syncs their recordings down incrementally, and serves a
tray-resident UI to browse everything synced.

Both halves are currently **thin vertical slices** — pair → record → sync a clip → view it,
cross-verified against real hardware — not the full product described in the design docs below.
See [`docs/implementation/HANDOFF-implementation.md`](docs/implementation/HANDOFF-implementation.md)
for what's built, what's deferred, and suggested next steps.

## Getting started

```
make install-tools   # one-time: node/npm/wails/vite privately under .local/ (see the Makefile)
make build            # build both apps
make test             # run both test suites
make help              # full task list
```

See [`phone-app/README.md`](phone-app/README.md) and [`controller/README.md`](controller/README.md)
for what each half actually does today.

## Documentation

- [`docs/implementation/HANDOFF-implementation.md`](docs/implementation/HANDOFF-implementation.md) —
  **start here** for current implementation status, what's deferred, and next steps.
- [`docs/implementation/phone-http-api.md`](docs/implementation/phone-http-api.md) — the HTTP
  contract phone-app and controller implement against.
- [`docs/implementation/HANDOFF-phone-ux.md`](docs/implementation/HANDOFF-phone-ux.md) /
  [`HANDOFF-controller-ux.md`](docs/implementation/HANDOFF-controller-ux.md) — the original UX/
  design-phase handoffs (superseded for status, still the design rationale for what isn't built
  yet).
- [`docs/QUIRKS.md`](docs/QUIRKS.md) — device, library, OS, and dev-tooling (this project's
  Windows/MSYS2 `make` setup especially) misbehavior discovered along the way, and the
  workarounds adopted for each. Read before assuming odd-looking code is a bug to "clean up."
- [`tools/dbstore/README.md`](tools/dbstore/README.md) / [`tools/go-deps/README.md`](tools/go-deps/README.md) —
  the sqlc+goose codegen tooling controller's SQL layer is built on.
