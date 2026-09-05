import { useEffect, useState } from 'preact/hooks';
import { GetPhoneDetail, type PhoneDetailView } from '../api';
import { fmtBytes, statusLabel } from '../lib/format';
import { BackIcon, GalleryIcon } from '../lib/icons';

interface PhoneDetailProps {
  phoneId: string;
  onBack: () => void;
  onViewGallery: () => void;
}

export function PhoneDetail({ phoneId, onBack, onViewGallery }: PhoneDetailProps) {
  const [detail, setDetail] = useState<PhoneDetailView | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const d = await GetPhoneDetail(phoneId);
        if (!cancelled) setDetail(d);
      } catch (err) {
        if (!cancelled) setError(String(err));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [phoneId]);

  if (error) {
    return (
      <div className="body-scroll">
        <div className="empty-note">Error: {error}</div>
      </div>
    );
  }

  if (!detail) {
    return <div className="body-scroll">Loading…</div>;
  }

  const p = detail.phone;

  return (
    <div className="body-scroll">
      <button className="back-link" onClick={onBack}>
        <BackIcon /> Back to Fleet
      </button>
      <div className="detail-header">
        <div className="avatar-circle">{(p.name || '?').slice(0, 1).toUpperCase()}</div>
        <div>
          <h2>{p.name}</h2>
          <div className="sub">
            <span>
              {p.manufacturer} {p.model}
            </span>
            {p.hasBattery && (
              <span>
                {p.batteryPercent}% battery{p.charging ? ' (charging)' : ''}
              </span>
            )}
            <span className={`status-pill ${p.status === 'recording' ? 'recording' : p.status === 'unreachable' ? 'unreachable' : ''}`}>
              <span className="dot"></span>
              {statusLabel(p.status)}
            </span>
          </div>
        </div>
      </div>

      <div className="deferred-note">
        Live preview / adjusters / calibration UI are out of scope for this vertical slice. Showing raw{' '}
        <span className="mono">GET /api/status</span> + <span className="mono">GET /api/config</span> below instead.
      </div>

      <div className="section-title">Sync</div>
      <div className="card">
        <div className="field-row">
          <span className="k">Address</span>
          <span className="v">{p.baseUrl}</span>
        </div>
        <div className="field-row">
          <span className="k">Last seen</span>
          <span className="v">{p.lastSeenMs ? new Date(p.lastSeenMs).toLocaleString() : '—'}</span>
        </div>
        <div className="field-row">
          <span className="k">Sync cursor</span>
          <span className="v">{p.syncCursorMs ? new Date(p.syncCursorMs).toLocaleString() : '—'}</span>
        </div>
        <div className="field-row">
          <span className="k">Archived on disk</span>
          <span className="v">{fmtBytes(p.diskUsageBytes)}</span>
        </div>
      </div>

      <div className="section-title">GET /api/status</div>
      <div className="card">
        {detail.statusError ? (
          <div className="field-row">
            <span className="k">Error</span>
            <span className="v">{detail.statusError}</span>
          </div>
        ) : (
          <div className="raw-json">{JSON.stringify(detail.status, null, 2)}</div>
        )}
      </div>

      <div className="section-title">GET /api/config</div>
      <div className="card">
        {detail.configError ? (
          <div className="field-row">
            <span className="k">Error</span>
            <span className="v">{detail.configError}</span>
          </div>
        ) : (
          <div className="raw-json">{JSON.stringify(detail.config, null, 2)}</div>
        )}
      </div>

      <div className="section-title">This phone's clips</div>
      <div className="card" style={{ padding: '14px' }}>
        <button className="btn" onClick={onViewGallery}>
          <GalleryIcon /> View in Gallery
        </button>
      </div>
    </div>
  );
}
