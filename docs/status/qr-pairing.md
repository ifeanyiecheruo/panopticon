# Plan: QR-code pairing

**Side:** both · **Size:** medium

## Goal

Let the phone display a pairing QR and the controller scan it with a webcam, so address + invite
code are captured together without typing.

## Context

[`../design/decisions/0003-pairing-and-unpairing.md`](../design/decisions/0003-pairing-and-unpairing.md):
`POST /api/pair` needs the phone's **address** as well as the invite **code**. The full invite
URL embeds both; a QR encodes that same URL. Today the phone's Connect screen shows the code +
URL as plain text and the controller's Add-phone has only the two-field paste path (full-URL
autofill into either field). No new API — this is UI on both ends.

## Approach

**Phone (`ui/connect/ConnectScreen.kt`):** render the invite URL as a QR alongside the existing
text. A pure-Kotlin/Java QR encoder (e.g. ZXing `core`, no camera dependency) drawn to a Compose
`Canvas`/`ImageBitmap`. `InviteManager.createInvite()` already yields the URL.

**Controller (`frontend/src/screens/AddPhone.tsx`):** the mock's asymmetric framing — a text
link "Or scan a QR code from the phone →" switches the paste view for a `getUserMedia` webcam
viewfinder, with a link back. Decode with a small JS QR lib (vendored, per
[`0007`](../design/decisions/0007-live-preview-plain-hls.md)'s no-npm-runtime-dep precedent, or
`BarcodeDetector` where available). On decode, parse the URL → fill both fields → run the same
pairing outcome path as paste.

## Affected files

Phone: `ui/connect/ConnectScreen.kt`, app `build.gradle` (QR encoder dep).
Controller: `frontend/src/screens/AddPhone.tsx`, a vendored QR-decode lib under
`frontend/src/vendor/`, `frontend/src/style.css` (viewfinder).

## Testing

Phone: a screenshot/`ImageBitmap` test that the rendered QR decodes back to the invite URL.
Controller: unit-test the "decoded URL → both fields" parse (reuse the existing full-URL parser);
manual webcam scan end to end against a real phone.

## Acceptance

Scanning the phone's QR with the controller webcam pairs with no typing; the paste path is
unchanged; camera-permission denial falls back cleanly to paste.

## Not in scope

Mobile-controller / phone-to-phone pairing; changing the invite URL format.
