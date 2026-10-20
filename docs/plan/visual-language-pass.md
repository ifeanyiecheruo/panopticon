# Plan: visual-language pass (both apps) + custom scrubber

**Side:** both · **Size:** medium

## Goal

Replace the inherited placeholder theme with a settled visual language, applied consistently to
the phone app and the controller, and build the controller's custom scrubber.

## Context

[`../design/decisions/0012-ux-shape.md`](../design/decisions/0012-ux-shape.md): the dark
near-black/teal theme (IBM Plex Sans/Mono, teal accent, frosted glass) is inherited from the
mocks as an explicit **placeholder**, "not a settled decision, flagged for a later pass". The
controller also uses a native `<video controls>` element instead of the mock's custom
scrubber+playhead — functional, just not the mock's visual treatment.

## Approach

1. **Decide the language** — palette, type scale, spacing, elevation/blur, motion. Produce it as
   design tokens, not per-component values. (The phone mock's tightened padding/gap values are a
   starting reference, not the answer.)
2. **Phone** — `ui/theme/Theme.kt`: swap the token values; audit each screen for hard-coded
   colors/spacing that bypass the theme.
3. **Controller** — `frontend/src/style.css` is plain hand-written CSS imported once; introduce
   CSS custom properties for the tokens and refactor screens/components onto them.
4. **Custom scrubber** — `frontend/src/components/` a scrubber+playhead component replacing the
   native controls in `ClipPlayer.tsx`: play/pause/seek/fullscreen + the mock's visual
   treatment, keeping the existing playlist/autoplay behaviour.

## Affected files

Phone: `ui/theme/Theme.kt`, screens under `ui/`. Controller: `frontend/src/style.css`,
`frontend/src/screens/*`, `frontend/src/components/*` (esp. `ClipPlayer.tsx`), maybe
`frontend/src/lib/icons.tsx`.

## Testing

Mostly visual — before/after screenshots of every screen in both apps, light/dark if applicable.
`ClipPlayer` behaviour tests (seek, autoplay-through) still green with the custom scrubber.

## Acceptance

One documented set of design tokens driving both apps; no screen bypasses the theme layer; the
controller scrubber matches the mock and keeps playlist/autoplay behaviour.

## Not in scope

Restructuring any screen's layout or interaction (that's [`0012`](../design/decisions/0012-ux-shape.md),
already settled); a component library dependency.
