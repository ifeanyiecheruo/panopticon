import { useEffect, useState } from 'preact/hooks';
import { ListTrash, RestoreClip, DeleteClipPermanently, EmptyTrash, type ClipView } from '../api';
import { groupByDay, clipKey } from '../lib/clips';
import { fmtDuration } from '../lib/format';
import { DayGroupList } from '../components/ClipTiles';
import { ClipPlayer } from '../components/ClipPlayer';
import { TrashIcon, RestoreIcon } from '../lib/icons';

interface TrashProps {
  trashSelected: string | null;
  trashConfirmingEmpty: boolean;
  onSelectClip: (key: string) => void;
  onAutoSelect: (key: string) => void;
  onOpenConfirmEmpty: () => void;
  onCancelConfirmEmpty: () => void;
  onEmptied: () => void;
  onRestored: () => void;
  onDeleted: () => void;
}

export function Trash({
  trashSelected,
  trashConfirmingEmpty,
  onSelectClip,
  onAutoSelect,
  onOpenConfirmEmpty,
  onCancelConfirmEmpty,
  onEmptied,
  onRestored,
  onDeleted,
}: TrashProps) {
  const [clips, setClips] = useState<ClipView[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  // See Gallery.tsx: this component remounts on every navigate() call (the
  // parent bumps a key), which is what re-triggers this fetch — mirroring
  // the vanilla version's full re-render-and-refetch on every navigation,
  // including toggling the empty-trash confirm dialog.
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
  }, []);

  useEffect(() => {
    if (!trashSelected && clips && clips.length > 0) {
      onAutoSelect(clipKey(clips[0]));
    }
  }, [clips, trashSelected, onAutoSelect]);

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

  const effectiveSelected = trashSelected ?? (clips.length > 0 ? clipKey(clips[0]) : null);
  const selected = clips.find((c) => clipKey(c) === effectiveSelected) || clips[0] || null;
  const days = groupByDay(clips);

  const handleConfirmEmpty = async () => {
    await EmptyTrash();
    onEmptied();
  };
  const handleRestore = async () => {
    if (!selected) return;
    await RestoreClip(selected.phoneId, selected.clipId);
    onRestored();
  };
  const handleDelete = async () => {
    if (!selected) return;
    await DeleteClipPermanently(selected.phoneId, selected.clipId);
    onDeleted();
  };
  const handleClipFinished = () => {
    if (!selected) return;
    const idx = clips.findIndex((c) => clipKey(c) === clipKey(selected));
    const next = idx >= 0 ? clips[idx + 1] : undefined;
    if (next) onAutoSelect(clipKey(next));
  };

  return (
    <>
      <div className="header">
        <div>
          <h1>Trash</h1>
          <div className="sub">
            {clips.length} clip{clips.length === 1 ? '' : 's'}
          </div>
        </div>
        <div className="header-actions">
          <button className="btn danger" disabled={clips.length === 0} onClick={onOpenConfirmEmpty}>
            <TrashIcon /> Empty trash
          </button>
        </div>
      </div>
      {trashConfirmingEmpty && (
        <div className="body-scroll" style={{ paddingBottom: 0 }}>
          <div className="confirm-box">
            <p>
              Permanently delete {clips.length} clip{clips.length === 1 ? '' : 's'}? This can't be undone.
            </p>
            <div className="confirm-actions">
              <button className="btn danger" onClick={handleConfirmEmpty}>
                Delete all
              </button>
              <button className="btn" onClick={onCancelConfirmEmpty}>
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
      <div className="gallery-layout">
        <div className="viewer-pane">
          {selected ? (
            <>
              {/* No `controls` here — matches the pre-split trash viewer
                  (its video element had preload/poster/src but no controls
                  attribute, unlike the Gallery viewer). */}
              <ClipPlayer clip={selected} onFinished={handleClipFinished} />
              <div className="viewer-meta">
                <div>
                  <div className="who">{selected.phoneName}</div>
                  <div className="when">
                    {new Date(selected.startedAtMs).toLocaleString()} · {fmtDuration(selected.durationMs)} ·{' '}
                    {selected.segmentCount} segment{selected.segmentCount === 1 ? '' : 's'}
                  </div>
                </div>
              </div>
              <div className="viewer-actions">
                <button className="btn" onClick={handleRestore}>
                  <RestoreIcon /> Restore
                </button>
                <button className="btn danger" onClick={handleDelete}>
                  <TrashIcon /> Delete
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
            <DayGroupList days={days} selectedKey={selected ? clipKey(selected) : null} onSelect={onSelectClip} />
          )}
        </div>
      </div>
    </>
  );
}
