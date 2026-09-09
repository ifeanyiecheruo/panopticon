# 0002 — Phone HTTP API: surface and auth model

**Status:** Accepted · implemented. Wire detail: [`../http-api.md`](../http-api.md).

## Context

The phone runs an HTTP server that paired controllers call. Modeled on the prototype's
`shared/api-contract.md` but reworked for this project's pairing model, multi-camera support, and
device-wide calibration. The trust model is **invite-in-both-hands**: an invite is generated on a
phone the owner physically holds and handed to a controller the owner also holds — not a
public-internet or broadcast scenario.

## Decision

### Per-controller bearer token, not a shared secret

Pairing exchanges a controller's public key for a per-controller token
(`Authorization: Bearer <token>`). Each controller can be revoked individually without
invalidating the others. Missing/unknown/revoked → `401`.

### No permission tiers

Every paired controller has full access to every route. No read-only/view-only notion.

### No human-confirmation step on pairing

`POST /api/pair` grants a fully active token immediately. Considered and dropped — the
invite-in-both-hands model makes the mitigations confirmation would add unnecessary.

### Admin actions are local library functions, not HTTP routes

Invite creation/listing/revocation and paired-controller listing/revocation are called by the
phone's **own local UI**, not exposed over HTTP — there is no remote party that needs them:

```
InviteManager.createInvite() / listPendingInvites() / revokeInvite(id)
ControllerRegistry.list() / revoke(controllerId)
```

The set of paired controllers is **not exposed over HTTP at all**, not even read-only.

### Self-unpair is the one exception

`DELETE /api/pair` *is* a route — the remote party (a controller unpairing itself) is the one who
needs it. It revokes **the calling token**, identified by the bearer token itself, with no
`controllerId` param, so it can't be repurposed to revoke a different controller.

### Naming / shape

- `/api/control/*` renamed to `/api/camera/*`.
- The API speaks **segments**, not clips (see
  [`0006`](0006-segments-clips-and-tombstones.md)).
- Favoriting removed entirely — no field, no route.
- Added along the way: `batteryPercent` / `charging` / `serverTimeMs` on `/api/status` (battery
  display + clock-skew correction), and `GET /api/build-info` so a caller can version-gate before
  hitting a route the phone may not support.

## Consequences

- Revoking one controller is a one-row delete; no key rotation.
- A controller cannot enumerate or manage other controllers — that's strictly an owner-at-the-phone
  task, which matches the physical trust model.
- Every route handler starts behind the same bearer-token gate (`AuthPlugin`); `POST /api/pair`
  is the sole unauthenticated route.
