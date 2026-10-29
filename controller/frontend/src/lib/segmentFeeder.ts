/** One segment on a SegmentFeeder's timeline. */
export interface TimelineEntry {
  /** Stable id - the same segment keeps the same key across setTimeline calls. */
  key: string;
  /** Where to fetch it as fragmented MP4 (the controller's /fmp4/ route). */
  url: string;
  /** Expected length in seconds; replaced by the real one once appended. */
  dur: number;
}

interface Item extends TimelineEntry {
  /** Seconds on the media timeline: exact once appended, estimated before. */
  start: number;
  appended: boolean;
}

/** Keep this much fetched ahead of the playhead... */
const AHEAD_S = 15;
/** ...and drop what is further than this behind it. */
const BEHIND_S = 10;

/**
 * Plays a list of segments as one continuous stream on one `<video>`, through
 * one MediaSource and one SourceBuffer. Segments are appended back to back in
 * `sequence` mode, so the boundary between two of them - or between the last
 * segment of one clip and the first of the next - is just the next frame, with
 * no element swap or `load()` for anything to notice.
 *
 * Everything that touches the SourceBuffer runs in one loop (`pump`), one
 * operation at a time, re-reading the playhead between operations; seeks and
 * timeline changes only record what they want and kick the loop. The buffer is
 * kept as one contiguous run of whole segments: a seek outside it clears it
 * and starts a new run at the target, so a gap between two runs can never
 * stall playback.
 */
export class SegmentFeeder {
  /** Called once if a segment can't be fetched or appended; the feeder stops. */
  onError: (message: string) => void = () => {};

  private readonly ms = new MediaSource();
  private readonly objectUrl: string;
  private readonly opened: Promise<void>;
  private sb: SourceBuffer | null = null;
  private codec = '';
  private items: Item[] = [];
  /** No more entries will follow the last one: end the stream there. */
  private final = false;
  /** A seek waiting for its segment to be appended. */
  private target: number | null = null;
  /** Buffered media from here on belongs to entries setTimeline dropped. */
  private truncateAt: number | null = null;
  private fetching: { item: Item; ctrl: AbortController } | null = null;
  private pumping = false;
  private again = false;
  private stopped = false;

  constructor(private readonly video: HTMLVideoElement, entries: TimelineEntry[], final: boolean) {
    this.objectUrl = URL.createObjectURL(this.ms);
    this.opened = new Promise((resolve) => this.ms.addEventListener('sourceopen', () => resolve(), { once: true }));
    video.src = this.objectUrl;
    video.addEventListener('timeupdate', this.kick);
    video.addEventListener('waiting', this.kick);
    this.setTimeline(entries, final);
  }

  dispose() {
    this.stopped = true;
    this.fetching?.ctrl.abort();
    this.video.removeEventListener('timeupdate', this.kick);
    this.video.removeEventListener('waiting', this.kick);
    URL.revokeObjectURL(this.objectUrl);
  }

  /**
   * Replace the upcoming timeline. `entries` must start with an entry already
   * on it (anything before that is dropped; its media ages out like any played
   * media) - entries in common are kept as they are and the rest replaced.
   * Returns false, changing nothing, when the timeline doesn't continue the
   * current one; the caller then needs a fresh feeder.
   */
  setTimeline(entries: TimelineEntry[], final: boolean): boolean {
    const from = entries.length ? this.items.findIndex((it) => it.key === entries[0].key) : -1;
    if (from < 0 && this.items.length > 0) return false;
    this.final = final;
    const old = this.items.slice(Math.max(0, from));
    let same = 0;
    while (same < old.length && same < entries.length && old[same].key === entries[same].key) same++;

    // Media appended for entries that are going away has to come out of the buffer.
    for (const it of old.slice(same)) {
      if (it.appended) this.truncateAt = Math.min(this.truncateAt ?? Infinity, it.start);
      if (this.fetching?.item === it) this.fetching.ctrl.abort();
    }
    const items = old.slice(0, same);
    let t = items.length ? items[items.length - 1].start + items[items.length - 1].dur : 0;
    for (const e of entries.slice(same)) {
      items.push({ ...e, start: t, appended: false });
      t += e.dur;
    }
    this.items = items;
    this.kick();
    return true;
  }

  has(key: string): boolean {
    return this.items.some((it) => it.key === key);
  }

  /** Where the entry `key` starts on the media timeline, or null. */
  startOf(key: string): number | null {
    return this.items.find((it) => it.key === key)?.start ?? null;
  }

  /** Move the playhead to `t` (media timeline seconds), fetching if needed. */
  seek(t: number) {
    const it = this.items[this.indexAt(t)];
    if (it?.appended) {
      this.target = null;
      this.video.currentTime = t;
      return;
    }
    this.target = t;
    // A drag across the seek bar retargets faster than segments load: drop the
    // one in flight unless it's the one now wanted.
    if (this.fetching && this.fetching.item !== it) this.fetching.ctrl.abort();
    this.kick();
  }

  private kick = () => {
    void this.pump();
  };

  private async pump() {
    if (this.pumping) {
      this.again = true;
      return;
    }
    this.pumping = true;
    try {
      await this.opened;
      do {
        this.again = false;
        while (!this.stopped && (await this.step()));
      } while (this.again && !this.stopped);
    } catch (e) {
      if (!this.stopped) {
        this.stopped = true;
        this.onError(e instanceof Error ? e.message : String(e));
      }
    } finally {
      this.pumping = false;
    }
  }

