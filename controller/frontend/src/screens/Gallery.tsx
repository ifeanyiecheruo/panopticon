import { useCallback, useEffect, useState } from 'preact/hooks';
import { ListPhones, ListClips, TrashClip, type PhoneView, type ClipView } from '../api';
import { groupByDay, clipKey, rangeKeys, stepKey } from '../lib/clips';
import { fmtDuration } from '../lib/format';
import { DayGroupList, type ClickMods } from '../components/ClipTiles';
import { ClipPlayer } from '../components/ClipPlayer';
import { TrashIcon } from '../lib/icons';

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

  // Refetch only on a real input change (filter or a post-mutation reload) —
  // never on selection. Keep the previous list painted during the refetch so
  // the split view doesn't flash.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [p, c] = await Promise.all([
          ListPhones(),
          ListClips(galleryFilter === 'all' ? '' : galleryFilter),
        ]);
        if (!cancelled) {
          setPhones(p);
          setClips(c);
        }
      } catch (err) {
        if (!cancelled) setError(String(err));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [galleryFilter, reload]);

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
    setBusy(true);
    try {
      for (const c of targets) await TrashClip(c.phoneId, c.clipId);
    } finally {
      setBusy(false);
    }
    setSelectedKeys(new Set());
    setAnchorKey(null);
    setReload((r) => r + 1);
  }, [clips, selectedKeys]);

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
  const viewerKey = anchorKey && selectedKeys.has(anchorKey) ? anchorKey : [...selectedKeys][0] ?? null;
  const viewer = orderedClips.find((c) => clipKey(c) === viewerKey) || null;
  const days = groupByDay(orderedClips);

  const handleSelect = (key: string, mods: ClickMods) => {
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
    const next = idx >= 0 ? orderedClips[idx + 1] : undefined;
    if (next) {
      const k = clipKey(next);
      setSelectedKeys(new Set([k]));
      setAnchorKey(k);
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
              <ClipPlayer clip={viewer} controls onFinished={handleClipFinished} />
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
        <div className="clip-list-pane">
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
