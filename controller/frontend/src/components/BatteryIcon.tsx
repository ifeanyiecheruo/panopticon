interface BatteryIconProps {
  percent: number;
  charging: boolean;
  /** When false, render nothing (phone reported no battery / is unreachable). */
  hasBattery?: boolean;
}

/** Battery status as a glyph, never text: a body whose fill tracks the charge
 * level (amber at/below 20%), with a bolt overlaid while charging. A hidden
 * <title> carries the numeric value for hover/screen-reader only. */
export function BatteryIcon({ percent, charging, hasBattery = true }: BatteryIconProps) {
  if (!hasBattery) return null;

  const pct = Math.max(0, Math.min(100, Math.round(percent)));
  const low = pct <= 20;
  const color = low ? 'var(--warn)' : 'var(--accent)';

  // Body inner area: x 2..20 (width 18), y 3..13 (height 10).
  const innerW = 18;
  const fillW = Math.max(pct > 0 ? 1.5 : 0, (pct / 100) * innerW);

  return (
    <svg
      class="battery"
      width="30"
      height="16"
      viewBox="0 0 30 16"
      fill="none"
      role="img"
      aria-label={`Battery ${pct}%${charging ? ', charging' : ''}`}
    >
      <title>{`${pct}%${charging ? ' · charging' : ''}`}</title>
      <rect x="1" y="2" width="21" height="12" rx="2.5" stroke="var(--text-dim)" stroke-width="1.5" />
      <rect x="23.5" y="5.5" width="2.5" height="5" rx="1" fill="var(--text-dim)" />
      <rect x="2.5" y="3.5" width={fillW} height="9" rx="1" fill={color} />
      {charging && (
        <path
          d="M13.5 3.2 L9 9 h3 l-1.5 4.6 L15 7.4 h-3 z"
          fill={low ? 'var(--warn)' : 'var(--accent-ink)'}
          stroke={charging && !low ? 'var(--accent-ink)' : 'none'}
          stroke-width="0.5"
          stroke-linejoin="round"
        />
      )}
    </svg>
  );
}
