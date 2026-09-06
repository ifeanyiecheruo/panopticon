import type { ClipView } from '../api';
import type { DayGroup } from '../lib/clips';
import { clipKey } from '../lib/clips';
import { fmtDuration } from '../lib/format';

interface ClipTileProps {
  clip: ClipView;
  active: boolean;
  onClick: () => void;
}

function ClipTile({ clip: c, active, onClick }: ClipTileProps) {
  return (
    <div
      className={`ctile ${active ? 'active' : ''}`}
      style={c.hasThumbnail ? { backgroundImage: `url('${c.thumbnailUrl}')` } : undefined}
      onClick={onClick}
    >
      {!c.hasThumbnail && <div className="noimg">no thumb</div>}
      <div className="cbar">
        <span className="cphone">{c.phoneName}</span>
        <span className="clen">{fmtDuration(c.durationMs)}</span>
      </div>
    </div>
  );
}

interface DayGroupListProps {
  days: DayGroup[];
  selectedKey: string | null;
  onSelect: (key: string) => void;
}

export function DayGroupList({ days, selectedKey, onSelect }: DayGroupListProps) {
  return (
    <>
      {days.map((group) => (
        <div className="day-group" key={group.day}>
          <div className="day-head">{group.day}</div>
          <div className="clip-grid">
            {group.clips.map((c) => (
              <ClipTile key={clipKey(c)} clip={c} active={selectedKey === clipKey(c)} onClick={() => onSelect(clipKey(c))} />
            ))}
          </div>
        </div>
      ))}
    </>
  );
}
