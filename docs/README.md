# Panopticon documentation

| You want… | Go to |
|---|---|
| design intent and rationale — architecture, decisions, component specs, the HTTP contract, UX mocks | [`design/`](design/README.md) |
| current build status + what's left to build | [`status/README.md`](status/README.md) + the plans beside it |
| a device / library / toolchain misbehaviour and its workaround | [`quirks/`](quirks/) |
| how to build, run, and test the repo | [`../CONTRIBUTING.md`](../CONTRIBUTING.md) |

## Layout

```
docs/
  design/
    README.md              index for everything below
    architecture.md        system architecture description (42010) + diagrams + shared glossary
    decisions/             thematic architecture decision records (0001–00NN)
    components/            one IEEE 1016 design description per subsystem
    http-api.md            the phone HTTP API contract (authoritative)
    storyboards/           original screen sketches
    ux-mocks/              clickable HTML/CSS/JS UX mocks
  status/
    README.md              build/verify status snapshot + plan index
    *.md                   one plan per remaining work item
  quirks/                  reproduced device/library/OS/toolchain misbehaviour + workarounds
```

## Keeping it current

- Design intent changes → see [`design/README.md`](design/README.md)'s "Keeping it current".
- A piece of deferred work gets built → fold its plan's substance into the relevant component
  doc / ADR and reduce the plan to a "done in `<commit>`" line (or remove it and update the
  [status](status/README.md) table).
- A new reproduced quirk → [`quirks/`](quirks/), by domain.

This directory holds design intent and rationale. Module-level "how to build/run" lives in
[`../CONTRIBUTING.md`](../CONTRIBUTING.md); there are no per-application READMEs.
