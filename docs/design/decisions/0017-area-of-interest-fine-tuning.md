# 0017 — Auto belongs to the phone, and fine-tunes an area of interest itself

**Status:** Accepted · implemented (phone-app, controller, mock phone), 2026-11-11, after two
earlier designs (below). Measured live on the Pixel 7 Pro.
Component specs: [`phone-camera-control.md`](../components/phone-camera-control.md),
[`controller-live-and-camera.md`](../components/controller-live-and-camera.md).

## Context

In auto the phone's own AE, AF and AWB run the camera. Drawing an area of interest points the AE
and AF regions at it, but neither optimises for what the area needs:

- **Exposure.** The gallery player stretches the area's levels and applies a gamma that puts its
  median at mid-grey (`lib/pictureRenderer.ts`). Where the mid-tones land is therefore fixed in
  playback; what is not is how many distinct levels the area was captured with. The phone's AE
  meters for a "correct" brightness instead. On the Pixel 7 Pro, an area inside a window was
  metered to levels 52..97 (3.9 bits of tonal entropy) where 4.8 bits were available.
- **Focus.** Continuous AF on the area is not guaranteed to land on the sharpest lens position.

A camera is a security camera: it has to stay well exposed through a day of changing light, with
or without a controller connected.

## Decision

### Auto is the phone's, all of it

`manualControlEnabled: false` means the phone decides exposure, focus and white balance. The only
keys auto takes are the **zoom** (`zoomRatio`, `zoomSelectNorm`, `zoomViewNorm`), the **exposure
compensation** (`aeExposureCompensation`: a bias the phone's AE applies while still metering and
following the light, so it is a preference about auto, not a manual exposure), and the **area of
interest** (`aeSelectNorm` / `afSelectNorm`, `aeRegionNorm` / `afRegionNorm`). Any other key sent
for a camera in (or being put into) auto is **`409 {error, key}`** - a conflict with the mode, not
a bad value; `false` for a manual switch is fine, it means "auto". Entering auto drops the other
keys; leaving it, the controller seeds the manual sliders from what auto had tuned.

### Implicit area: the HAL's. Explicit area: the HAL's, fine-tuned

With no area drawn (an implicit area, the whole frame) auto is the HAL's alone. With one drawn,
the HAL's auto still runs the camera - AE and AF metering the area - and `AutoAreaTuner`, inside
the camera pipeline and with no controller, only **nudges** it, slowly, with the levers the
manual sliders use:

- **A baseline first.** When an area is drawn or moved, or the camera (re)starts with one, AE and
  AF settle on it untouched. Tuning starts from there, and "Re-tune"
  (`POST /api/camera/area-tune`) starts again from there.
- **Exposure: compensation on top of AE.** AE stays on, so it keeps following the light by
  itself. Tuning adds exposure compensation on top of the user's own, one camera step (a sixth of
  a stop on a Pixel) at a time, at most **1 EV** either way and within the camera's range. A new
  compensation from the user is a new baseline: tuning starts over from it.
- **Focus: the focus distance, near AF's.** Focus is held manually at the distance AF settled on,
  then moved a 320th of the lens's range at a time, at most 8 steps either way.
- **Slowly.** A trial every 10s (exposure) or 15s (focus) while they pay off. Each trial measures
  the area at the current setting, moves one step, measures again, and keeps the step only if it
  helped - by 0.02 bits of contrast or 3% sharpness, over three frames - otherwise it steps back.
  Once both directions have failed, the wait doubles, up to 5 minutes, so a tuned area is left
  alone while a slow drift is still followed.
- **A watchdog, every 5 minutes.** One step at a time is not always enough to follow a change in
  the light: a bias tuned in the morning was visibly too bright by noon, and a manual Re-tune fixed
  it at once. So every 5 minutes the area is measured as a trial measures it (contrast, and
  sharpness while focus is held, over three frames, with AE steady), but with nothing changed.
  The result is compared with what the area measured where tuning last settled: at the baseline
  or the last kept step. If it has fallen by more than **0.1 bits** of contrast or **15%**
  sharpness, which is well beyond a trial's margin and so beyond an unchanged scene's wobble,
  tuning starts over from a new baseline. The status message says so.
