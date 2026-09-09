# 0003 — Pairing and unpairing

**Status:** Accepted. Pairing + Unpair + Force unpair implemented (controller); QR display/scan
deferred ([`../../status/qr-pairing.md`](../../status/qr-pairing.md)).

## Context

Pairing bootstraps trust between a phone (the server) and a controller (the client) over the LAN,
with no discovery protocol and no third party. See also
[`0002`](0002-http-api-surface-and-auth.md) for the token model.

## Decision

### The phone generates invites; controllers cannot

An invite is created on the phone's Connect screen. A controller only ever *consumes* one via
`POST /api/pair?invite=<code>`.

### An invite needs an address as well as a code

`POST /api/pair` is a request to the phone's own HTTP server, so the controller needs the
phone's network address to send it. The full invite **URL** (e.g.
`https://192.168.1.87/api/connect?invite=XYZF-EBDO-ORMS`) embeds both; the bare short code shown
large on the Connect screen does not and needs the address entered separately. A QR encodes the
full URL, capturing both at once.

Controller Add-phone consequence: the paste view has **two fields** (address + invite code), but
pasting a full URL into either auto-fills both. QR view needs neither.

### Identity is a public key, never an IP

Controllers are identified to the phone by public key only. No IP address is stored or displayed
anywhere in either app.

### Unpair vs Force unpair are two distinct actions

- **Unpair** (safe): first checks `GET /api/segments` for footage the phone still holds that the
  controller never archived, and warns if any. Requires the phone reachable **and** the
  `DELETE /api/pair` revoke to succeed (or return `401` — already gone) before dropping local
  state — otherwise the phone still holds a live token and local state must reflect that.
- **Force unpair**: drops local state regardless of reachability or revoke outcome (best-effort
  revoke). Needs a stronger confirmation than plain Unpair, and its confirmation copy must say
  the unsynced-footage check could not be performed (the premise is the phone may be
  unreachable). Can leave an orphaned live token only the phone's own UI can clean up.

### Already-synced footage is kept after either kind of unpair

It stays in the aggregate Gallery as historical footage from a now-unpaired phone. Only the
pairing/sync relationship ends (`store.DeletePhone` removes just the pairing row).

## Consequences

- Self-unpair required exactly one new phone route (`DELETE /api/pair`) — the one
  controller-registry action where the remote party needs it.
- No location/IP data exists to leak.
- The unsynced-footage warning is best-effort and absent on Force unpair by design.
