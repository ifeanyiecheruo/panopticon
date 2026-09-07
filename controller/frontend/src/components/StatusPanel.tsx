import type { ComponentChildren } from 'preact';
import { fmtBytes, statusLabel } from '../lib/format';
import type { PhoneView, PhoneApiStatus, CalibrationView } from '../api';

interface Props {
  phone: PhoneView;
  status: PhoneApiStatus | undefined;
  statusError?: string;
  calibration: CalibrationView;
}

function Row({ k, v, warn }: { k: string; v: ComponentChildren; warn?: boolean }) {
  return (
    <div className="field-row">
      <span className="k">{k}</span>
      <span className="v" style={warn ? { color: 'var(--warn)' } : undefined}>
        {v}
      </span>
    </div>
  );
}

/** Read-only device status — the friendly version of what used to be raw
 * GET /api/status JSON, plus a one-line calibration-data indicator. */
export function StatusPanel({ phone, status, statusError, calibration: cal }: Props) {
  const calText = cal.present
    ? `Present${cal.calibratedAtMs ? ` — ${new Date(cal.calibratedAtMs).toLocaleDateString()}` : ''}${
        cal.viaOtherPhone ? ` · via ${cal.sourcePhoneName || cal.sourcePhoneId}` : ''
      }`
    : cal.modelKey
      ? 'Not present — this phone would become the reference for its model'
      : 'Not available — phone reported no manufacturer/model';

  return (
    <div className="card">
      {statusError ? (
        <Row k="Connection" v={`Unreachable — ${statusError}`} warn />
      ) : (
        <>
          <Row
            k="Recording"
            v={status?.status === 'recording' ? 'Recording' : `Idle (${status?.mode ?? '—'})`}
            warn={status?.status === 'recording'}
          />
          <Row k="Camera" v={status?.cameraHealthy ? 'Healthy' : 'Unhealthy'} warn={status ? !status.cameraHealthy : false} />
          <Row
            k="Storage"
            v={
              status
                ? `${fmtBytes(status.storageUsedBytes)} / ${fmtBytes(status.storageCapBytes)}`
                : '—'
            }
          />
          <Row k="Live viewers" v={status ? String(status.liveViewers) : '—'} />
        </>
      )}
      <Row k="Address" v={phone.baseUrl} />
      <Row k="Last seen" v={phone.lastSeenMs ? new Date(phone.lastSeenMs).toLocaleString() : '—'} />
      <Row k="Calibration data" v={calText} warn={!cal.present} />
      <Row k="Status" v={statusLabel(phone.status)} />
    </div>
  );
}
