import { useEffect, useState } from 'preact/hooks';
import { ListPhones, ListClips, TrashClip, type PhoneView, type ClipView } from '../api';
import { groupByDay, clipKey } from '../lib/clips';
import { fmtDuration } from '../lib/format';
import { DayGroupList } from '../components/ClipTiles';
import { TrashIcon } from '../lib/icons';

interface GalleryProps {
  galleryFilter: string;
  gallerySelected: string | null;
  onFilterChange: (filter: string) => void;
  onSelectClip: (key: string) => void;
  onAutoSelect: (key: string) => void;
  onTrashed: () => void;
}

export function Gallery({ galleryFilter, gallerySelected, onFilterChange, onSelectClip, onAutoSelect, onTrashed }: GalleryProps) {
  const [phones, setPhones] = useState<PhoneView[] | null>(null);
  const [clips, setClips] = useState<ClipView[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  // This component remounts (via a `key` on the parent's route switch) on
  // every navigate() call — clicking a filter chip, a clip tile, or trashing
  // the selected clip — which is what re-triggers this fetch, matching the
  // vanilla version's always-refetch-on-navigate behavior. gallerySelected
  // is intentionally not a dependency here: changing it alone (the silent
  // auto-select below) must not cause a second fetch.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [p, c] = await Promise.all([ListPhones(), ListClips(galleryFilter === 'all' ? '' : galleryFilter)]);
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [galleryFilter]);

  // Mirrors the vanilla version persisting its first-clip default selection
  // back into shared state (without a re-render-triggering navigate call).
  useEffect(() => {
    if (!gallerySelected && clips && clips.length > 0) {
      onAutoSelect(clipKey(clips[0]));
    }
  }, [clips, gallerySelected, onAutoSelect]);

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

  const effectiveSelected = gallerySelected ?? (clips.length > 0 ? clipKey(clips[0]) : null);
  const selected = clips.find((c) => clipKey(c) === effectiveSelected) || clips[0] || null;
  const days = groupByDay(clips);

  const handleTrash = async () => {
    if (!selected) return;
    await TrashClip(selected.phoneId, selected.filename);
    onTrashed();
  };

  return (
    <>
      <div className="header">
        <div>
          <h1>Gallery</h1>
          <div className="sub">
            {clips.length} clip{clips.length === 1 ? '' : 's'}
          </div>
        </div>
      </div>
      <div className="chip-row">
        <button className={`chip ${galleryFilter === 'all' ? 'active' : ''}`} onClick={() => onFilterChange('all')}>
          All
        </button>
        {phones.map((p) => (
          <button key={p.id} className={`chip ${galleryFilter === p.id ? 'active' : ''}`} onClick={() => onFilterChange(p.id)}>
            {p.name}
          </button>
        ))}
      </div>
      <div className="gallery-layout">
        <div className="viewer-pane">
          {selected ? (
            <>
              <video controls preload="metadata" poster={selected.thumbnailUrl} src={selected.videoUrl}></video>
              <div className="viewer-meta">
                <div>
                  <div className="who">{selected.phoneName}</div>
                  <div className="when">
                    {new Date(selected.createdAtMs).toLocaleString()} · {fmtDuration(selected.durationMs)}
                  </div>
                </div>
              </div>
              <div className="viewer-actions">
                <button className="btn danger" onClick={handleTrash}>
                  <TrashIcon /> Trash
                </button>
              </div>
            </>
          ) : (
            <div className="viewer-empty">No clip selected</div>
          )}
        </div>
        <div className="clip-list-pane">
          {days.length === 0 ? (
            <div className="empty-note">No synced clips yet. Pair a phone and wait for the background sync loop to pull its footage.</div>
          ) : (
            <DayGroupList days={days} selectedKey={selected ? clipKey(selected) : null} onSelect={onSelectClip} />
          )}
        </div>
      </div>
    </>
  );
}
