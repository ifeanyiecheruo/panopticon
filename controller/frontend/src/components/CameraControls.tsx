import { useCallback, useEffect, useRef, useState } from 'preact/hooks';
import {
  ListCameras,
  SetActiveCamera,
  GetCameraControls,
  SetCameraControls,
  ComputeEffectiveRect,
  type CameraInfo,
  type CameraControlsView,
  type CameraCapabilities,
  type EffectiveRectResult,
} from '../api';
import { LivePreview } from './LivePreview';

/** Plain (method-free) shapes for the editable draft + patch payload — the
 * generated Wails model classes carry a `convertValues` method that makes an
 * object literal structurally incompatible. The bound calls accept plain JSON. */
type RectNorm = { l: number; t: number; r: number; b: number };
type ControlKeys = {
  zoomRatio?: number;
  cropRegionNorm?: RectNorm;
  aeExposureCompensation?: number;
  aeLock?: boolean;
  manualExposure?: boolean;
  sensorExposureTimeNs?: number;
  sensorSensitivityIso?: number;
  manualFocus?: boolean;
  lensFocusDistanceDiopters?: number;
  awbMode?: number;
  manualWhiteBalance?: boolean;
  wbRedGain?: number;
  wbGreenGain?: number;
  wbBlueGain?: number;
  videoStabilizationMode?: number;
  opticalStabilizationMode?: number;
};

// CONTROL_AWB_MODE values (android.hardware.camera2.CameraMetadata).
const AWB_LABELS: Record<number, string> = {
  0: 'Off (manual)',
  1: 'Auto',
  2: 'Incandescent',
  3: 'Fluorescent',
  4: 'Warm fluorescent',
  5: 'Daylight',
  6: 'Cloudy daylight',
  7: 'Twilight',
  8: 'Shade',
};

interface Props {
  phoneId: string;
  /** Phone is in record mode. A camera switch mid-record briefly interrupts
   * recording (new clip boundary) — allowed, but confirm-gated. */
  phoneRecording: boolean;
}

// The live pipeline records/broadcasts at 1280x720; the zoom-rect prediction is
// resolution-scoped, so ask the calibration model about that size.
const LIVE_W = 1280;
const LIVE_H = 720;

const APPLY_DEBOUNCE_MS = 250;

