# controller — state store — software design description

## 1. Introduction

### 1.1 Purpose

Describe the component that is the controller's single local source of truth: one embedded
SQLite DB holding identity, paired phones, the segment/clip index, and the model-keyed
calibration store.

### 1.2 Scope

Covers `internal/dbstore/` (`db.go`, `identity.go`, `phones.go`, `clips.go`, `segments.go`,
`regroup.go`, `calibration.go`, `schemas/db/*.sql`, `queries/*.sql[.go]`) and
`internal/dbstore/README.md` (the hand-written vs generated split, how to add a migration or
query).

### 1.3 Context

Every other controller component reads or writes through `Store`. The UI is a read view over
this DB (see [`controller-runtime.md`](controller-runtime.md),
[`controller-ui.md`](controller-ui.md)); it does not re-derive gallery state from live phone
calls.

### 1.4 Definitions

| Term | Meaning |
|---|---|
| segment / clip | one file / a contiguous run of them (system-wide terms; architecture §1.3) |
| clip lifecycle | the `active` → `trashed` → `purged` state machine |
| tombstone | a `purged` clip's DB row, kept so a resync can't redownload a discarded clip |
| sync cursor | the per-phone `since` watermark the syncer advances |
| model key | `manufacturer + model` — the calibration store's key, not a phone id |
| migration | a goose `schemas/db/NNN_*.sql` file, applied on `Open()` |

### 1.5 Acronyms and abbreviations

Linked to their row in [`architecture.md` §1.4](../architecture.md#14-acronyms-and-abbreviations):
[DB](../architecture.md#acr-db),
[SQL](../architecture.md#acr-sql),
[CGO](../architecture.md#acr-cgo),
[JSON](../architecture.md#acr-json).
`sqlc` and `goose` are tool names, not acronyms — see
[ADR 0001](../decisions/0001-repository-and-build.md).

### 1.6 References

- [`../decisions/0006-segments-clips-and-tombstones.md`](../decisions/0006-segments-clips-and-tombstones.md),
  [`0009`](../decisions/0009-calibration-model.md),
  [`0011`](../decisions/0011-controller-runtime-and-state.md),
  [`0001`](../decisions/0001-repository-and-build.md) (sqlc/goose, pure-Go driver).
- `controller/internal/dbstore/README.md`,
  [`../../../tools/dbstore/README.md`](../../../tools/dbstore/README.md) — codegen mechanics.
- Eviction-probe plan:
  [`../../status/eviction-probe-loop.md`](../../status/eviction-probe-loop.md).

## 2. Design overview

A hand-written domain-shaped `Store` wrapper over sqlc-generated query code, on a goose-migrated
schema, using the pure-Go `modernc.org/sqlite` driver. Five tables: identity, phones, segments,
clips, calibration.

## 3. Detailed design

### 3.1 Design entities

| Entity | Type | Responsibility |
|---|---|---|
| `Store` | hand-written wrapper | Domain-shaped API over the generated queries (`db.go` + per-aggregate files). |
| schema (goose) | `schemas/db/*.sql` | `001_initial` (identity, phones, calibration), `002_segments_and_clips` (the segment/clip split + a copy-over of existing rows). Applied automatically on every `Open()`. |
| queries (sqlc) | `queries/*.sql` → `*.sql.go` | Typed query code, `DO NOT EDIT` header. |
| `RegroupUnassignedSegments` (`regroup.go`) | one-time backfill | Groups segments carried over by the `002` migration into clips. |
| driver | `modernc.org/sqlite` | Pure Go — no CGO/gcc. |

### 3.2 Dependencies

- The SQLite file under `appdirs`' `data/`.
- Codegen tooling (`tools/dbstore`, `tools/go-deps`) — build-time only.

### 3.3 Interfaces

- **Provided:** `Store` methods to `App`, `syncer`, `pairing`, `unpair`, `calibration`.
- **Required:** the on-disk DB file.

### 3.4 Data

| Table | Columns of note |
|---|---|
| `identity` | the controller's Ed25519 keypair (generated once, reused for the life of the install) |
| `phones` | id, name, base URL, bearer token, last-seen, **sync cursor** |
| `segments` | one row per synced file, each with a `clip_id`; tombstone rows kept after purge |
| `clips` | the user-facing item — a contiguous run of segments; `active` / `trashed` / `purged` lifecycle + aggregate span / size / count |
| `calibration` | `manufacturer+model` → last result JSON + source phone id + timestamp (**shared**, not per-phone) |

Clip lifecycle transitions:

| Action | Effect |
|---|---|
| Trash (from Gallery) | active → trashed |
| Restore (from Trash) | trashed → active |
| Delete (single) | trashed → purged (file gone now; tombstone kept until eviction confirmed) |
| Empty trash (bulk) | Delete applied to every trashed clip |

### 3.5 Processing and behaviour

- Migrations run on `Open()`; a fresh DB is created and migrated transparently.
- A purged row's tombstone is only removed once an eviction probe confirms the phone no longer
  holds the segments — **not yet implemented**, so tombstones accumulate
  ([`../../status/eviction-probe-loop.md`](../../status/eviction-probe-loop.md)).
- Verified: migration `002` + backfill collapsed the real Pixel 6 archive's 15 contiguous
  segments into one clip.

## 4. Design rationale and decisions

- **SQLite as the single local source of truth** —
  [`0011`](../decisions/0011-controller-runtime-and-state.md).
- **Segments vs clips; three-state lifecycle; purge-keeps-tombstone** —
  [`0006`](../decisions/0006-segments-clips-and-tombstones.md): the controller must
  not redownload a discarded clip on a resync, and must never age `active` clips out on its own.
- **Calibration keyed by `manufacturer+model`** —
  [`0009`](../decisions/0009-calibration-model.md): the result depends on hardware +
  firmware, not which phone ran the sweep.
- **sqlc + goose, pure-Go driver** —
  [`0001`](../decisions/0001-repository-and-build.md): no CGO/gcc toolchain, generated
  code kept in a dedicated directory.
