import { useCallback, useEffect, useState } from 'preact/hooks';
import { ListTrash, RestoreClip, DeleteClipPermanently, EmptyTrash, type ClipView } from '../api';
import { groupByDay, clipKey, rangeKeys, stepKey } from '../lib/clips';
import { fmtDuration } from '../lib/format';
import { DayGroupList, type ClickMods } from '../components/ClipTiles';
import { ClipPlayer } from '../components/ClipPlayer';
import { TrashIcon, RestoreIcon } from '../lib/icons';

export function Trash() {
  const [clips, setClips] = useState<ClipView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [busy, setBusy] = useState(false);
  const [confirmingEmpty, setConfirmingEmpty] = useState(false);

  const [selectedKeys, setSelectedKeys] = useState<Set<string>>(new Set());
  const [anchorKey, setAnchorKey] = useState<string | null>(null);
  // Set only when the selection advances because a clip finished — carries the
  // play-through across the ClipPlayer remount. Cleared on any manual pick.
  const [autoplay, setAutoplay] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const c = await ListTrash();
        if (!cancelled) setClips(c);
      } catch (err) {
        if (!cancelled) setError(String(err));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [reload]);

  useEffect(() => {
    if (clips && clips.length > 0 && selectedKeys.size === 0) {
      const k = clipKey(clips[0]);
      setSelectedKeys(new Set([k]));
      setAnchorKey(k);
    }
  }, [clips, selectedKeys.size]);

  const runBulk = useCallback(
    async (fn: (phoneId: string, clipId: string) => Promise<unknown>) => {
      if (!clips) return;
      const ts = clips.filter((c) => selectedKeys.has(clipKey(c)));
      if (ts.length === 0) return;
      setBusy(true);
      try {
        for (const c of ts) await fn(c.phoneId, c.clipId);
      } finally {
        setBusy(false);
      }
      setSelectedKeys(new Set());
      setAnchorKey(null);
      setReload((r) => r + 1);
    },
    [clips, selectedKeys],
  );

  // Keyboard: arrows move/extend the selection, Delete permanently deletes it.
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
      } else if ((e.key === 'Delete' || e.key === 'Backspace') && !confirmingEmpty) {
        e.preventDefault();
        void runBulk(DeleteClipPermanently);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [clips, selectedKeys, anchorKey, runBulk, confirmingEmpty]);

  if (error) {
    return (
      <div className="body-scroll">
        <div className="empty-note">Error: {error}</div>
      </div>
    );
  }

  if (!clips) {
    return <div className="body-scroll">Loading…</div>;
  }

  const orderedClips = clips;
  const viewerKey = anchorKey && selectedKeys.has(anchorKey) ? anchorKey : [...selectedKeys][0] ?? null;
  const viewer = orderedClips.find((c) => clipKey(c) === viewerKey) || null;
  const days = groupByDay(orderedClips);
  const count = selectedKeys.size;

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

  const handleConfirmEmpty = async () => {
    setBusy(true);
    try {
      await EmptyTrash();
    } finally {
      setBusy(false);
    }
    setConfirmingEmpty(false);
    setSelectedKeys(new Set());
    setAnchorKey(null);
    setReload((r) => r + 1);
  };

  const handleClipFinished = () => {
    if (!viewer) return;
    const idx = orderedClips.findIndex((c) => clipKey(c) === clipKey(viewer));
    const next = idx >= 0 ? orderedClips[idx + 1] : undefined;
    if (next) {
      const k = clipKey(next);
      setAutoplay(true); // keep playing through the next clip across the remount
      setSelectedKeys(new Set([k]));
      setAnchorKey(k);
    } else {
      setAutoplay(false); // reached the end of the list — stop the play-through
    }
  };

  return (
    <>
      <div className="header">
        <div>
          <h1>Trash</h1>
          <div className="sub">
            {clips.length} clip{clips.length === 1 ? '' : 's'}
            {count > 1 && ` · ${count} selected`}
          </div>
        </div>
        <div className="header-actions">
          <button
            className="btn danger"
            disabled={clips.length === 0 || busy}
            onClick={() => setConfirmingEmpty(true)}
          >
            <TrashIcon /> Empty trash
          </button>
        </div>
      </div>
      {confirmingEmpty && (
        <div className="body-scroll" style={{ paddingBottom: 0 }}>
          <div className="confirm-box">
            <p>
              Permanently delete {clips.length} clip{clips.length === 1 ? '' : 's'}? This can't be
              undone.
            </p>
            <div className="confirm-actions">
              <button className="btn danger" disabled={busy} onClick={handleConfirmEmpty}>
                Delete all
              </button>
              <button className="btn" onClick={() => setConfirmingEmpty(false)}>
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
      <div className="gallery-layout">
        <div className="viewer-pane">
          {viewer ? (
            <>
              <ClipPlayer
                key={viewerKey ?? undefined}
                clip={viewer}
                autoplay={autoplay}
                onFinished={handleClipFinished}
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
                <button className="btn" disabled={busy} onClick={() => runBulk(RestoreClip)}>
                  <RestoreIcon /> {count > 1 ? `Restore ${count}` : 'Restore'}
                </button>
                <button className="btn danger" disabled={busy} onClick={() => runBulk(DeleteClipPermanently)}>
                  <TrashIcon /> {count > 1 ? `Delete ${count}` : 'Delete'}
                </button>
              </div>
            </>
          ) : (
            <div className="viewer-empty">No clip selected</div>
          )}
        </div>
        <div className="clip-list-pane">
          {days.length === 0 ? (
            <div className="empty-note">Trash is empty.</div>
          ) : (
            <DayGroupList days={days} selectedKeys={selectedKeys} onSelect={handleSelect} />
          )}
        </div>
      </div>
    </>
  );
}
