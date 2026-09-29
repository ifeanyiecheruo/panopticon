# Plan: sync cadence, backoff, and status polling

**Side:** controller · **Size:** small

## Goal

Replace the fixed process-start poll interval with a cadence/backoff strategy shared by the sync
loop and the eviction-probe loop, and stop re-hitting every phone's `/api/status` on every UI
render.

## Context

Left as an open item by the original controller UX design, and confirmed in the code:

- `internal/syncer` polls each phone every `syncPollInterval` (`main.go`, 30s), fixed at process
  start; **no backoff** against a phone that's been unreachable for a while — it just retries
  forever on the same interval.
- `ListPhones` / `GetPhoneDetail`'s live-status fetch has **no caching** — every Fleet render
  re-hits every paired phone's `/api/status`. Fine for a handful of phones; not at fleet scale.
- The eviction probe (`internal/syncer/evictprobe.go`) rides the sync tick, on its own
  10-minute `evictionProbeInterval`, so it inherits whatever cadence the sync loop gets.

## Approach

- **Backoff** — per phone, on consecutive failures grow the interval (e.g. 30s → 1m → 5m → cap
  at ~15m); reset to base on the first success. Keep the base interval configurable.
- **Shared policy** — the eviction probe already only runs on a tick that reached the phone,
  so backing off the sync tick backs it off too; no second policy needed.
- **Status caching** — a short TTL cache (a few seconds) in front of the `/api/status` fetch
  behind `ListPhones` / `GetPhoneDetail`, plus optional coalescing so concurrent renders share
  one in-flight request. (A full continuously-updating background poll is
  [`controller-status-polling` follow-up](#not-in-scope) — this plan only stops the redundant
  re-hits.)

## Affected files

`controller/main.go` (config), `internal/syncer/` (backoff + policy), a new
`internal/pollpolicy/` (or inline), `app.go` (status cache around `ListPhones` /
`GetPhoneDetail`).

## Testing

`syncer` unit tests: interval grows on repeated failure, resets on success. `app.go`: two
back-to-back `ListPhones` calls within the TTL do one `/api/status` round trip per phone.

## Acceptance

A phone unplugged for an hour is polled minutes apart, not every 30s; a Fleet screen left open
doesn't generate a `/api/status` storm; the eviction-probe loop reuses the same policy.

## Not in scope

A true push/streaming status channel; a continuously-updating Fleet that refreshes without a
render (nice-to-have, separate).
