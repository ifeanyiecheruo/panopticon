# Plan: report and show power state - plugged vs charging

**Side:** both · **Size:** small

## Goal

Show, independently, whether a phone is **plugged in** and whether its battery is **gaining or
losing charge**, so "on power but draining" is visible before the phone dies.

## Context

On 2026-09-29 the Pixel 6 sat on a 7.5W (5V/1.5A) charger while drawing more than that: the
battery fell from 95% to 46% in about 4.5h, net -0.62A, while "plugged in". `/api/status` reports a
single `charging` bool from `BatteryManager.isCharging`, which follows the charge *status* - so
that phone just read `charging: false`, indistinguishable from unplugged. Android's status fields
also lag: after a 12W charger was swapped in, `current_now` went positive (+0.1-0.4A) within
seconds while `dumpsys battery` still said "not charging" at the old 5V/1.5A.

## Approach

- **Phone** (`DeviceRoutes` `/api/status`): add `plugged` (`EXTRA_PLUGGED` != 0, with the source:
  ac/usb/wireless/dock) and `batteryCurrentMa` (`BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`,
  sign = charging vs draining; average a few samples, it is noisy). Keep `charging` for
  compatibility. Optionally a health event when plugged and draining for several minutes.
- **Controller** (`BatteryIcon.tsx`): two independent overlays on the battery glyph - a plug when
  plugged, and a charging/draining indicator from the current's sign - so all four combinations
  read at a glance; the tooltip gives the current and percent.

## Not in scope

Charge-rate estimates / time-to-empty, or throttling the pipeline automatically on low battery.
