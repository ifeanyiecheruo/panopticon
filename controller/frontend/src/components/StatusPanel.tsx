import type { ComponentChildren } from 'preact';
import { fmtBytes, statusLabel } from '../lib/format';
import type { PhoneView, PhoneApiStatus, CalibrationView } from '../api';

interface Props {
  phone: PhoneView;
  status: PhoneApiStatus | undefined;
  statusError?: string;
  calibration: CalibrationView;
}

/** The thermal guard's state in one line, or null for phone builds that predate it. */
function thermalText(status: PhoneApiStatus | undefined): { text: string; warn: boolean } | null {
  const t = status?.thermalShutdown;
  if (!t) return null;
  if (t.source === 'none') return { text: 'Unavailable — phone reports no thermal state', warn: false };
  // Android's own thermal status where the phone has it; battery °C only on phones without.
  const byStatus = t.source === 'thermalStatus';
  const reading = byStatus
    ? `thermal status ${t.level || '—'}`
    : typeof t.batteryTempC === 'number'
      ? `battery ${t.batteryTempC.toFixed(1)}°C`
      : 'battery temperature n/a';
  if (t.active) {
    const since = t.sinceMs ? ` since ${new Date(t.sinceMs).toLocaleTimeString()}` : '';
    const resume = byStatus ? `resumes at ${t.safeLevel}` : `resumes under ${t.safeTempC.toFixed(0)}°C`;
    return { text: `Camera shut down${since} — ${reading}; ${resume}`, warn: true };
  }
  const trip = byStatus ? t.criticalLevel : `${t.criticalTempC.toFixed(0)}°C`;
  return { text: `OK — ${reading}; shuts down at ${trip}`, warn: false };
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

  const thermal = thermalText(status);

  return (
    <div className="card">
      {statusError ? (
        <Row k="Connection" v={`Unreachable — ${statusError}`} warn />
      ) : (
        <>
          <Row
            k="Recording"
            v={
              status?.status === 'recording'
                ? 'Recording'
                : status?.status === 'overheated'
                  ? `Paused — overheated (${status.mode} resumes on cooling)`
                  : `Idle (${status?.mode ?? '—'})`
            }
            warn={status?.status === 'recording' || status?.status === 'overheated'}
          />
          {thermal && <Row k="Thermal guard" v={thermal.text} warn={thermal.warn} />}
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