export function CameraControls({ phoneId, phoneRecording }: Props) {
  const [cams, setCams] = useState<CameraInfo[]>([]);
  const [view, setView] = useState<CameraControlsView | null>(null);
  const [loadErr, setLoadErr] = useState<string | null>(null);

  // Local editable copy of the phone's manual-control state.
  const [manualOn, setManualOn] = useState(false);
  const [keys, setKeys] = useState<ControlKeys>({});
  const [msg, setMsg] = useState<string | null>(null);
  const [invalidKey, setInvalidKey] = useState<string | null>(null);

  const [switchBusy, setSwitchBusy] = useState(false);
  const [confirmCam, setConfirmCam] = useState<string | null>(null);

  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const load = useCallback(async () => {
    setLoadErr(null);
    const [cs, cv] = await Promise.all([ListCameras(phoneId), GetCameraControls(phoneId)]);
    if (cs.ok) setCams(cs.cameras || []);
    if (cv.ok) {
      setView(cv);
      setManualOn(cv.state?.manualControlEnabled ?? false);
      setKeys({ ...(cv.state?.keys ?? {}) });
    } else {
      setLoadErr(cv.error || cs.error || 'Could not read camera state from the phone.');
    }
  }, [phoneId]);

  useEffect(() => {
    load();
  }, [load]);

  const caps: CameraCapabilities | undefined = view?.capabilities;
  const activeCamId = view?.state?.cameraId || cams.find((c) => c.isActive)?.cameraId || '';

  const pushControls = useCallback(
    (nextManual: boolean, nextKeys: ControlKeys) => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
      debounceRef.current = setTimeout(async () => {
        const patch = { manualControlEnabled: nextManual, keys: nextKeys };
        const r = await SetCameraControls(phoneId, patch as Parameters<typeof SetCameraControls>[1]);
        if (r.outcome === 'invalid_key') {
          setInvalidKey(r.invalidKey || null);
          setMsg(r.message || 'The phone rejected that value.');
        } else if (!r.ok) {
          setInvalidKey(null);
          setMsg(r.message || 'Could not apply camera controls.');
        } else {
          setInvalidKey(null);
          setMsg(null);
        }
      }, APPLY_DEBOUNCE_MS);
    },
    [phoneId],
  );

  const setKey = <K extends keyof ControlKeys>(k: K, v: ControlKeys[K]) => {
    const next = { ...keys, [k]: v };
    setKeys(next);
    if (manualOn) pushControls(true, next);
  };

  const toggleManual = (on: boolean) => {
    setManualOn(on);
    pushControls(on, keys);
  };

  const doSwitch = async (cameraId: string) => {
    setSwitchBusy(true);
    setConfirmCam(null);
    try {
      const r = await SetActiveCamera(phoneId, cameraId);
      if (!r.ok) {
        setMsg(r.message || 'Could not switch camera.');
        return;
      }
      setMsg(null);
      await load(); // capabilities + state are per-camera
    } finally {
      setSwitchBusy(false);
    }
  };

  const onPickCamera = (cameraId: string) => {
    if (cameraId === activeCamId || switchBusy) return;
    if (phoneRecording) setConfirmCam(cameraId);
    else doSwitch(cameraId);
  };

  // ---- zoom-rect picker overlay ----
  const [pickRect, setPickRect] = useState<RectNorm | null>(null);
  const [predicted, setPredicted] = useState<EffectiveRectResult | null>(null);
  const dragRef = useRef<{ x: number; y: number } | null>(null);

  const clamp01 = (n: number) => Math.min(1, Math.max(0, n));
  const evtNorm = (e: PointerEvent, el: HTMLElement) => {
    const b = el.getBoundingClientRect();
    return { x: clamp01((e.clientX - b.left) / b.width), y: clamp01((e.clientY - b.top) / b.height) };
  };

  const predictFor = useCallback(
    async (rect: RectNorm) => {
      const w = rect.r - rect.l;
      const h = rect.b - rect.t;
      const zoom = 1 / Math.max(0.05, Math.max(w, h));
      const cx = (rect.l + rect.r) / 2;
      const cy = (rect.t + rect.b) / 2;
      try {
        const res = await ComputeEffectiveRect(phoneId, activeCamId, LIVE_W, LIVE_H, zoom, cx, cy);
        setPredicted(res);
      } catch {
        setPredicted(null); // model has no data for this resolution / camera
      }
    },
    [phoneId, activeCamId],
  );

  const applyPickRect = () => {
    if (!pickRect) return;
    // cropRegionNorm and zoomRatio are mutually exclusive on the phone; the rect wins.
    const next = { ...keys, cropRegionNorm: pickRect, zoomRatio: undefined };
    setKeys(next);
    if (!manualOn) {
      setManualOn(true);
      pushControls(true, next);
    } else {
      pushControls(true, next);
    }
  };

  const renderPicker = (playing: boolean) => {
    if (!playing || !manualOn) return null;
    const asPct = (r: RectNorm) => ({
      left: `${r.l * 100}%`,
      top: `${r.t * 100}%`,
      width: `${(r.r - r.l) * 100}%`,
      height: `${(r.b - r.t) * 100}%`,
    });
    return (
      <div
        className="zoom-picker"
        onPointerDown={(e) => {
          (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
          const p = evtNorm(e, e.currentTarget as HTMLElement);
          dragRef.current = p;
          setPredicted(null);
          setPickRect({ l: p.x, t: p.y, r: p.x, b: p.y });
        }}
        onPointerMove={(e) => {
          if (!dragRef.current) return;
          const p = evtNorm(e, e.currentTarget as HTMLElement);
          const s = dragRef.current;
          setPickRect({
            l: Math.min(s.x, p.x),
            t: Math.min(s.y, p.y),
            r: Math.max(s.x, p.x),
            b: Math.max(s.y, p.y),
          });
        }}
        onPointerUp={() => {
          dragRef.current = null;
          if (pickRect && pickRect.r - pickRect.l > 0.03 && pickRect.b - pickRect.t > 0.03) {
            predictFor(pickRect);
          } else {
            setPickRect(null);
          }
        }}
      >
        {pickRect && <div className="zoom-picker-box" style={asPct(pickRect)} />}
        {predicted && (
          <div
            className={`zoom-picker-pred ${predicted.positionHonored ? '' : 'recentred'}`}
            style={asPct(predicted.effectiveRectNorm)}
          />
        )}
        {pickRect && (
          <div className="zoom-picker-bar" onPointerDown={(e) => e.stopPropagation()}>
            {predicted?.note && <span className="zoom-picker-note">{predicted.note}</span>}
            <button className="btn small" onClick={applyPickRect}>
              Apply zoom rect
            </button>
            <button
              className="btn small"
              onClick={() => {
                setPickRect(null);
                setPredicted(null);
              }}
            >
              Clear
            </button>
          </div>
        )}
      </div>
    );
  };

  // ---- render ----
  return (
    <>
      <div className="section-title">Live preview</div>
      <LivePreview phoneId={phoneId} phoneRecording={phoneRecording} overlay={renderPicker} />

      <div className="section-title">Camera</div>
      <div className="card" style={{ padding: '14px' }}>
        {loadErr ? (
          <div className="calib-sub">{loadErr}</div>
        ) : (
          <>
            <div className="cam-chips">
              {cams.map((c) => (
                <button
                  key={c.cameraId}
                  className={`cam-chip ${c.cameraId === activeCamId ? 'active' : ''}`}
                  disabled={switchBusy}
                  onClick={() => onPickCamera(c.cameraId)}
                  title={c.focalLengthMm ? `${c.focalLengthMm.toFixed(1)} mm` : undefined}
                >
                  {c.label}
                  <span className="cam-chip-sub">
                    {c.facing} · {c.cameraId}
                  </span>
                </button>
              ))}
            </div>

            {confirmCam && (
              <div className="confirm-box">
                <p>
                  The phone is recording. Switching camera reconfigures the pipeline — it briefly
                  interrupts recording and starts a new clip. Continue?
                </p>
                <div className="confirm-actions">
                  <button className="btn danger" onClick={() => doSwitch(confirmCam)}>
                    Switch camera
                  </button>
                  <button className="btn" onClick={() => setConfirmCam(null)}>
                    Cancel
                  </button>
                </div>
              </div>
            )}

            <label className="ctl-toggle">
              <input
                type="checkbox"
                checked={manualOn}
                onChange={(e) => toggleManual((e.target as HTMLInputElement).checked)}
              />
              Manual controls {manualOn ? 'on' : 'off (full auto)'}
            </label>

            {caps && (
              <fieldset className="ctl-group" disabled={!manualOn}>
                <Slider
                  label="Zoom"
                  min={caps.zoomRatioRange.lo}
                  max={caps.zoomRatioRange.hi}
                  step={0.1}
                  value={keys.zoomRatio ?? caps.zoomRatioRange.lo}
                  fmt={(v) => `${v.toFixed(1)}×`}
                  bad={invalidKey === 'zoomRatio'}
                  onInput={(v) => setKey('zoomRatio', v)}
                />
                <Slider
                  label="Exposure comp."
                  min={caps.aeCompensationRange.lo}
                  max={caps.aeCompensationRange.hi}
                  step={1}
                  value={keys.aeExposureCompensation ?? 0}
                  fmt={(v) => `${v > 0 ? '+' : ''}${v} (${((v * caps.aeCompensationStepMilliEv) / 1000).toFixed(2)} EV)`}
                  bad={invalidKey === 'aeExposureCompensation'}
                  onInput={(v) => setKey('aeExposureCompensation', Math.round(v))}
                />
                <label className="ctl-toggle sub">
                  <input
                    type="checkbox"
                    checked={keys.aeLock ?? false}
                    onChange={(e) => setKey('aeLock', (e.target as HTMLInputElement).checked)}
                  />
                  AE lock (metering freeze)
                </label>

                {caps.awbModes && caps.awbModes.length > 1 && !keys.manualWhiteBalance && (
                  <div className="ctl-row">
                    <label>White balance</label>
                    <select
                      value={keys.awbMode ?? (caps.awbModes.includes(1) ? 1 : caps.awbModes[0])}
                      onChange={(e) =>
                        setKey('awbMode', parseInt((e.target as HTMLSelectElement).value, 10))
                      }
                    >
                      {caps.awbModes.map((m) => (
                        <option key={m} value={m}>
                          {AWB_LABELS[m] ?? `Mode ${m}`}
                        </option>
                      ))}
                    </select>
                    <span className="ctl-val" />
                  </div>
                )}

                {caps.hasManualWhiteBalance && (
                  <div className="ctl-disclosure">
                    <label className="ctl-toggle sub">
                      <input
                        type="checkbox"
                        checked={keys.manualWhiteBalance ?? false}
                        onChange={(e) =>
                          setKey('manualWhiteBalance', (e.target as HTMLInputElement).checked)
                        }
                      />
                      Manual white balance (RGGB gains)
                    </label>
                    {keys.manualWhiteBalance &&
                      (['wbRedGain', 'wbGreenGain', 'wbBlueGain'] as const).map((k, i) => (
                        <Slider
                          key={k}
                          label={['Red gain', 'Green gain', 'Blue gain'][i]}
                          min={caps.wbGainRange.lo}
                          max={caps.wbGainRange.hi}
                          step={0.05}
                          value={keys[k] ?? 1.0}
                          fmt={(v) => v.toFixed(2)}
                          bad={invalidKey === k}
                          onInput={(v) => setKey(k, v)}
                        />
                      ))}
                  </div>
                )}

                {caps.videoStabilizationModes && caps.videoStabilizationModes.includes(1) && (
                  <label className="ctl-toggle sub">
                    <input
                      type="checkbox"
                      checked={keys.videoStabilizationMode === 1}
                      onChange={(e) =>
                        setKey('videoStabilizationMode', (e.target as HTMLInputElement).checked ? 1 : 0)
                      }
                    />
                    Video stabilization (digital)
                  </label>
                )}

                {caps.opticalStabilizationModes && caps.opticalStabilizationModes.includes(1) && (
                  <label className="ctl-toggle sub">
                    <input
                      type="checkbox"
                      checked={keys.opticalStabilizationMode === 1}
                      onChange={(e) =>
                        setKey('opticalStabilizationMode', (e.target as HTMLInputElement).checked ? 1 : 0)
                      }
                    />
                    Optical stabilization (OIS)
                  </label>
                )}

                {caps.hasManualSensor && caps.exposureTimeRangeNs && caps.sensitivityRange && (
                  <div className="ctl-disclosure">
                    <label className="ctl-toggle sub">
                      <input
                        type="checkbox"
                        checked={keys.manualExposure ?? false}
                        onChange={(e) =>
                          setKey('manualExposure', (e.target as HTMLInputElement).checked)
                        }
                      />
                      Manual exposure
                    </label>
                    {keys.manualExposure && (
                      <>
                        <Slider
                          label="Shutter"
                          min={Math.log10(caps.exposureTimeRangeNs.lo)}
                          max={Math.log10(caps.exposureTimeRangeNs.hi)}
                          step={0.02}
                          value={Math.log10(
                            keys.sensorExposureTimeNs ?? caps.exposureTimeRangeNs.lo,
                          )}
                          fmt={(v) => `1/${Math.round(1e9 / 10 ** v).toLocaleString()} s`}
                          bad={invalidKey === 'sensorExposureTimeNs'}
                          onInput={(v) => setKey('sensorExposureTimeNs', Math.round(10 ** v))}
                        />
                        <Slider
                          label="ISO"
                          min={caps.sensitivityRange.lo}
                          max={caps.sensitivityRange.hi}
                          step={10}
                          value={keys.sensorSensitivityIso ?? caps.sensitivityRange.lo}
                          fmt={(v) => `${Math.round(v)}`}
                          bad={invalidKey === 'sensorSensitivityIso'}
                          onInput={(v) => setKey('sensorSensitivityIso', Math.round(v))}
                        />
                      </>
                    )}
                  </div>
                )}

                {caps.hasManualFocus && caps.minFocusDistanceDiopters > 0 && (
                  <div className="ctl-disclosure">
                    <label className="ctl-toggle sub">
                      <input
                        type="checkbox"
                        checked={keys.manualFocus ?? false}
                        onChange={(e) =>
                          setKey('manualFocus', (e.target as HTMLInputElement).checked)
                        }
                      />
                      Manual focus
                    </label>
                    {keys.manualFocus && (
                      <Slider
                        label="Focus"
                        min={0}
                        max={caps.minFocusDistanceDiopters}
                        step={caps.minFocusDistanceDiopters / 100}
                        value={keys.lensFocusDistanceDiopters ?? 0}
                        fmt={(v) => (v <= 0.001 ? '∞' : `${(1 / v).toFixed(2)} m`)}
                        bad={invalidKey === 'lensFocusDistanceDiopters'}
                        onInput={(v) => setKey('lensFocusDistanceDiopters', v)}
                      />
                    )}
                  </div>
                )}
              </fieldset>
            )}

            {msg && <div className="calib-sub" style={{ marginTop: '8px' }}>{msg}</div>}
            <div className="calib-sub" style={{ marginTop: '6px' }}>
              Draw a box on the live preview to set an off-centre zoom rect; the dashed overlay is
              what this phone model's calibration says the HAL will actually crop to.
            </div>
          </>
        )}
      </div>
    </>
  );
}

interface SliderProps {
  label: string;
  min: number;
  max: number;
  step: number;
  value: number;
  fmt: (v: number) => string;
  bad?: boolean;
  onInput: (v: number) => void;
}

function Slider({ label, min, max, step, value, fmt, bad, onInput }: SliderProps) {
  return (
    <div className={`ctl-row ${bad ? 'bad' : ''}`}>
      <label>{label}</label>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onInput={(e) => onInput(parseFloat((e.target as HTMLInputElement).value))}
      />
      <span className="ctl-val">{fmt(value)}</span>
    </div>
  );
}
