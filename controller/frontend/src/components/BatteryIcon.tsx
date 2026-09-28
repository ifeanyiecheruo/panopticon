interface BatteryIconProps {
  percent: number;
  charging: boolean;
  /** When false, render nothing (phone reported no battery / is unreachable). */
  hasBattery?: boolean;
  /** `PowerManager`'s 0..6 thermal severity, or -1/undefined when the phone cannot report it
   *  (Android < 29, or unreachable). Drives the fill colour — see [thermalFill]. */
  thermalSeverity?: number;
  /** `none` | `light` | `moderate` | `severe` | `critical` | `emergency` | `shutdown`. Title text
   *  only; the colour comes from [thermalSeverity] so an unrecognised name cannot change it. */
  thermalLevel?: string;
  /** False when the phone cannot report thermal state at all, in which case the battery keeps its
   *  original charge-derived colour rather than being tinted by a value we do not have. */
  hasThermal?: boolean;
}

/**
 * Green → red across `PowerManager`'s seven severities. Explicit stops rather than an interpolated
 * hue ramp: a straight green→red lerp passes through a muddy olive in the middle that reads as
 * neither healthy nor hot, and the middle is exactly where the interesting states live.
 *
 * Index 0 is not the theme's teal accent. A thermally-reporting phone at `none` should look
 * deliberately fine, not merely default, and teal against these warmer stops reads as a different
 * axis rather than one end of this one.
 */
const THERMAL_FILL = [
  '#3ddc84', // none      — green
  '#8fd44a', // light
  '#d8c93f', // moderate  — yellow
  '#e8a33a', // severe    — amber
  '#e07a33', // critical  — orange
  '#dd5533', // emergency
  '#d93b3b', // shutdown  — red
];

/**
 * Battery status as a glyph, never text: a body whose fill *length* tracks the charge level and
 * whose fill *colour* tracks how close the phone is to thermal throttling, with a bolt overlaid
 * while charging. A hidden <title> carries both values for hover/screen-reader only.
 *
 * Two quantities in one glyph because they are read together — a phone that is hot *and* draining
 * is a different situation from either alone, and a second icon beside this one made that
 * comparison a saccade instead of a glance. Length and colour are independent channels, so
 * neither has to give way.
 *
 * The one collision is low charge, which used to own the fill colour (amber at ≤20%). Thermal
 * takes the fill; low charge moves to the **outline**, which is otherwise a constant, so it stays
 * legible at any temperature instead of being overwritten by it. A phone that cannot report
 * thermal keeps the original charge-derived fill, so nothing is invented for devices that cannot
 * answer.
 */
export function BatteryIcon({
  percent,
  charging,
  hasBattery = true,
  thermalSeverity,
  thermalLevel,
  hasThermal = false,
}: BatteryIconProps) {
  if (!hasBattery) return null;

  const pct = Math.max(0, Math.min(100, Math.round(percent)));
  const low = pct <= 20;

  const sev =
    hasThermal && typeof thermalSeverity === 'number' && thermalSeverity >= 0
      ? Math.min(THERMAL_FILL.length - 1, Math.round(thermalSeverity))
      : null;
  const color = sev !== null ? THERMAL_FILL[sev] : low ? 'var(--warn)' : 'var(--accent)';
  const outline = low ? 'var(--warn)' : 'var(--text-dim)';

  // Body inner area: x 2..20 (width 18), y 3..13 (height 10).
  const innerW = 18;
  const fillW = Math.max(pct > 0 ? 1.5 : 0, (pct / 100) * innerW);

  const thermalText = sev !== null ? ` · ${thermalLevel || 'thermal'} (${sev})` : '';

  return (
    <svg
      class="battery"
      width="30"
      height="16"
      viewBox="0 0 30 16"
      fill="none"
      role="img"
      aria-label={`Battery ${pct}%${charging ? ', charging' : ''}${
        sev !== null ? `, thermal ${thermalLevel || sev}` : ''
      }`}
    >
      <title>{`${pct}%${charging ? ' · charging' : ''}${thermalText}`}</title>
      <rect x="1" y="2" width="21" height="12" rx="2.5" stroke={outline} stroke-width="1.5" />
      <rect x="23.5" y="5.5" width="2.5" height="5" rx="1" fill={outline} />
      <rect x="2.5" y="3.5" width={fillW} height="9" rx="1" fill={color} />
      {charging && (
        <path
          d="M13.5 3.2 L9 9 h3 l-1.5 4.6 L15 7.4 h-3 z"
          fill="var(--accent-ink)"
          stroke="var(--accent-ink)"
          stroke-width="0.5"
          stroke-linejoin="round"
        />
      )}
    </svg>
  );
}
