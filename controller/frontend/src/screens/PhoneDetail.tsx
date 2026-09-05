import { useCallback, useEffect, useRef, useState } from 'preact/hooks';
import {
  GetPhoneDetail,
  StartCalibration,
  GetCalibrationProgress,
  CancelCalibration,
  type PhoneDetailView,
  type CalibrationProgress,
} from '../api';
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

  // Calibration re-run state.
  const [runId, setRunId] = useState<string | null>(null);
  const [progress, setProgress] = useState<CalibrationProgress | null>(null);
  const [calibMsg, setCalibMsg] = useState<string | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const loadDetail = useCallback(async () => {
    try {
      const d = await GetPhoneDetail(phoneId);
      setDetail(d);
    } catch (err) {
      setError(String(err));
    }
  }, [phoneId]);

  useEffect(() => {
    loadDetail();
  }, [loadDetail]);

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  // Poll the sweep while one is running; refresh the detail (and its
  // calibration summary) once it settles.
  useEffect(() => {
    if (runId === null) return;
    stopPolling();
    pollRef.current = setInterval(async () => {
      const res = await GetCalibrationProgress(phoneId, runId);
      if (!res.ok || !res.progress) {
        setCalibMsg(res.error || 'Lost contact with the sweep.');
        setRunId(null);
        setProgress(null);
        stopPolling();
        return;
      }
      setProgress(res.progress);
      if (res.progress.status !== 'running') {
        setRunId(null);
        stopPolling();
        setCalibMsg(
          res.progress.status === 'completed'
            ? 'Calibration complete.'
            : `Calibration ${res.progress.status}.`,
        );
        loadDetail();
      }
    }, 800);
    return stopPolling;
  }, [runId, phoneId, stopPolling, loadDetail]);

  const startCalibration = async () => {
    setCalibMsg(null);
    setProgress(null);
    const res = await StartCalibration(phoneId);
    if (res.outcome === 'ok') {
      setRunId(res.runId || '');
    } else if (res.outcome === 'running') {
      setRunId(''); // attach to the in-progress sweep (status endpoint takes no runId)
      setCalibMsg(res.message || null);
    } else {
      setCalibMsg(res.message || 'Could not start calibration.');
    }
  };

  const cancelCalibration = async () => {
    const id = progress?.runId || runId || '';
    try {
      await CancelCalibration(phoneId, id);
    } catch (err) {
      setCalibMsg(String(err));
    }
  };

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
  const cal = detail.calibration;
  const running = runId !== null;

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
        Live preview / adjusters are out of scope for this vertical slice. Showing raw{' '}
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

      <div className="section-title">Calibration</div>
      <div className="card">
        <div className="calib-row">
          <div>
            {cal.present ? (
              <>
                <div className="calib-state">
                  {cal.checksTotal > 0
                    ? `${cal.checksPassed}/${cal.checksTotal} checks completed`
                    : 'Calibrated'}
                </div>
                <div className="calib-sub">
                  {cal.calibratedAtMs ? new Date(cal.calibratedAtMs).toLocaleString() : ''}
                  {cal.viaOtherPhone && ` · via ${cal.sourcePhoneName || cal.sourcePhoneId}`}
                </div>
              </>
            ) : (
              <>
                <div className="calib-state needed">Calibration needed</div>
                <div className="calib-sub">
                  {cal.modelKey
                    ? 'No cached data for this model — this phone would become its reference.'
                    : 'This phone reported no manufacturer/model to key calibration by.'}
                </div>
              </>
            )}
          </div>
          <button className="btn small" onClick={startCalibration} disabled={running || !cal.modelKey}>
            {running ? 'Running…' : cal.present ? 'Re-run' : 'Run calibration'}
          </button>
        </div>

        {progress && (
          <>
            <div className="calib-bar">
              <i
                style={{
                  width: `${
                    progress.camerasTotal > 0
                      ? Math.round((progress.camerasCompleted / progress.camerasTotal) * 100)
                      : 0
                  }%`,
                }}
              />
            </div>
            <div className="calib-progress-line">
              {progress.status === 'running'
                ? `camera ${progress.camerasCompleted}/${progress.camerasTotal}` +
                  (progress.currentStep ? ` · ${progress.currentStep}` : '') +
                  (progress.progressWithinStep && progress.progressWithinStep.total > 0
                    ? ` · check ${progress.progressWithinStep.index}/${progress.progressWithinStep.total}`
                    : '')
                : progress.status}
            </div>
          </>
        )}
        {calibMsg && <div className="calib-sub">{calibMsg}</div>}
        {running && (
          <button className="btn small danger" style={{ marginTop: '8px' }} onClick={cancelCalibration}>
            Cancel
          </button>
        )}
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
