import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { ListPhones, ListClips, TrashClip, MarkClipWatched, type PhoneView, type ClipView } from '../api';
import {
  groupByDay,
  clipKey,
  rangeKeys,
  stepKey,
  keyAfterRemoval,
  viewerKeyOf,
  withWatched,
  pruneSelection,
  sameClips,
} from '../lib/clips';
import { fmtDuration } from '../lib/format';
import { DayGroupList, type ClickMods } from '../components/ClipTiles';
import { ClipPlayer } from '../components/ClipPlayer';
import { TrashIcon } from '../lib/icons';

/** How often the gallery re-reads the local clip list. Cheap (a local DB
 * query) and well under the phone sync interval, so new footage shows up
 * within a few seconds of landing. */
const POLL_MS = 5000;

interface GalleryProps {
  galleryFilter: string;
  onFilterChange: (filter: string) => void;
}

export function Gallery({ galleryFilter, onFilterChange }: GalleryProps) {
  const [phones, setPhones] = useState<PhoneView[] | null>(null);
  const [clips, setClips] = useState<ClipView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [busy, setBusy] = useState(false);

  const [selectedKeys, setSelectedKeys] = useState<Set<string>>(new Set());
  const [anchorKey, setAnchorKey] = useState<string | null>(null);
  // Set only when the selection advances because a clip finished — tells
  // ClipPlayer this is a continuation so it plays without a fresh gesture.
  // Cleared on any manual pick.
  const [autoplay, setAutoplay] = useState(false);

  const listRef = useRef<HTMLDivElement>(null);
  // The tile to hold still across a refresh and where it sat on screen - see
  // captureScrollAnchor.
  const scrollAnchor = useRef<{ key: string; top: number } | null>(null);
  // Mirrors of render state for the async fetch, which closes over stale values.
  const viewerKeyRef = useRef<string | null>(null);
  viewerKeyRef.current = viewerKeyOf(selectedKeys, anchorKey);
  const clipsRef = useRef<ClipView[] | null>(null);
  clipsRef.current = clips;

  // Refetch on a real input change (filter or a post-mutation reload) and on
  // the poll below — never on selection. Keep the previous list painted during
  // the refetch so the split view doesn't flash.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [p, c] = await Promise.all([
          ListPhones(),
          ListClips(galleryFilter === 'all' ? '' : galleryFilter),
        ]);
        if (!cancelled) {
          setPhones((prev) => (prev && JSON.stringify(prev) === JSON.stringify(p) ? prev : p));
          if (!sameClips(clipsRef.current, c)) {
            captureScrollAnchor();
            setClips(c);
          }
        }
      } catch (err) {
        if (!cancelled) setError(String(err));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [galleryFilter, reload]);

  // The sync loop pulls new footage in the background, so poll for it while
  // the window is visible. Skipped mid-trash so a poll can't race the removal.
  useEffect(() => {
    if (busy) return;
    const id = window.setInterval(() => {
      if (!document.hidden) setReload((r) => r + 1);
    }, POLL_MS);
    return () => window.clearInterval(id);
  }, [busy]);

  // New clips land at the top of the list and push everything below them
  // down. Remember where the selected tile sits on screen (or, if it's
  // scrolled out of view, the first tile that is) so the layout effect below
  // can put it back after the new list renders.
  function captureScrollAnchor() {
    const list = listRef.current;
    const scroller = list?.closest('.main');
    if (!list || !scroller) return;
    const view = scroller.getBoundingClientRect();
    const tiles = [...list.querySelectorAll<HTMLElement>('[data-clip-key]')];
    const visible = (el: HTMLElement) => {
      const r = el.getBoundingClientRect();
      return r.bottom > view.top && r.top < view.bottom;
    };
    const selected = tiles.find((el) => el.dataset.clipKey === viewerKeyRef.current);
    const el = selected && visible(selected) ? selected : tiles.find(visible);
    scrollAnchor.current = el ? { key: el.dataset.clipKey!, top: el.getBoundingClientRect().top } : null;
  }

  useLayoutEffect(() => {
    const a = scrollAnchor.current;
    scrollAnchor.current = null;
    const list = listRef.current;
    const scroller = list?.closest('.main');
    if (!a || !list || !scroller) return;
    const el = [...list.querySelectorAll<HTMLElement>('[data-clip-key]')].find((t) => t.dataset.clipKey === a.key);
    if (!el) return;
    // Measured after layout, so this is also a no-op if the browser's own
    // scroll anchoring already compensated.
    scroller.scrollTop += el.getBoundingClientRect().top - a.top;
  }, [clips]);

  // A refresh can drop a selected clip (trashed from elsewhere); keep the
  // rest of the selection, and let the default-select below cover an empty one.
  useEffect(() => {
    if (!clips) return;
    const kept = pruneSelection(clips, selectedKeys);
    if (kept !== selectedKeys) setSelectedKeys(kept);
  }, [clips, selectedKeys]);

  // Filter change starts a fresh selection.
  useEffect(() => {
    setSelectedKeys(new Set());
    setAnchorKey(null);
  }, [galleryFilter]);

  // Default-select the first clip once a list is in hand and nothing is selected.
  useEffect(() => {
    if (clips && clips.length > 0 && selectedKeys.size === 0) {
      const k = clipKey(clips[0]);
      setSelectedKeys(new Set([k]));
      setAnchorKey(k);
    }
  }, [clips, selectedKeys.size]);

  const doTrash = useCallback(async () => {
    if (!clips) return;
    const targets = clips.filter((c) => selectedKeys.has(clipKey(c)));
    if (targets.length === 0) return;
    const removed = new Set(targets.map(clipKey));
    const nextKey = keyAfterRemoval(clips, removed, viewerKeyOf(selectedKeys, anchorKey));
    setBusy(true);
    try {
      for (const c of targets) await TrashClip(c.phoneId, c.clipId);
    } finally {
      setBusy(false);
    }
    // Drop them locally first so neither the selection nor the default-select
    // effect can land on a trashed clip before the refetch lands.
    setClips((prev) => prev?.filter((c) => !removed.has(clipKey(c))) ?? null);
    setAutoplay(false);
    setSelectedKeys(nextKey ? new Set([nextKey]) : new Set());
    setAnchorKey(nextKey);
    setReload((r) => r + 1);
  }, [clips, selectedKeys, anchorKey]);

  const handleStarted = useCallback((c: ClipView) => {
    if (c.watched) return;
    void MarkClipWatched(c.phoneId, c.clipId).catch(() => {});
    setClips((prev) => withWatched(prev, clipKey(c)));
  }, []);

  // Keyboard: arrows move/extend the selection, Delete trashes it. Ignored while
  // a form control has focus.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null;
      if (t && (t.tagName === 'INPUT' || t.tagName === 'SELECT' || t.tagName === 'TEXTAREA')) return;
      if (!clips || clips.length === 0) return;
      const cur = anchorKey ?? ([...selectedKeys][0] || clipKey(clips[0]));
      if (['ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowUp'].includes(e.key)) {
        e.preventDefault();
        const target = stepKey(clips, cur, e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : -1);
        if (!target) return;
        setAutoplay(false);
        if (e.shiftKey) {
          setSelectedKeys(rangeKeys(clips, anchorKey, target));
        } else {
          setSelectedKeys(new Set([target]));
          setAnchorKey(target);
        }
      } else if (e.key === 'Delete' || e.key === 'Backspace') {
        e.preventDefault();
        void doTrash();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [clips, selectedKeys, anchorKey, doTrash]);

  if (error) {
    return (
      <div className="body-scroll">
        <div className="empty-note">Error: {error}</div>
      </div>
    );
  }

  if (!phones || !clips) {
    return <div className="body-scroll">Loading…</div>;
  }

  const orderedClips = clips;
  const viewerKey = viewerKeyOf(selectedKeys, anchorKey);
  const viewerIdx = viewerKey ? orderedClips.findIndex((c) => clipKey(c) === viewerKey) : -1;
  const viewer = viewerIdx >= 0 ? orderedClips[viewerIdx] : null;
  // orderedClips is newest-first, so forward-in-time is the lower index (see handleClipFinished).
  const nextClip = viewerIdx > 0 ? orderedClips[viewerIdx - 1] : null;
  const days = groupByDay(orderedClips);

  const handleSelect = (key: string, mods: ClickMods) => {
    setAutoplay(false);
    if (mods.shift) {
      setSelectedKeys(rangeKeys(orderedClips, anchorKey, key));
      return;
    }
    if (mods.ctrl) {
      const next = new Set(selectedKeys);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      setSelectedKeys(next);
      setAnchorKey(key);
      return;
    }
    setSelectedKeys(new Set([key]));
    setAnchorKey(key);
  };

  const handleClipFinished = () => {
    if (!viewer) return;
    const idx = orderedClips.findIndex((c) => clipKey(c) === clipKey(viewer));
    // orderedClips is newest-first (ListClips sorts DESC), so the next clip
    // forward in time sits at a *lower* index, not idx + 1.
    const next = idx > 0 ? orderedClips[idx - 1] : undefined;
    if (next) {
      const k = clipKey(next);
      setAutoplay(true); // keep playing through the next clip
      setSelectedKeys(new Set([k]));
      setAnchorKey(k);
    } else {
      setAutoplay(false); // reached the end of the list — stop the play-through
    }
  };

  const count = selectedKeys.size;

  return (
    <>
      <div className="header">
        <div>
          <h1>Gallery</h1>
          <div className="sub">
            {clips.length} clip{clips.length === 1 ? '' : 's'}
            {count > 1 && ` · ${count} selected`}
          </div>
        </div>
      </div>
      <div className="chip-row">
        <button
          className={`chip ${galleryFilter === 'all' ? 'active' : ''}`}
          onClick={() => onFilterChange('all')}
        >
          All
        </button>
        {phones.map((p) => (
          <button
            key={p.id}
            className={`chip ${galleryFilter === p.id ? 'active' : ''}`}
            onClick={() => onFilterChange(p.id)}
          >
            {p.name}
          </button>
        ))}
      </div>
      <div className="gallery-layout">
        <div className="viewer-pane">
          {viewer ? (
            <>
              <ClipPlayer
                clip={viewer}
                controls
                autoplay={autoplay}
                onFinished={handleClipFinished}
                onStarted={handleStarted}
                nextClip={nextClip}
              />
              <div className="viewer-meta">
                <div>
                  <div className="who">{viewer.phoneName}</div>
                  <div className="when">
                    {new Date(viewer.startedAtMs).toLocaleString()} · {fmtDuration(viewer.durationMs)}
                  </div>
                </div>
              </div>
              <div className="viewer-actions">
                <button className="btn danger" disabled={busy} onClick={doTrash}>
                  <TrashIcon /> {count > 1 ? `Trash ${count} clips` : 'Trash'}
                </button>
              </div>
            </>
          ) : (
            <div className="viewer-empty">No clip selected</div>
          )}
        </div>
        <div className="clip-list-pane" ref={listRef}>
          {days.length === 0 ? (
            <div className="empty-note">
              No synced clips yet. Pair a phone and wait for the background sync loop to pull its
              footage.
            </div>
          ) : (
            <DayGroupList days={days} selectedKeys={selectedKeys} onSelect={handleSelect} />
          )}
        </div>
      </div>
    </>
  );
}
