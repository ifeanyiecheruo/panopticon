/** A rect as fractions of the frame, top down - a segment's area of interest. */
export interface AreaRect {
  l: number;
  t: number;
  r: number;
  b: number;
}

/** The tones inside an area: its black and white points and its median, 0-1. */
export interface AreaLevels {
  lo: number;
  hi: number;
  mid: number;
}

/**
 * The levels of the pixels inside `area`: the luma below which `clip` of them
 * fall (black), above which `clip` do (white), and the median. `rgba` is a
 * w x h readback, bottom row first. Null when the area covers no pixel.
 */
export function areaLevels(rgba: Uint8Array, w: number, h: number, area: AreaRect, clip: number): AreaLevels | null {
  const hist = new Uint32Array(256);
  const x0 = Math.max(0, Math.floor(area.l * w));
  const x1 = Math.min(w, Math.ceil(area.r * w));
  // Rows run bottom up; the area top down.
  const r0 = Math.max(0, Math.floor((1 - area.b) * h));
  const r1 = Math.min(h, Math.ceil((1 - area.t) * h));
  let total = 0;
  for (let r = r0; r < r1; r++) {
    for (let x = x0; x < x1; x++) {
      const o = (r * w + x) * 4;
      hist[Math.round(0.2126 * rgba[o] + 0.7152 * rgba[o + 1] + 0.0722 * rgba[o + 2])]++;
      total++;
    }
  }
  if (total === 0) return null;
  // The first value with more than `share` of the pixels at or below it.
  const at = (share: number) => {
    let acc = 0;
    for (let v = 0; v < 256; v++) {
      acc += hist[v];
      if (acc > share * total) return v / 255;
    }
    return 1;
  };
  return { lo: at(clip), hi: at(1 - clip), mid: at(0.5) };
}

/** `[lo, hi]` widened about their middle (and kept in 0-1) so they stretch
 * contrast by at most `maxGain`. */
export function capGain(lo: number, hi: number, maxGain: number): [number, number] {
  const span = Math.max(hi - lo, 1 / Math.max(1, maxGain));
  const mid = Math.min(Math.max((lo + hi) / 2, span / 2), 1 - span / 2);
  return [mid - span / 2, mid + span / 2];
}

/**
 * The gamma that, after the stretch to `[lo, hi]`, puts `mid` at mid-grey -
 * so an area sitting mostly in the shadows (or highlights) of its own range is
 * opened up rather than only spread out. Kept within `[min, max]`.
 */
export function gammaFor(lo: number, hi: number, mid: number, min: number, max: number): number {
  const m = Math.min(0.95, Math.max(0.05, (mid - lo) / Math.max(1e-3, hi - lo)));
  return Math.min(max, Math.max(min, Math.log(0.5) / Math.log(m)));
}
