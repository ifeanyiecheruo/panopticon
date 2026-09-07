import type { ClipView } from '../api';

export interface DayGroup {
  day: string;
  clips: ClipView[];
}

/** A "clip" (the gallery item) is a contiguous run of segments, uniquely
 * identified on the controller by (phoneId, clipId). Gallery/Trash selection is
 * tracked as that pair joined with '|'. */
export function clipKey(c: Pick<ClipView, 'clipId' | 'phoneId'>): string {
  return c.phoneId + '|' + c.clipId;
}

/** The inclusive set of clip keys between `anchorKey` and `targetKey` in the
 * flat display order — for shift-click range selection. Falls back to just the
 * target when the anchor is missing. */
export function rangeKeys(clips: ClipView[], anchorKey: string | null, targetKey: string): Set<string> {
  const keys = clips.map(clipKey);
  const ti = keys.indexOf(targetKey);
  const ai = anchorKey ? keys.indexOf(anchorKey) : -1;
  if (ti < 0 || ai < 0) return new Set([targetKey]);
  const [lo, hi] = ai <= ti ? [ai, ti] : [ti, ai];
  return new Set(keys.slice(lo, hi + 1));
}

/** The clip key one step (`dir` = +1 / -1) from `current` in flat display
 * order, clamped to the ends — for arrow-key navigation. */
export function stepKey(clips: ClipView[], current: string | null, dir: 1 | -1): string | null {
  if (clips.length === 0) return null;
  const keys = clips.map(clipKey);
  const i = current ? keys.indexOf(current) : -1;
  const next = Math.max(0, Math.min(keys.length - 1, (i < 0 ? 0 : i) + dir));
  return keys[next];
}

export function groupByDay(clips: ClipView[]): DayGroup[] {
  const byDay = new Map<string, ClipView[]>();
  for (const c of clips) {
    const day = new Date(c.startedAtMs).toLocaleDateString(undefined, {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    });
    if (!byDay.has(day)) byDay.set(day, []);
    byDay.get(day)!.push(c);
  }
  return Array.from(byDay.entries()).map(([day, dayClips]) => ({ day, clips: dayClips }));
}
