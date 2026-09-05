import { useEffect, useState } from 'preact/hooks';
import { ListPhones, type PhoneView } from '../api';
import { fmtBytes, statusLabel } from '../lib/format';
import { RefreshIcon } from '../lib/icons';

interface FleetProps {
  onSelectPhone: (phoneId: string) => void;
  onRefresh: () => void;
}

export function Fleet({ onSelectPhone, onRefresh }: FleetProps) {
  const [phones, setPhones] = useState<PhoneView[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    // try/catch (rather than .then/.catch) also catches a synchronous throw
    // from ListPhones() itself, not just a rejected promise.
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
  }, []);

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

  const totalDisk = phones.reduce((sum, p) => sum + (p.diskUsageBytes || 0), 0);

  return (
    <>
      <div className="header">
        <div>
          <h1>Fleet</h1>
          <div className="sub">
            {phones.length} phone{phones.length === 1 ? '' : 's'} paired · <b>{fmtBytes(totalDisk)}</b> archived
          </div>
        </div>
        <div className="header-actions">
          <button className="btn" onClick={onRefresh}>
            <RefreshIcon /> Refresh
          </button>
        </div>
      </div>
      <div className="body-scroll">
        {phones.length === 0 ? (
          <div className="empty-note">
            No phones paired yet.
            <br />
            <br />
            Use <b>Add phone</b> in the left rail to pair one.
          </div>
        ) : (
          <div className="grid">
            {phones.map((p) => (
              <PhoneCard key={p.id} phone={p} onClick={() => onSelectPhone(p.id)} />
            ))}
          </div>
        )}
      </div>
    </>
  );
}

function PhoneCard({ phone: p, onClick }: { phone: PhoneView; onClick: () => void }) {
  const statusClass = p.status === 'recording' ? 'recording' : p.status === 'unreachable' ? 'unreachable' : '';
  return (
    <div className={`pcard ${p.status === 'unreachable' ? 'is-unreachable' : ''}`} onClick={onClick}>
      <div className="top">
        <div className="name">{p.name}</div>
        {p.hasBattery && (
          <div className={`batt ${p.batteryPercent <= 20 ? 'low' : ''}`}>
            {p.batteryPercent}%{p.charging ? ' ⚡' : ''}
          </div>
        )}
      </div>
      <div className={`status-pill ${statusClass}`}>
        <span className="dot"></span>
        {statusLabel(p.status)}
      </div>
    </div>
  );
}
