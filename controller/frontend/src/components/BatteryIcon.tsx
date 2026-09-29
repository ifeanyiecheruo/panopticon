interface BatteryIconProps {
  percent: number;
  /** The phone's charge *status*. Only consulted when [currentMa] is unavailable - it reads false
   *  on a charger too weak for the load, which is exactly the case this icon exists to show. */
  charging: boolean;
  /** On external power at all, whatever the battery is doing. */
  plugged?: boolean;
  /** `ac` | `usb` | `wireless` | `dock` | `none` - title text only. */
  powerSource?: string;
  /** Net battery current averaged over about a minute, mA: positive = gaining charge, negative =
   *  draining. Null/undefined when the phone cannot report it. */
  currentMa?: number | null;
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

/** Below this net current, in mA either way, the battery is holding rather than charging or
 *  draining - a phone held at its charge limit reads a few mA of noise. */
const HOLDING_MA = 20;

type Flow = 'gaining' | 'draining' | 'holding';

function flowOf(currentMa: number | null | undefined, charging: boolean): Flow {
  if (typeof currentMa !== 'number') return charging ? 'gaining' : 'holding';
  if (currentMa >= HOLDING_MA) return 'gaining';
  if (currentMa <= -HOLDING_MA) return 'draining';
  return 'holding';
}

/**
 * Battery status as a glyph, never text: a body whose fill *length* tracks the charge level and
 * whose fill *colour* tracks how close the phone is to thermal throttling. A hidden <title> carries
 * the values for hover/screen-reader only.
 *
 * Power is two independent marks, because "plugged in" and "charging" are different questions and
 * the one that matters is when they disagree:
 *
 * - a **plug** left of the body while on external power;
 * - inside the body, a **bolt** while the battery is gaining charge, or a **down arrow** while it
 *   drains *on power* - and then the plug turns warning-coloured too, since that phone is dying on
 *   its charger. Draining off power is the normal case and gets no mark.
 *
 * So: nothing = on battery; plug + bolt = charging; plug alone = on power, holding; warning plug +
 * arrow = on power but draining. The direction comes from the measured current, falling back to
 * the charge status only on phones that can't report the current.
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
  plugged = false,
  powerSource,
  currentMa,
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

  const flow = flowOf(currentMa, charging);
  const drainingOnPower = plugged && flow === 'draining';
  const plugColor = drainingOnPower ? 'var(--warn)' : 'var(--text-dim)';
  const source = powerSource && powerSource !== 'none' ? ` (${powerSource})` : '';
  const current = typeof currentMa === 'number' ? ` ${Math.abs(currentMa)} mA` : '';
  const powerText = [
    plugged ? `plugged in${source}` : 'on battery',
    flow === 'gaining' ? `charging${current}` : flow === 'draining' ? `draining${current}` : '',
  ]
    .filter(Boolean)
    .join(' · ');

  return (
    <svg
      class="battery"
      width="40"
      height="16"
      viewBox="0 0 40 16"
      fill="none"
      role="img"
      aria-label={`Battery ${pct}%, ${powerText.replace(/ · /g, ', ')}${
        sev !== null ? `, thermal ${thermalLevel || sev}` : ''
      }`}
    >
      <title>{`${pct}% · ${powerText}${thermalText}`}</title>
      {plugged && (
        // Two prongs into a rounded plug body, cord trailing down.
        <g stroke={plugColor} stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round">
          <path d="M3 2.5 v3 M6.5 2.5 v3" />
          <path d="M1.5 5.5 h6.5 v2.5 a3.25 3.25 0 0 1 -6.5 0 z" fill={plugColor} />
          <path d="M4.75 11.2 v3" />
        </g>
      )}
      <g transform="translate(10 0)">
        <rect x="1" y="2" width="21" height="12" rx="2.5" stroke={outline} stroke-width="1.5" />
        <rect x="23.5" y="5.5" width="2.5" height="5" rx="1" fill={outline} />
        <rect x="2.5" y="3.5" width={fillW} height="9" rx="1" fill={color} />
        {flow === 'gaining' && (
          <path
            d="M13.5 3.2 L9 9 h3 l-1.5 4.6 L15 7.4 h-3 z"
            fill="var(--accent-ink)"
            stroke="var(--accent-ink)"
            stroke-width="0.5"
            stroke-linejoin="round"
          />
        )}
        {drainingOnPower && (
          // Warning colour over a dark halo, unlike the bolt: this state matters most at low
          // charge, where the arrow sits over the empty (dark) body rather than the fill.
          <g stroke-linecap="round" stroke-linejoin="round">
            <path d="M11.5 4 v6.5 M8.5 7.8 l3 3.2 l3 -3.2" stroke="var(--accent-ink)" stroke-width="3.4" />
            <path d="M11.5 4 v6.5 M8.5 7.8 l3 3.2 l3 -3.2" stroke="var(--warn)" stroke-width="1.6" />
          </g>
        )}
      </g>
    </svg>
  );
}
