import { useCallback, useEffect, useRef, useState } from 'preact/hooks';
import {
  GetPhoneDetail,
  StartCalibration,
  GetCalibrationProgress,
  CancelCalibration,
  SetRecording,
  UnpairPhone,
  ForceUnpairPhone,
  type PhoneDetailView,
  type CalibrationProgress,
} from '../api';
import { statusLabel } from '../lib/format';
import { BatteryIcon } from '../components/BatteryIcon';
import { CameraControls } from '../components/CameraControls';
import { PhoneCommandBar } from '../components/PhoneCommandBar';
import { ConfigForm } from '../components/ConfigForm';
import { StatusPanel } from '../components/StatusPanel';
import { useLivePreview } from '../components/LivePreview';

interface PhoneDetailProps {
  onPhoneChanged?: () => void;
  phoneId: string;
  onDeselect: () => void;
  onViewGallery: () => void;
  onUnpaired: () => void;
}

export function PhoneDetail({ phoneId, onDeselect, onViewGallery, onUnpaired, onPhoneChanged }: PhoneDetailProps) {
  const [detail, setDetail] = useState<PhoneDetailView | null>(null);
  const [error, setError] = useState<string | null>(null);

  const live = useLivePreview(phoneId);

  // Calibration re-run state.
  const [runId, setRunId] = useState<string | null>(null);
  const [progress, setProgress] = useState<CalibrationProgress | null>(null);
  const [calibMsg, setCalibMsg] = useState<string | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // Recording toggle.
  const [recordBusy, setRecordBusy] = useState(false);

  // Unpair state.
  const [unpairOpen, setUnpairOpen] = useState(false);
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
          res.progress.status === 'completed' ? 'Calibration complete.' : `Calibration ${res.progress.status}.`,
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
      setRunId('');
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

  const toggleRecording = async () => {
    if (!detail) return;
    const next = detail.status?.mode !== 'record';
    setRecordBusy(true);
    try {
      const r = await SetRecording(phoneId, next);
      if (!r.ok) {
        setCalibMsg(r.message || 'Could not change recording state.');
      }
      await loadDetail();
    } finally {
      setRecordBusy(false);
    }
  };

  const doUnpair = async (confirmed: boolean) => {
    setUnpairBusy(true);
    setUnpairMsg(null);
    try {
      const r = await UnpairPhone(phoneId, confirmed);
      if (r.ok) {
        onUnpaired();
        return;
      }
      if (r.outcome === 'needs_confirmation') {
        setUnpairMode('confirm-unsynced');
        setUnpairMsg(r.message ?? null);
        return;
      }
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
      onUnpaired();
    } finally {
      setUnpairBusy(false);
    }
  };

  if (error) {
    return (
      <div className="phone-detail">
        <button className="back-link" onClick={onDeselect}>
          Fleet overview
        </button>
        <div className="empty-note">Error: {error}</div>
      </div>
    );
  }

  if (!detail) {
    return (
      <div className="phone-detail">
        <div className="calib-sub">Loading…</div>
      </div>
    );
  }

  const p = detail.phone;
  const cal = detail.calibration;
  const running = runId !== null;
  const phoneRecording = detail.status?.mode === 'record';

  return (
    <div className="phone-detail">
      <button className="back-link" onClick={onDeselect}>
        Fleet overview
      </button>

      <div className="detail-header">
        <div className="avatar-circle">{(p.name || '?').slice(0, 1).toUpperCase()}</div>
        <div>
          <h2>{p.name}</h2>
          <div className="sub">
            <span>
              {p.manufacturer} {p.model}
            </span>
            <span
              className={`status-pill ${
                p.status === 'recording' ? 'recording' : p.status === 'unreachable' ? 'unreachable' : ''
              }`}
            >
              <span className="dot"></span>
              {statusLabel(p.status)}
            </span>
            <BatteryIcon percent={p.batteryPercent} charging={p.charging} hasBattery={p.hasBattery} />
          </div>
        </div>
      </div>

      <PhoneCommandBar
        reachable={p.reachable}
        phoneRecording={phoneRecording}
        recordBusy={recordBusy}
        onToggleRecording={toggleRecording}
        live={live}
        calibRunning={running}
        calibLabel={cal.present ? 'Re-run calibration' : 'Run calibration'}
        canCalibrate={!!cal.modelKey}
        onCalibrate={startCalibration}
        onViewGallery={onViewGallery}
        unpairOpen={unpairOpen}
        onToggleUnpair={() => setUnpairOpen((v) => !v)}
      />

      {(progress || calibMsg || running) && (
        <div className="card" style={{ padding: '12px 14px' }}>
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
                    (progress.currentStep ? ` · ${progress.currentStep}` : '')
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
      )}

      <CameraControls phoneId={phoneId} phoneRecording={phoneRecording} ctl={live} />

      <div className="section-title">Configuration</div>
      <ConfigForm
        phoneId={phoneId}
        config={detail.config}
        configError={detail.configError}
        onSaved={(cfg) => {
          // The device name is the phone's name; a rename must reach the rest
          // of the UI (Fleet list, Gallery chips), not just this screen.
          if (cfg.deviceName && cfg.deviceName !== detail.phone.name) onPhoneChanged?.();
          loadDetail();
        }}
      />

      <div className="section-title">Status</div>
      <StatusPanel phone={p} status={detail.status} statusError={detail.statusError} calibration={cal} />

      {unpairOpen && (
        <>
          <div className="section-title">Danger zone</div>
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
                  <button
                    className="btn danger"
                    onClick={() => setUnpairMode('confirm-force')}
                    disabled={unpairBusy}
                  >
                    Force unpair
                  </button>
                </div>
                {unpairMsg && (
                  <div className="calib-sub" style={{ marginTop: '10px' }}>
                    {unpairMsg}
                  </div>
                )}
              </>
            )}

            {unpairMode === 'confirm-unsynced' && (
              <div className="confirm-box">
                <p>{unpairMsg}</p>
                <div className="confirm-actions">
                  <button className="btn danger" onClick={() => doUnpair(true)} disabled={unpairBusy}>
                    Unpair anyway
                  </button>
                  <button
                    className="btn"
                    onClick={() => {
                      setUnpairMode(null);
                      setUnpairMsg(null);
                    }}
                    disabled={unpairBusy}
                  >
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
        </>
      )}
    </div>
  );
}
