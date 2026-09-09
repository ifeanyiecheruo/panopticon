# Panopticon

Panopticon repurposes old Android phones as standalone security cameras.
## Getting started

```
make install-tools   # one-time install of development tools
make build           # build both phone app and desktop controller
make test            # run test suites
make help            # full task list
```

See [`phone-app/README.md`](phone-app/README.md) and [`controller/README.md`](controller/README.md)
for what each half  does.

## Documentation

- [`docs/implementation/HANDOFF-implementation.md`](docs/implementation/HANDOFF-implementation.md) —
  **start here** for current implementation status, what's deferred, and next steps.
- [`docs/implementation/phone-http-api.md`](docs/implementation/phone-http-api.md) — the HTTP
  contract phone-app and controller implement against.
- [`docs/implementation/HANDOFF-phone-ux.md`](docs/implementation/HANDOFF-phone-ux.md) /
  [`HANDOFF-controller-ux.md`](docs/implementation/HANDOFF-controller-ux.md) — the original UX/
  design-phase handoffs (superseded for status, still the design rationale for what isn't built
  yet).
- [`docs/quirks/`](docs/quirks/README.md) — device, library, OS, and dev-tooling (this project's
  Windows/MSYS2 `make` setup especially) misbehavior discovered along the way, and the
  workarounds adopted for each, split by domain (camera2, calibration/zoom, manual camera
  controls, live HLS, MPEG-TS, Android service, Windows dev tooling). Read before assuming
  odd-looking code is a bug to "clean up."
- [`tools/dbstore/README.md`](tools/dbstore/README.md) / [`tools/go-deps/README.md`](tools/go-deps/README.md) —
  the sqlc+goose codegen tooling controller's SQL layer is built on.