  /** Do the next thing the buffer needs; false when there is nothing to do. */
  private async step(): Promise<boolean> {
    if (this.truncateAt !== null) {
      const at = this.truncateAt;
      this.truncateAt = null;
      if (this.sb && this.bufferedEnd() > at) await this.op(() => this.sb!.remove(at, Infinity));
      return true;
    }
    if (this.items.length === 0) return false;

    const t = this.target ?? this.video.currentTime;
    const i = this.indexAt(t);
    const it = this.items[i];
    if (!it.appended) {
      // Next to the buffered run it just extends it; anywhere else starts a new run.
      const contiguous = i > 0 && this.items[i - 1].appended;
      if (!contiguous) await this.clear();
      await this.append(it, contiguous ? this.items[i - 1] : null);
      return true;
    }
    if (this.target !== null) {
      this.video.currentTime = Math.min(Math.max(this.target, it.start), it.start + it.dur - 0.001);
      this.target = null;
      return true;
    }

    let last = i;
    while (last + 1 < this.items.length && this.items[last + 1].appended) last++;
    const runEnd = this.items[last].start + this.items[last].dur;
    if (last + 1 < this.items.length && runEnd - t < AHEAD_S) {
      await this.append(this.items[last + 1], this.items[last]);
      return true;
    }
    if (await this.evict(t - BEHIND_S)) return true;
    if (this.final && last === this.items.length - 1 && this.ms.readyState === 'open') {
      // Lets the element reach `ended`. Appending again later reopens the source.
      this.ms.endOfStream();
    }
    return false;
  }

  /** Fetch `it` and append it right after `prev`, or at its own estimated start. */
  private async append(it: Item, prev: Item | null) {
    const ctrl = new AbortController();
    this.fetching = { item: it, ctrl };
    let buf: ArrayBuffer;
    let codec: string;
    try {
      const res = await fetch(it.url, { signal: ctrl.signal });
      if (!res.ok) throw new Error(`segment unavailable (${res.status})`);
      codec = res.headers.get('X-Codec') ?? '';
      buf = await res.arrayBuffer();
    } catch (e) {
      if (ctrl.signal.aborted) return; // retargeted; the next step works out what's wanted now
      throw e;
    } finally {
      this.fetching = null;
    }
    if (this.stopped || !this.items.includes(it)) return;

    const type = `video/mp4; codecs="${codec}"`;
    if (!this.sb) {
      this.sb = this.ms.addSourceBuffer(type);
      // Sequence mode places each segment where timestampOffset says,
      // whatever times its own fragments carry.
      this.sb.mode = 'sequence';
    } else if (codec !== this.codec) {
      this.sb.changeType(type); // a different phone or profile in the play-through
    }
    this.codec = codec;

    const start = prev ? prev.start + prev.dur : it.start;
    this.sb.timestampOffset = start;
    try {
      await this.op(() => this.sb!.appendBuffer(buf));
    } catch (e) {
      if (!(e instanceof DOMException && e.name === 'QuotaExceededError')) throw e;
      // Full: drop everything before the playing segment and try once more.
      await this.evict(this.video.currentTime);
      this.sb.timestampOffset = start;
      await this.op(() => this.sb!.appendBuffer(buf));
    }
    it.start = start;
    it.dur = this.bufferedEndFrom(start) - start;
    it.appended = true;
    // Later estimates follow on from the real end.
    for (let j = this.items.indexOf(it) + 1; j < this.items.length && !this.items[j].appended; j++) {
      this.items[j].start = this.items[j - 1].start + this.items[j - 1].dur;
    }
  }

  /** Drop buffered media before the segment containing `cut`. */
  private async evict(cut: number): Promise<boolean> {
    const sb = this.sb;
    if (!sb || sb.buffered.length === 0) return false;
    // Only whole segments go, so what stays buffered starts on a keyframe and
    // every segment still marked appended is fully there. (Before the first
    // entry is media setTimeline dropped, which can go at any point.)
    const end = cut < this.items[0].start ? cut : this.items[this.indexAt(cut)].start;
    if (end - sb.buffered.start(0) < 1) return false;
    await this.op(() => sb.remove(0, end));
    for (const it of this.items) {
      if (it.appended && it.start + it.dur <= end + 0.001) it.appended = false;
    }
    return true;
  }

  private async clear() {
    if (this.sb && this.sb.buffered.length > 0) await this.op(() => this.sb!.remove(0, Infinity));
    for (const it of this.items) it.appended = false;
  }

  private indexAt(t: number): number {
    const i = this.items.findIndex((it) => t < it.start + it.dur);
    return i < 0 ? this.items.length - 1 : i;
  }

  private bufferedEnd(): number {
    const b = this.sb?.buffered;
    return b && b.length ? b.end(b.length - 1) : 0;
  }

  /** End of the buffered range that `t` falls in. */
  private bufferedEndFrom(t: number): number {
    const b = this.sb!.buffered;
    for (let i = 0; i < b.length; i++) {
      if (b.start(i) <= t + 0.01 && t < b.end(i)) return b.end(i);
    }
    return t;
  }

  /** Run one SourceBuffer operation and wait for it to finish. */
  private op(f: () => void): Promise<void> {
    const sb = this.sb!;
    return new Promise((resolve, reject) => {
      const done = () => {
        sb.removeEventListener('updateend', done);
        sb.removeEventListener('error', fail);
        resolve();
      };
      const fail = () => {
        sb.removeEventListener('updateend', done);
        sb.removeEventListener('error', fail);
        reject(new Error('the segment could not be decoded'));
      };
      sb.addEventListener('updateend', done);
      sb.addEventListener('error', fail);
      try {
        f();
      } catch (e) {
        sb.removeEventListener('updateend', done);
        sb.removeEventListener('error', fail);
        reject(e);
      }
    });
  }
}
