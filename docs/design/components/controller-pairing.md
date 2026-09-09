# controller — pairing & phone client — software design description

## 1. Introduction

### 1.1 Purpose

Describe the component that establishes and tears down the pairing relationship with a phone,
and provides the typed HTTP client every other controller component uses to talk to phones.

### 1.2 Scope

Covers `internal/identity/`, `internal/pairing/`, `internal/unpair/`, and `internal/phoneapi/`
(`client.go`, `camera.go`, `calibration.go`).

### 1.3 Context

`pairing` / `unpair` are invoked by `App` methods. `phoneapi.Client` is a shared dependency of
`syncer`, `calibration`, `App`, `liveproxy`, and `unpair` — it is the only code that speaks HTTP
to a phone.

### 1.4 Definitions

| Term | Meaning |
|---|---|
| invite URL | `https://<addr>/api/connect?invite=<code>` — embeds both the address and the code |
| Unpair (safe) | drops local state only if the phone is reachable and the revoke succeeded (or `401`), after an unsynced-footage check |
| Force unpair | drops local state regardless; best-effort revoke; no unsynced-footage check possible |
| sentinel error | a named `error` value callers branch on (`ErrUnreachable`, `ErrInvalidInvite`, …) instead of string matching |
| opportunistic ingest | pulling `GET /api/calibration/result` right after a successful pair |

System-wide terms: architecture §1.3.

### 1.5 Acronyms and abbreviations

Linked to their row in [`architecture.md` §1.4](../architecture.md#14-acronyms-and-abbreviations):
[HTTP](../architecture.md#acr-http),
[Ed25519](../architecture.md#acr-ed25519),
[URL](../architecture.md#acr-url),
[LAN](../architecture.md#acr-lan).

### 1.6 References

- [`../decisions/0002-http-api-surface-and-auth.md`](../decisions/0002-http-api-surface-and-auth.md),
  [`0003`](../decisions/0003-pairing-and-unpairing.md).
- [`../http-api.md`](../http-api.md) — `POST`/`DELETE /api/pair`, `GET /api/segments`.
- QR pairing plan: [`../../status/qr-pairing.md`](../../status/qr-pairing.md).
- Consumer of the calibration client:
  [`controller-calibration-store.md`](controller-calibration-store.md).

## 2. Design overview

`identity` mints and persists the controller's Ed25519 keypair. `pairing` redeems an invite and
does the opportunistic calibration ingest. `unpair` implements the safe and forced teardown
paths. `phoneapi.Client` wraps every phone route with an explicit per-call timeout and typed
sentinel errors.

## 3. Detailed design

### 3.1 Design entities

| Entity | Type | Responsibility |
|---|---|---|
| `identity` | keypair | Generates/persists the controller's Ed25519 keypair (the `publicKey` in `POST /api/pair`). |
| `pairing` | Add-phone flow | `POST /api/pair` with this controller's identity; classifies failure into two distinct UI messages — unreachable address vs invalid/expired invite. Does the opportunistic calibration ingest right after a successful pair. `ParseInviteURL` splits a full URL into (address, code). |
| `unpair` | teardown | `Unpair` (safe): checks `GET /api/segments` for un-archived footage → `needs_confirmation` if any; requires the phone reachable **and** the revoke to succeed (or `401`) before `store.DeletePhone`. `Force`: best-effort revoke, drops the row regardless. Either way archived footage is kept; `syncer.reconcile` stops the phone's goroutine on its next tick. |
| `phoneapi.Client` | HTTP client | One explicit context timeout per call type (short for metadata, long for downloads). Typed sentinels: `ErrUnreachable`, `ErrUnauthorized`, `ErrInvalidInvite`, `ErrEvicted`, `ErrUnknownCamera`, `*InvalidControlKeyError{Key,Reason}`. |
| `phoneapi/camera.go` | client surface | `/api/cameras`, `/api/cameras/active`, `/api/camera/capabilities`, `/api/camera/state`. |
| `phoneapi/calibration.go` | client surface | `/api/calibration/result` — decodes the full per-resolution zoom map. |

### 3.2 Dependencies

- `Store` — `phones`, `identity`.
- The phone HTTP contract ([`../http-api.md`](../http-api.md)).

### 3.3 Interfaces

- **Provided:** `phoneapi.Client` to `syncer`, `calibration`, `App`, `liveproxy`, `unpair`;
  `pairing.Pair()` / `unpair.Unpair()` / `unpair.Force()` to `App`.
- **Required:** `Store`; a reachable phone.

### 3.4 Data

- Writes the `identity` row (once) and `phones` rows (on pair) / deletes them (on unpair).
- Holds no footage or config data.

### 3.5 Processing and behaviour

- Every phone call has an explicit context timeout — Go's `http.Client` has none by default, and
  a stalled-but-connected phone can hang a bare request forever (a prototype lesson).
- Pairing failure is classified before it reaches the UI so Add-phone can show "could not reach
  <addr>" vs "invalid or expired invite".

## 4. Design rationale and decisions

- **Per-controller bearer token; self-unpair is the one registry route** —
  [`0002`](../decisions/0002-http-api-surface-and-auth.md).
- **Invite needs address + code; identity is a public key, never an IP** —
  [`0003`](../decisions/0003-pairing-and-unpairing.md). QR (which carries both) is
  [`../../status/qr-pairing.md`](../../status/qr-pairing.md).
- **Unpair vs Force unpair as two distinct actions** —
  [`0003`](../decisions/0003-pairing-and-unpairing.md): safe Unpair must keep local
  state consistent with the fact that the phone still holds a live token if the revoke didn't
  land.
- **Typed sentinel errors** — callers react distinctly (unreachable vs unauthorised vs evicted)
  without pattern-matching error strings.
