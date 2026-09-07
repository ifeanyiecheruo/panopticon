// Same inline SVG glyphs as the vanilla version's icon() string map, ported
// to JSX components instead of raw markup strings.

export function LogoIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <path d="M3 11.5L12 4l9 7.5" />
      <path d="M5.5 10v9.5a1 1 0 001 1H9.5v-6.5h5V20.5H17.5a1 1 0 001-1V10" />
    </svg>
  );
}

export function FleetIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <rect x="3" y="4" width="7" height="7" rx="1.5" />
      <rect x="14" y="4" width="7" height="7" rx="1.5" />
      <rect x="3" y="15" width="7" height="7" rx="1.5" />
      <rect x="14" y="15" width="7" height="7" rx="1.5" />
    </svg>
  );
}

export function GalleryIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <path d="M3 9h18M8 4v5M16 4v5" />
    </svg>
  );
}

export function TrashIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <path d="M3 6h18M8 6V4a1 1 0 011-1h6a1 1 0 011 1v2m2 0l-1 14a1 1 0 01-1 1H7a1 1 0 01-1-1L5 6" />
    </svg>
  );
}

export function AddIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <path d="M12 5v14M5 12h14" />
    </svg>
  );
}

export function BackIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <path d="M15 18l-6-6 6-6" />
    </svg>
  );
}

export function RefreshIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <path d="M17 2.5l4 4-4 4" />
      <path d="M3 12.5v-2a4 4 0 014-4h14" />
      <path d="M7 21.5l-4-4 4-4" />
      <path d="M21 11.5v2a4 4 0 01-4 4H3" />
    </svg>
  );
}

export function RestoreIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <path d="M3 3v6h6" />
      <path d="M3.5 13a8.5 8.5 0 108-10.4" />
    </svg>
  );
}

export function RecordIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor" stroke="none">
      <circle cx="12" cy="12" r="7" />
    </svg>
  );
}

export function StopIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor" stroke="none">
      <rect x="6" y="6" width="12" height="12" rx="2" />
    </svg>
  );
}

export function LiveIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <circle cx="12" cy="12" r="2.5" fill="currentColor" stroke="none" />
      <path d="M6.3 6.3a8 8 0 000 11.4M17.7 6.3a8 8 0 010 11.4" />
      <path d="M3.5 3.5a13 13 0 000 17M20.5 3.5a13 13 0 010 17" opacity="0.5" />
    </svg>
  );
}

export function CalibrateIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <circle cx="12" cy="12" r="8" />
      <path d="M12 2v3M12 19v3M2 12h3M19 12h3" />
      <circle cx="12" cy="12" r="2" fill="currentColor" stroke="none" />
    </svg>
  );
}

export function UnpairIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <path d="M9 7H7a5 5 0 000 10h2M15 7h2a5 5 0 013.5 8.5" />
      <path d="M3 3l18 18" />
    </svg>
  );
}

export function CheckIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <path d="M20 6L9 17l-5-5" />
    </svg>
  );
}
