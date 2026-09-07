import { useEffect, useState } from 'preact/hooks';
import { ListPhones, type PhoneView } from '../api';
import { fmtBytes } from '../lib/format';
import { RefreshIcon } from '../lib/icons';
import { PhoneDetail } from './PhoneDetail';

interface FleetProps {
  selectedPhoneId: string | null;
  onSelectPhone: (phoneId: string) => void;
  onDeselect: () => void;
  onViewGallery: (phoneId: string) => void;
  onUnpaired: () => void;
}

export function Fleet({ selectedPhoneId, onSelectPhone, onDeselect, onViewGallery, onUnpaired }: FleetProps) {
  const [phones, setPhones] = useState<PhoneView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const p = await ListPhones();
        if (!cancelled) setPhones(p);
      } catch (err) {
        if (!cancelled) setError(String(err));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [reload]);

  if (error) {
    return (
      <div className="body-scroll">
        <div className="empty-note">Error: {error}</div>
      </div>
    );
  }

  if (!phones) {
    return (
      <>
        <div className="header">
          <h1>Fleet</h1>
        </div>
        <div className="body-scroll">Loading phones…</div>
      </>
    );
  }

  return (
    <div className="fleet-split">
      <aside className="fleet-master">
        <div className="fleet-master-head">
          <span>
            {phones.length} phone{phones.length === 1 ? '' : 's'}
          </span>
          <button className="btn small" onClick={() => setReload((r) => r + 1)} title="Refresh">
            <RefreshIcon />
          </button>
        </div>
        {phones.length === 0 ? (
          <div className="empty-note">
            No phones paired.
            <br />
            <br />
            Use <b>Add phone</b> in the left rail.
          </div>
        ) : (
          phones.map((p) => (
            // Name only — status/battery live in the detail pane, which polls
            // fresh; a second copy here just drifts out of sync.
            <button
              key={p.id}
              className={`fleet-row ${p.id === selectedPhoneId ? 'active' : ''}`}
              onClick={() => onSelectPhone(p.id)}
            >
              <span className="fleet-row-name">{p.name}</span>
            </button>
          ))
        )}
      </aside>

      <section className="fleet-detail">
        {selectedPhoneId ? (
          <PhoneDetail
            key={selectedPhoneId}
            phoneId={selectedPhoneId}
            onDeselect={onDeselect}
            onPhoneChanged={() => setReload((r) => r + 1)}
            onViewGallery={() => onViewGallery(selectedPhoneId)}
            onUnpaired={() => {
              setReload((r) => r + 1);
              onUnpaired();
            }}
          />
        ) : (
          <FleetSummary phones={phones} />
        )}
      </section>
    </div>
  );
}

function FleetSummary({ phones }: { phones: PhoneView[] }) {
  const totalDisk = phones.reduce((sum, p) => sum + (p.diskUsageBytes || 0), 0);
  return (
    <div className="fleet-summary">
      <h1>Fleet</h1>
      <div className="sub">
        {phones.length} phone{phones.length === 1 ? '' : 's'} paired · <b>{fmtBytes(totalDisk)}</b> archived
      </div>
      <div className="empty-note" style={{ marginTop: '18px' }}>
        {phones.length === 0
          ? 'Pair a phone to get started.'
          : 'Select a device on the left to view its live preview, controls, config and status.'}
      </div>
    </div>
  );
}
