import type { ClipView } from '../api';
import type { DayGroup } from '../lib/clips';
import { clipKey } from '../lib/clips';
import { fmtDuration } from '../lib/format';

export interface ClickMods {
  shift: boolean;
  ctrl: boolean;
}

interface ClipTileProps {
  clip: ClipView;
  active: boolean;
  onClick: (mods: ClickMods) => void;
}

function ClipTile({ clip: c, active, onClick }: ClipTileProps) {
  return (
    <div
      className={`ctile ${active ? 'active' : ''}`}
      style={c.hasThumbnail ? { backgroundImage: `url('${c.thumbnailUrl}')` } : undefined}
      onClick={(e) => onClick({ shift: e.shiftKey, ctrl: e.ctrlKey || e.metaKey })}
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
  selectedKeys: Set<string>;
  onSelect: (key: string, mods: ClickMods) => void;
}

export function DayGroupList({ days, selectedKeys, onSelect }: DayGroupListProps) {
  return (
    <>
      {days.map((group) => (
        <div className="day-group" key={group.day}>
          <div className="day-head">{group.day}</div>
          <div className="clip-grid">
            {group.clips.map((c) => {
              const k = clipKey(c);
              return (
                <ClipTile
                  key={k}
                  clip={c}
                  active={selectedKeys.has(k)}
                  onClick={(mods) => onSelect(k, mods)}
                />
              );
            })}
          </div>
        </div>
      ))}
    </>
  );
}