- What it holds is phone-internal state (`AutoOverride`), never control keys. The controller
  sees it as `autoTuning` in `GET /api/camera/state`.

### Guards

Each was needed on the Pixel 7 Pro's telephoto:

- **Never measure AE mid-move.** A trial waits for the reported exposure to hold still (under
  0.03 stops over 3 results 200ms apart). If AE is moving, the light is changing or AE is still
  recovering, and there is no trial this time.
- **Don't push a clipped area further into the clip.** An area with over 1% of its pixels at
  white is not brightened, nor one at black darkened. With the AE region on an area whose top 1%
  was already white, **+1/6 EV ran AE away**: it ramped the exposure two stops in 4s and kept
  climbing, and +1/3 EV went to solid white. Negative steps were smooth. Without an AE region,
  compensation behaved: about a sixth of a stop per step, settled within half a second.
- **A runaway is undone and not repeated.** After a step, if AE moves more than three times the
  step plus a quarter stop, or doesn't settle within 6s, the step is undone at once. That
  direction is then closed until auto starts over on the area.
- **Hold AF's distance only where that keeps its sharpness.** On the telephoto, holding AF's own
  distance manually lost about a third of the sharpness (AF reached sharpness no manual distance
  matched, 0 to 9.5 D). So focus stays AF's there. Every probe hands the lens back to AF, which
  looks for focus again, so the result is remembered per camera while the app runs.

### How it measures

Each step is an override on the repeating request. It reads the area from frames captured after
the step took effect - the encoder's own draw, read back on the GL thread over only the area
(`AreaReadback`). There is no JPEG.

- **Contrast** is the **entropy of the area's luma histogram**, read at up to 960px. It rises as
  the tones spread and falls as they pile into black or white. Live, it agreed with the player's
  own black-to-white spread.
- **Sharpness** is **Tenengrad** (mean squared Sobel gradient), read at up to 1920px over the
  area **plus 5% of the frame on each side**. Strictly inside the area, defocus raised the score:
  the edges around an area inside a window blurred into it, scoring four times higher well out
  of focus. With the margin, an edge's whole blur is counted.

### Earlier designs

1. **Tuning from the controller.** The controller searched exposure and focus over HTTP, through
   manual mode, measuring JPEG stills. It worked only while a controller was connected, never
   followed the light, and paid a Wi-Fi round trip per step.
2. **A search on the phone.** The tuner then searched the whole range on the phone: exposure (a
   manual shutter and ISO, AE off) in one-stop steps refined to a quarter stop, then focus across
   the lens's range. It kept the result only if it beat the phone's own. It found more contrast
   (4.0 -> 4.8 bits on one area), but every tune swept the picture through stops of exposure and
   the whole focus range on the recording. And with AE off, the held exposure didn't follow the
   light between tunes: corrections held the median, and only a stop's change triggered a full
   search again. In use, the shifts were dramatic and the exposure lagged the light.

Starting from the HAL's auto and nudging it gives up some of the second design's gains (on the
area measured, +0.04 bits rather than +0.8). In exchange, AE follows the light by itself, the
picture never moves more than a step, and auto, which already gets close, is only refined.

## Consequences

- A recording shows each kept nudge as a sixth-of-a-stop exposure change or a slight focus move,
  seconds apart while tuning and minutes apart once settled. The motion gate treats each step as
  the camera's own disturbance.
- A clipped area is only ever darkened. Where the HAL's AE meters an area into the clip, the
  tuner can bring it back by at most 1 EV.
- On a camera where manual focus can't match AF, only exposure is tuned.
- The area readback holds the GL thread for a few milliseconds per measurement: six per trial.
- An older phone build `400`s nothing new, ignores `maxAgeMs`, reports no `autoTuning`, and
  accepts manual keys in auto (ignoring them). The controller says it needs updating when asked
  to re-tune.
