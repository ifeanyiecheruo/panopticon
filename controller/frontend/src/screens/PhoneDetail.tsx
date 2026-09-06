import { useCallback, useEffect, useRef, useState } from 'preact/hooks';
import {
  GetPhoneDetail,
  StartCalibration,
  GetCalibrationProgress,
  CancelCalibration,
  UnpairPhone,
  ForceUnpairPhone,
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

  // Unpair state.
  const [unpairMode, setUnpairMode] = useState<null | 'confirm-unsynced' | 'confirm-force'>(null);
  const [unpairMsg, setUnpairMsg] = useState<string | null>(null);
  const [unpairBusy, setUnpairBusy] = useState(false);

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

  const doUnpair = async (confirmed: boolean) => {
    setUnpairBusy(true);
    setUnpairMsg(null);
    try {
      const r = await UnpairPhone(phoneId, confirmed);
      if (r.ok) {
        onBack();
        return;
      }
      if (r.outcome === 'needs_confirmation') {
        setUnpairMode('confirm-unsynced');
        setUnpairMsg(r.message ?? null);
        return;
      }
      // unreachable / revoke_failed / other — stays paired; point at Force.
      setUnpairMode(null);
      setUnpairMsg(r.message || 'Could not unpair.');
    } finally {
      setUnpairBusy(false);
    }
  };

  const doForceUnpair = async () => {
    setUnpairBusy(true);
    try {
      await ForceUnpairPhone(phoneId);
      onBack();
    } finally {
      setUnpairBusy(false);
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
  // Calibration (and, later, live preview) need exclusive camera access, which
  // the phone only gives up when recording is explicitly stopped on the phone.
  const phoneRecording = detail.status?.mode === 'record';

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
          <button
            className="btn small"
            onClick={startCalibration}
            disabled={running || !cal.modelKey || phoneRecording}
            title={phoneRecording ? 'Stop recording on the phone first' : undefined}
          >
            {running ? 'Running…' : cal.present ? 'Re-run' : 'Run calibration'}
          </button>
        </div>

        {phoneRecording && (
          <div className="calib-sub" style={{ marginTop: '8px' }}>
            Recording — calibration and live preview are unavailable until recording is stopped on
            the phone's own screen.
          </div>
        )}

        {cal.present && cal.cameras && cal.cameras.length > 0 && (
          <div className="calib-cameras">
            {cal.cameras.map((c) => (
              <div className="calib-cam" key={c.cameraId}>
                <div className="calib-cam-head">
                  Camera {c.cameraId} · {c.facing}
                </div>
                <div className="calib-cam-grid">
                  <span>optical</span>
                  <span>
                    {c.opticalRange.lo.toFixed(2)}×–{c.opticalRange.hi.toFixed(2)}×
                  </span>
                  <span>digital</span>
                  <span>
                    {c.digitalRange.lo.toFixed(2)}×–{c.digitalRange.hi.toFixed(2)}×
                  </span>
                  <span>crossover</span>
                  <span>{c.crossoverRatio != null ? `${c.crossoverRatio.toFixed(2)}×` : '—'}</span>
                  <span>zoom-rect position</span>
                  <span className={c.positionHonored ? '' : 'calib-bad'}>
                    {c.positionHonored
                      ? 'honoured'
                      : c.positionMetadataLied
                        ? 'NOT honoured (metadata lied)'
                        : 'NOT honoured'}
                  </span>
                  <span>quality collapse</span>
                  <span className={c.qualityCollapseRatio != null ? 'calib-bad' : ''}>
                    {c.qualityCollapseRatio != null ? `from ${c.qualityCollapseRatio.toFixed(2)}×` : 'not seen'}
                  </span>
                  <span>resolutions probed</span>
                  <span>{c.resolutions}</span>
                </div>
              </div>
            ))}
          </div>
        )}

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

      <div className="section-title">Unpair</div>
      <div className="card" style={{ padding: '14px' }}>
        {unpairMode === null && (
          <>
            <div className="calib-sub" style={{ marginBottom: '10px' }}>
              Unpairing revokes this controller's token on the phone. Clips already synced stay in
              the Gallery as historical footage.
            </div>
            <div className="confirm-actions">
              <button className="btn" onClick={() => doUnpair(false)} disabled={unpairBusy}>
                Unpair
              </button>
              <button className="btn danger" onClick={() => setUnpairMode('confirm-force')} disabled={unpairBusy}>
                Force unpair
              </button>
            </div>
            {unpairMsg && <div className="calib-sub" style={{ marginTop: '10px' }}>{unpairMsg}</div>}
          </>
        )}

        {unpairMode === 'confirm-unsynced' && (
          <div className="confirm-box">
            <p>{unpairMsg}</p>
            <div className="confirm-actions">
              <button className="btn danger" onClick={() => doUnpair(true)} disabled={unpairBusy}>
                Unpair anyway
              </button>
              <button className="btn" onClick={() => { setUnpairMode(null); setUnpairMsg(null); }} disabled={unpairBusy}>
                Keep paired
              </button>
            </div>
          </div>
        )}

        {unpairMode === 'confirm-force' && (
          <div className="confirm-box">
            <p>
              Force unpair removes <b>{p.name}</b> locally even if it can't be reached. If the phone
              is offline it may keep a live token until you clear this controller from the phone's
              own screen. The unsynced-clips check is skipped.
            </p>
            <div className="confirm-actions">
              <button className="btn danger" onClick={doForceUnpair} disabled={unpairBusy}>
                Force unpair
              </button>
              <button className="btn" onClick={() => setUnpairMode(null)} disabled={unpairBusy}>
                Cancel
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
