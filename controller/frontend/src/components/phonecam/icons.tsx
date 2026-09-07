/** Inline SVG glyphs for the phone-style camera UI — ported from the phone
 * Preview mock's `icon()` map (docs/design/ux-mocks/phone-ux-mock.html). */
import type { ComponentChildren, JSX } from 'preact';

const S = (d: ComponentChildren, opts: { fill?: boolean } = {}) => (
  <svg
    viewBox="0 0 24 24"
    fill={opts.fill ? 'currentColor' : 'none'}
    stroke={opts.fill ? 'none' : 'currentColor'}
    stroke-width="2"
    stroke-linecap="round"
    stroke-linejoin="round"
  >
    {d}
  </svg>
);

// ---- mode-button icons (adjust bar) ----
export type ModeIconName =
  | 'zoom'
  | 'crop'
  | 'exposure'
  | 'shutter'
  | 'iso'
  | 'focus'
  | 'wb'
  | 'wbgain'
  | 'stabilize'
  | 'rotate'
  | 'auto';

const MODE: Record<ModeIconName, JSX.Element> = {
  zoom: S(
    <>
      <circle cx="11" cy="11" r="7" />
      <path d="M21 21l-4.3-4.3M9 11h4M11 9v4" />
    </>,
  ),
  crop: S(
    <>
      <path d="M6 2v14a2 2 0 002 2h14" />
      <path d="M2 6h14a2 2 0 012 2v14" />
    </>,
  ),
  exposure: S(
    <>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v3M12 19v3M2 12h3M19 12h3" />
    </>,
  ),
  shutter: S(
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 3l4.5 7.8M21 12l-9 0M16.5 20.2L12 12M3 12l4.5-7.8M7.5 20.2L12 12" />
    </>,
  ),
  iso: S(
    <>
      <rect x="3" y="6" width="18" height="12" rx="2" />
      <path d="M7 10v4M11 14V10l3 4v-4M17 10h-1a1 1 0 000 2h.5a1 1 0 010 2H15" />
    </>,
  ),
  focus: S(
    <>
      <circle cx="12" cy="12" r="3" />
      <path d="M3 8V5a2 2 0 012-2h3M16 3h3a2 2 0 012 2v3M21 16v3a2 2 0 01-2 2h-3M8 21H5a2 2 0 01-2-2v-3" />
    </>,
  ),
  wb: S(
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 3a9 9 0 000 18z" fill="currentColor" stroke="none" />
    </>,
  ),
  wbgain: S(
    <>
      <path d="M4 20V8M12 20V4M20 20v-8" />
    </>,
  ),
  stabilize: S(
    <>
      <path d="M12 3l7 3v5c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z" />
    </>,
  ),
  rotate: S(
    <>
      <path d="M3 12a9 9 0 019-9 9 9 0 016.7 3" />
      <path d="M21 3v5h-5" />
      <path d="M21 12a9 9 0 01-9 9 9 9 0 01-6.7-3" />
      <path d="M3 21v-5h5" />
    </>,
  ),
  auto: S(
    <>
      <path d="M5 15l3-8 3 8M5.8 12.5h4.4" />
      <path d="M20 8.5A3 3 0 0016 8a3 3 0 000 8 3 3 0 004-0.5" />
    </>,
  ),
};

export function modeIcon(name: ModeIconName): JSX.Element {
  return MODE[name];
}

// ---- ruler end icons (polarity glyphs at the two ends of a ruler) ----
export type RulerIconName =
  | 'zoomOut'
  | 'zoomIn'
  | 'focusNear'
  | 'focusFar'
  | 'moon'
  | 'sun'
  | 'shadowLow'
  | 'shadowHigh'
  | 'wbCool'
  | 'wbWarm'
  | 'minus'
  | 'plus';

const END: Record<RulerIconName, JSX.Element> = {
  zoomOut: S(
    <>
      <circle cx="10.5" cy="10.5" r="6.5" />
      <path d="M21 21l-4.8-4.8M8 10.5h5" />
    </>,
  ),
  zoomIn: S(
    <>
      <circle cx="10.5" cy="10.5" r="6.5" />
      <path d="M21 21l-4.8-4.8M10.5 8v5M8 10.5h5" />
    </>,
  ),
  focusNear: S(
    <>
      <path d="M4 9V6a2 2 0 012-2h3M15 4h3a2 2 0 012 2v3M20 15v3a2 2 0 01-2 2h-3M9 20H6a2 2 0 01-2-2v-3" />
      <circle cx="12" cy="12" r="3.2" fill="currentColor" stroke="none" />
    </>,
  ),
  focusFar: S(
    <>
      <path d="M4 9V6a2 2 0 012-2h3M15 4h3a2 2 0 012 2v3M20 15v3a2 2 0 01-2 2h-3M9 20H6a2 2 0 01-2-2v-3" />
      <path d="M6 15l3.5-4.5 2.5 3 2-2.5L18 15" />
    </>,
  ),
  moon: S(<path d="M20 14.5A8.5 8.5 0 1110 3.2 6.8 6.8 0 0020 14.5z" fill="currentColor" stroke="none" />),
  sun: S(
    <>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </>,
  ),
  shadowLow: S(<circle cx="12" cy="12" r="7.5" fill="currentColor" stroke="none" />),
  shadowHigh: S(
    <>
      <circle cx="12" cy="12" r="7.5" />
      <path d="M12 4.5v15M4.5 12h15" stroke-width="1" opacity="0.5" />
    </>,
  ),
  wbCool: S(<path d="M12 2v20M4.5 6l15 12M19.5 6l-15 12M2 12h20" stroke-width="1.8" />),
  wbWarm: S(
    <>
      <circle cx="12" cy="12" r="4.5" fill="currentColor" stroke="none" />
      <path d="M12 2v2.5M12 19.5V22M4.2 4.2l1.8 1.8M18 18l1.8 1.8M2 12h2.5M19.5 12H22M4.2 19.8L6 18M18 6l1.8-1.8" />
    </>,
  ),
  minus: S(<path d="M5 12h14" />),
  plus: S(<path d="M12 5v14M5 12h14" />),
};

export function rulerIcon(name: RulerIconName): JSX.Element {
  return END[name];
}

export function ResetIcon() {
  return S(
    <>
      <path d="M3 12a9 9 0 109-9 9 9 0 00-6.4 2.7L3 8" />
      <path d="M3 3v5h5" />
    </>,
  );
}

export function CycleIcon() {
  return S(
    <>
      <path d="M17 2.5l4 4-4 4" />
      <path d="M3 12.5v-2a4 4 0 014-4h14" />
      <path d="M7 21.5l-4-4 4-4" />
      <path d="M21 11.5v2a4 4 0 01-4 4H3" />
    </>,
  );
}
