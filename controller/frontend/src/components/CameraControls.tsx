import type { ComponentChildren } from 'preact';
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
import { LivePreviewVideo, type LiveController } from './LivePreview';
import { HScroll } from './phonecam/HScroll';
import { Ruler, type RulerSpec } from './phonecam/Ruler';
import { modeIcon, CycleIcon, ResetIcon, type ModeIconName, type RulerIconName } from './phonecam/icons';

/** Plain (method-free) shapes for the editable draft + patch payload — the
 * generated Wails model classes carry a `convertValues` method that makes an
 * object literal structurally incompatible. The bound calls accept plain JSON. */
type RectNorm = { l: number; t: number; r: number; b: number };
type ControlKeys = {
  zoomRatio?: number;
  cropRegionNorm?: RectNorm;
  aeExposureCompensation?: number;
  aeLock?: boolean;
  aeRegionNorm?: RectNorm;
  manualExposure?: boolean;
  sensorExposureTimeNs?: number;
  sensorSensitivityIso?: number;
  manualFocus?: boolean;
  lensFocusDistanceDiopters?: number;
  afRegionNorm?: RectNorm;
  awbMode?: number;
  manualWhiteBalance?: boolean;
  wbRedGain?: number;
  wbGreenGain?: number;
  wbBlueGain?: number;
  videoStabilizationMode?: number;
  opticalStabilizationMode?: number;
};

const AWB_LABELS: Record<number, string> = {
  0: 'off', 1: 'auto', 2: 'incand.', 3: 'fluor.', 4: 'warm fl.', 5: 'daylight', 6: 'cloudy', 7: 'twilight', 8: 'shade',
};

interface Props {
  phoneId: string;
  phoneRecording: boolean;
  ctl: LiveController;
}

const LIVE_W = 1280;
const LIVE_H = 720;
const APPLY_DEBOUNCE_MS = 200;
/** Grace period after the pointer leaves the preview before the controls start
 * their (CSS, 2s) fade. */
const IDLE_FADE_MS = 800;
const ROTATIONS = [0, 90, 180, 270];

/** Build a ruler spec: ~520px of tick travel across the whole range, with
 * physical-ruler tick tiers derived from the span. */
function spec(min: number, max: number, ends: [RulerIconName, RulerIconName]): RulerSpec {
  const span = Math.abs(max - min) || 1;
  return {
    min,
    max,
    pxPerUnit: 520 / span,
    microStep: span / 40,
    tickStep: span / 8,
    majorStep: span / 4,
    endIcons: ends,
  };
}

type Mode = { id: string; label: string; icon: ModeIconName };

export function CameraControls({ phoneId, phoneRecording, ctl }: Props) {
  const [cams, setCams] = useState<CameraInfo[]>([]);
  const [view, setView] = useState<CameraControlsView | null>(null);
  const [loadErr, setLoadErr] = useState<string | null>(null);

  const [manualOn, setManualOn] = useState(false);
  const [keys, setKeys] = useState<ControlKeys>({});
  const [rotation, setRotation] = useState(0);
  const [msg, setMsg] = useState<string | null>(null);
  const [invalidKey, setInvalidKey] = useState<string | null>(null);

  const [openMode, setOpenMode] = useState('zoom');
  const [switchBusy, setSwitchBusy] = useState(false);
  const [confirmCam, setConfirmCam] = useState<string | null>(null);
  const [idle, setIdle] = useState(false);

  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const idleRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const load = useCallback(async () => {
    setLoadErr(null);
    const [cs, cv] = await Promise.all([ListCameras(phoneId), GetCameraControls(phoneId)]);
    if (cs.ok) setCams(cs.cameras || []);
    if (cv.ok) {
      setView(cv);
      setManualOn(cv.state?.manualControlEnabled ?? false);
      setKeys({ ...(cv.state?.keys ?? {}) });
      setRotation(cv.state?.rotationDegrees ?? 0);
    } else {
      setLoadErr(cv.error || cs.error || 'Could not read camera state from the phone.');
    }
  }, [phoneId]);

  useEffect(() => {
    load();
  }, [load]);

  const caps: CameraCapabilities | undefined = view?.capabilities;
  const activeCamId = view?.state?.cameraId || cams.find((c) => c.isActive)?.cameraId || '';
  const playing = ctl.playing;

  // ---- idle fade for the on-video controls ----
  // Controls stay visible the whole time the pointer is over the preview; they
  // begin their (slow, 2s) fade only a short beat after the pointer leaves.
  const wake = useCallback(() => {
    if (idleRef.current) clearTimeout(idleRef.current);
    setIdle(false);
  }, []);
  const scheduleFade = useCallback(() => {
    if (idleRef.current) clearTimeout(idleRef.current);
    idleRef.current = setTimeout(() => setIdle(true), IDLE_FADE_MS);
  }, []);
  useEffect(() => {
    if (playing) wake();
    return () => {
      if (idleRef.current) clearTimeout(idleRef.current);
    };
  }, [playing, openMode, wake]);

  // ---- push a control patch to the phone (debounced) ----
  const pushControls = useCallback(
    (nextManual: boolean, nextKeys: ControlKeys, immediate = false) => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
      const send = async () => {
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
      };
      if (immediate) void send();
      else debounceRef.current = setTimeout(send, APPLY_DEBOUNCE_MS);
    },
    [phoneId],
  );

  const applyKeys = (partial: Partial<ControlKeys>, commit = true) => {
    const next = { ...keys, ...partial };
    setKeys(next);
    if (!manualOn) setManualOn(true);
    pushControls(true, next, commit);
  };
  const setKey = <K extends keyof ControlKeys>(k: K, v: ControlKeys[K], commit = false) =>
    applyKeys({ [k]: v } as Partial<ControlKeys>, commit);

  const goAuto = () => {
    setManualOn(false);
    setKeys({});
    setInvalidKey(null);
    pushControls(false, {}, true);
  };

  const applyRotation = async (deg: number) => {
    const prev = rotation;
    setRotation(deg);
    try {
      const r = await SetCameraControls(
        phoneId,
        { rotationDegrees: deg } as Parameters<typeof SetCameraControls>[1],
      );
      if (!r.ok) {
        setRotation(prev);
        setMsg(r.message || 'Could not set rotation.');
      }
    } catch (err) {
      setRotation(prev);
      setMsg(String(err));
    }
  };

  // ---- camera switch (cycle button) ----
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
      await load();
      // A camera switch rebuilds the phone's live pipeline; the controller's
      // player is still bound to the old broadcast (or showing the previous
      // camera's failure), so re-attach it against the new camera.
      if (ctl.live || ctl.state.kind === 'error') {
        await new Promise((res) => setTimeout(res, 1200));
        await ctl.reattach();
      }
    } finally {
      setSwitchBusy(false);
    }
  };
  const cycleCamera = () => {
    if (switchBusy || cams.length < 2) return;
    const idx = Math.max(0, cams.findIndex((c) => c.cameraId === activeCamId));
    const next = cams[(idx + 1) % cams.length];
    if (!next || next.cameraId === activeCamId) return;
    if (phoneRecording) setConfirmCam(next.cameraId);
    else doSwitch(next.cameraId);
  };
  const activeCamLabel = cams.find((c) => c.cameraId === activeCamId)?.label ?? activeCamId;

  // ---- drag-a-box picker overlay, shared by Zoom / Exposure / Focus ----
  // The active adjuster names a `rectTarget` (below). Draw a box -> applied on
  // pointer-up -> the box is NOT kept on screen. Esc aborts an in-progress drag.
  type RectTarget = {
    key: 'cropRegionNorm' | 'aeRegionNorm' | 'afRegionNorm';
    /** keys to clear (set undefined) when this rect is applied — the rect wins. */
    clears: (keyof ControlKeys)[];
    /** transient confirmation shown in the hint line after applying. */
    applied: string;
  };
  const [pickRect, setPickRect] = useState<RectNorm | null>(null);
  const [rectNote, setRectNote] = useState<string | null>(null);
  const dragRef = useRef<{ x: number; y: number } | null>(null);
  const rectNoteTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const clamp01 = (n: number) => Math.min(1, Math.max(0, n));
  const evtNorm = (e: PointerEvent, el: HTMLElement) => {
    const b = el.getBoundingClientRect();
    return { x: clamp01((e.clientX - b.left) / b.width), y: clamp01((e.clientY - b.top) / b.height) };
  };
  const flashNote = (text: string) => {
    setRectNote(text);
    if (rectNoteTimer.current) clearTimeout(rectNoteTimer.current);
    rectNoteTimer.current = setTimeout(() => setRectNote(null), 4000);
  };

  const cancelRect = useCallback(() => {
    dragRef.current = null;
    setPickRect(null);
  }, []);

  /** Grow a drawn box so that, mapped into the sensor active array, it has the
   * output's 16:9 shape — then the phone crops to a rectangle that *contains*
   * the whole selection instead of a distorted / re-fit sub-rect. Clamped to
   * stay within the frame. */
  const fitCropRect = useCallback(
    (r: RectNorm): RectNorm => {
      const aw = caps?.activeArrayWidth ?? 0;
      const ah = caps?.activeArrayHeight ?? 0;
      if (aw <= 0 || ah <= 0) return r;
      const want = (LIVE_W / LIVE_H) * (ah / aw); // target (width/height) in 0..1 fraction space
      let w = r.r - r.l;
      let h = r.b - r.t;
      if (w / h < want) w = h * want;
      else h = w / want;
      const cx = (r.l + r.r) / 2;
      const cy = (r.t + r.b) / 2;
      let l = cx - w / 2;
      let t = cy - h / 2;
      let rr = cx + w / 2;
      let b = cy + h / 2;
      if (l < 0) { rr -= l; l = 0; }
      if (rr > 1) { l -= rr - 1; rr = 1; }
      if (t < 0) { b -= t; t = 0; }
      if (b > 1) { t -= b - 1; b = 1; }
      return { l: clamp01(l), t: clamp01(t), r: clamp01(rr), b: clamp01(b) };
    },
    [caps],
  );

  const commitRect = useCallback(
    async (drawn: RectNorm, target: RectTarget) => {
      const rect = target.key === 'cropRegionNorm' ? fitCropRect(drawn) : drawn;
      const clears: Partial<ControlKeys> = {};
      for (const c of target.clears) clears[c] = undefined;
      applyKeys({ [target.key]: rect, ...clears } as Partial<ControlKeys>, true);

      if (target.key === 'cropRegionNorm') {
        // Best-effort: surface the calibration-predicted honoured crop.
        try {
          const w = rect.r - rect.l;
          const h = rect.b - rect.t;
          const res: EffectiveRectResult = await ComputeEffectiveRect(
            phoneId, activeCamId, LIVE_W, LIVE_H,
            1 / Math.max(0.05, Math.max(w, h)),
            (rect.l + rect.r) / 2, (rect.t + rect.b) / 2,
          );
          flashNote(
            res.note ||
              (res.positionHonored
                ? target.applied
                : 'This model recentres off-centre zoom rects — the crop may not match your box.'),
          );
        } catch {
          flashNote(target.applied);
        }
      } else {
        flashNote(target.applied);
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [phoneId, activeCamId, keys, manualOn, fitCropRect],
  );

  // Esc aborts an in-progress drag.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && (pickRect || dragRef.current)) {
        e.preventDefault();
        cancelRect();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [pickRect, cancelRect]);

  // ---- adjuster set (capability-gated) ----
  const modes: Mode[] = [];
  if (caps) {
    modes.push({ id: 'zoom', label: 'Zoom', icon: 'zoom' });
    modes.push({ id: 'exposure', label: 'Exposure', icon: 'exposure' });
    // Focus adjuster: a manual-distance dial and/or drag-to-focus (AF regions).
    // Cameras that expose only one of the two (e.g. the Pixel 6 back camera has
    // AF regions but no manual focus distance) still get the mode.
    if ((caps.hasManualFocus && caps.minFocusDistanceDiopters > 0) || caps.maxAfRegions > 0)
      modes.push({ id: 'focus', label: 'Focus', icon: 'focus' });
    if ((caps.awbModes && caps.awbModes.length > 1) || caps.hasManualWhiteBalance)
      modes.push({ id: 'wb', label: 'White bal.', icon: 'wb' });
    const canVideoStab = caps.videoStabilizationModes?.includes(1);
    const canOptStab = caps.opticalStabilizationModes?.includes(1);
    if (canVideoStab || canOptStab) modes.push({ id: 'stabilize', label: 'Stabilize', icon: 'stabilize' });
    modes.push({ id: 'rotate', label: 'Rotation', icon: 'rotate' });
  }
  const validOpen = modes.some((m) => m.id === openMode) ? openMode : 'zoom';

  // ---- descriptor for the active mode: 0+ overlay rulers, an optional dropdown ----
  type RulerDef = {
    key: string;
    spec: RulerSpec;
    value: number;
    dflt: number;
    tint?: string;
    set: (v: number, commit: boolean) => void;
  };
  type SelectDef = {
    label: string;
    value: string;
    options: { v: string; label: string }[];
    onChange: (v: string) => void;
  };

  let rulers: RulerDef[] = [];
  let select: SelectDef | undefined;
  let note: string | undefined;
  let rectTarget: RectTarget | undefined;

  const hasManualExp = !!(caps?.hasManualSensor && caps.exposureTimeRangeNs && caps.sensitivityRange);

  if (caps && playing) {
    const C = caps;
    switch (validOpen) {
      case 'zoom':
        // Zoom = a centred-zoom ruler AND the drag-a-box off-centre picker.
        // The rect (like every rect) is only offered while manual controls are
        // engaged, and never toggles that state itself.
        if (manualOn) {
          rectTarget = { key: 'cropRegionNorm', clears: ['zoomRatio'], applied: 'Zoom rect applied.' };
        }
        rulers = [
          {
            key: 'zoom',
            spec: spec(C.zoomRatioRange.lo, C.zoomRatioRange.hi, ['zoomOut', 'zoomIn']),
            value: keys.zoomRatio ?? C.zoomRatioRange.lo,
            dflt: C.zoomRatioRange.lo,
            set: (v, c) => applyKeys({ zoomRatio: v, cropRegionNorm: undefined }, c),
          },
        ];
        break;
      case 'exposure': {
        // Folded: metering mode (auto / AE lock / manual) + comp OR shutter+ISO
        // rulers + a drag-a-box spot-metering rect.
        const expMode = keys.manualExposure ? 'manual' : keys.aeLock ? 'lock' : 'auto';
        const opts = [
          { v: 'auto', label: 'auto' },
          { v: 'lock', label: 'AE lock' },
        ];
        if (hasManualExp) opts.push({ v: 'manual', label: 'manual (shutter + ISO)' });
        select = {
          label: 'Exposure',
          value: expMode,
          options: opts,
          onChange: (v) => {
            if (v === 'manual') applyKeys({ manualExposure: true, aeLock: false, aeRegionNorm: undefined }, true);
            else if (v === 'lock') applyKeys({ manualExposure: false, aeLock: true }, true);
            else applyKeys({ manualExposure: false, aeLock: false }, true);
          },
        };
        if (expMode === 'manual' && C.exposureTimeRangeNs && C.sensitivityRange) {
          const eLo = Math.log10(C.exposureTimeRangeNs.lo);
          const eHi = Math.log10(C.exposureTimeRangeNs.hi);
          rulers = [
            {
              key: 'shutter',
              spec: spec(eLo, eHi, ['moon', 'sun']),
              value: Math.log10(keys.sensorExposureTimeNs ?? C.exposureTimeRangeNs.lo),
              dflt: eLo,
              set: (v, c) =>
                applyKeys(
                  { sensorExposureTimeNs: Math.round(10 ** v), manualExposure: true, aeRegionNorm: undefined },
                  c,
                ),
            },
            {
              key: 'iso',
              spec: spec(C.sensitivityRange.lo, C.sensitivityRange.hi, ['shadowLow', 'shadowHigh']),
              value: keys.sensorSensitivityIso ?? C.sensitivityRange.lo,
              dflt: C.sensitivityRange.lo,
              set: (v, c) =>
                applyKeys(
                  { sensorSensitivityIso: Math.round(v), manualExposure: true, aeRegionNorm: undefined },
                  c,
                ),
            },
          ];
        } else {
          rulers = [
            {
              key: 'ec',
              spec: spec(C.aeCompensationRange.lo, C.aeCompensationRange.hi, ['moon', 'sun']),
              value: keys.aeExposureCompensation ?? 0,
              dflt: 0,
              set: (v, c) => setKey('aeExposureCompensation', Math.round(v), c),
            },
          ];
        }
        // Spot-metering rect: manual exposure only, and it does NOT leave manual.
        if (expMode === 'manual' && C.maxAeRegions > 0) {
          rectTarget = {
            key: 'aeRegionNorm',
            clears: ['sensorExposureTimeNs', 'sensorSensitivityIso'],
            applied: 'Metering spot set.',
          };
        }
        break;
      }
      case 'focus': {
        const hasFocusDial = C.hasManualFocus && C.minFocusDistanceDiopters > 0;
        select = {
          label: 'Focus mode',
          value: keys.manualFocus ? 'manual' : 'auto',
          options: [
            { v: 'auto', label: 'auto (continuous)' },
            { v: 'manual', label: hasFocusDial ? 'manual' : 'manual (pick a point)' },
          ],
          onChange: (v) =>
            applyKeys({ manualFocus: v === 'manual', afRegionNorm: undefined }, true),
        };
        if (keys.manualFocus) {
          if (hasFocusDial) {
            rulers = [
              {
                key: 'focus',
                spec: spec(0, C.minFocusDistanceDiopters, ['focusFar', 'focusNear']),
                value: keys.lensFocusDistanceDiopters ?? 0,
                dflt: 0,
                set: (v, c) =>
                  applyKeys(
                    { lensFocusDistanceDiopters: v, manualFocus: true, afRegionNorm: undefined },
                    c,
                  ),
              },
            ];
          }
          // Focus-point rect: manual only, and it does NOT leave manual.
          if (C.maxAfRegions > 0) {
            rectTarget = {
              key: 'afRegionNorm',
              clears: hasFocusDial ? ['lensFocusDistanceDiopters'] : [],
              applied: 'Focus point set.',
            };
            if (!hasFocusDial) note = 'Drag a box on the preview to lock focus on that area.';
          }
        } else {
          note = hasFocusDial
            ? 'Set focus mode to “manual” to dial focus or pick a focus point.'
            : 'Set focus mode to “manual” to pick a focus point.';
        }
        break;
      }
      case 'wb': {
        const opts = (C.awbModes || []).map((m) => ({ v: `awb:${m}`, label: AWB_LABELS[m] ?? `mode ${m}` }));
        if (C.hasManualWhiteBalance) opts.push({ v: 'manual', label: 'manual (RGB gains)' });
        select = {
          label: 'White balance',
          value: keys.manualWhiteBalance
            ? 'manual'
            : `awb:${keys.awbMode ?? (C.awbModes?.includes(1) ? 1 : C.awbModes?.[0] ?? 1)}`,
          options: opts,
          onChange: (v) =>
            v === 'manual'
              ? applyKeys({ manualWhiteBalance: true }, true)
              : applyKeys({ manualWhiteBalance: false, awbMode: parseInt(v.slice(4), 10) }, true),
        };
        // "manual" reveals the R/G/B gain rulers right here — no separate mode.
        if (keys.manualWhiteBalance) {
          rulers = (
            [
              ['wbRedGain', '#ff6b6b'],
              ['wbGreenGain', '#69db7c'],
              ['wbBlueGain', '#74c0fc'],
            ] as const
          ).map(([k, tint]) => ({
            key: k,
            tint,
            spec: spec(C.wbGainRange.lo, C.wbGainRange.hi, ['minus', 'plus']),
            value: keys[k] ?? 1,
            dflt: 1,
            set: (v, c) => setKey(k, v, c),
          }));
        }
        break;
      }
      case 'stabilize': {
        const canV = C.videoStabilizationModes?.includes(1);
        const canO = C.opticalStabilizationModes?.includes(1);
        const opts = [{ v: 'off', label: 'off' }];
        if (canV) opts.push({ v: 'video', label: 'video (digital)' });
        if (canO) opts.push({ v: 'optical', label: 'optical (OIS)' });
        if (canV && canO) opts.push({ v: 'both', label: 'both' });
        const cur =
          keys.videoStabilizationMode === 1 && keys.opticalStabilizationMode === 1
            ? 'both'
            : keys.videoStabilizationMode === 1
              ? 'video'
              : keys.opticalStabilizationMode === 1
                ? 'optical'
                : 'off';
        select = {
          label: 'Stabilization',
          value: cur,
          options: opts,
          onChange: (v) =>
            applyKeys(
              {
                videoStabilizationMode: v === 'video' || v === 'both' ? 1 : 0,
                opticalStabilizationMode: v === 'optical' || v === 'both' ? 1 : 0,
              },
              true,
            ),
        };
        break;
      }
      case 'rotate':
        select = {
          label: 'Rotation',
          value: String(rotation),
          options: ROTATIONS.map((d) => ({ v: String(d), label: `${d}°` })),
          onChange: (v) => applyRotation(parseInt(v, 10)),
        };
        break;
    }
  }
  const activeSelect = select;

  function renderRectPicker(target: RectTarget): ComponentChildren {
    return (
      <div
        className={`zoom-picker rect-${target.key}`}
        onPointerDown={(e) => {
          (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
          const p = evtNorm(e, e.currentTarget as HTMLElement);
          dragRef.current = p;
          setRectNote(null);
          setPickRect({ l: p.x, t: p.y, r: p.x, b: p.y });
        }}
        onPointerMove={(e) => {
          if (!dragRef.current) return;
          const p = evtNorm(e, e.currentTarget as HTMLElement);
          const s = dragRef.current;
          setPickRect({
            l: Math.min(s.x, p.x), t: Math.min(s.y, p.y), r: Math.max(s.x, p.x), b: Math.max(s.y, p.y),
          });
        }}
        onPointerUp={(e) => {
          try {
            (e.currentTarget as HTMLElement).releasePointerCapture(e.pointerId);
          } catch {
            /* not captured */
          }
          const dragged = dragRef.current && pickRect;
          dragRef.current = null;
          setPickRect(null); // the drawn box is never kept on screen
          if (dragged && pickRect && pickRect.r - pickRect.l > 0.04 && pickRect.b - pickRect.t > 0.04) {
            void commitRect(pickRect, target);
          }
        }}
        onPointerCancel={cancelRect}
      >
        {pickRect && (
          <div
            className="zoom-picker-box"
            style={{
              left: `${pickRect.l * 100}%`,
              top: `${pickRect.t * 100}%`,
              width: `${(pickRect.r - pickRect.l) * 100}%`,
              height: `${(pickRect.b - pickRect.t) * 100}%`,
            }}
          />
        )}
      </div>
    );
  }

  // ---- render ----
  return (
    <>
      <div className="section-title">Live preview &amp; camera</div>
      <div className="phonecam" onPointerDownCapture={wake}>
        <div
          className={`viewport-wrap ${idle ? 'idle' : ''}`}
          onMouseEnter={wake}
          onMouseMove={wake}
          onMouseLeave={scheduleFade}
        >
          <LivePreviewVideo
            ctl={ctl}
            idleHint={
              phoneRecording
                ? 'Phone is recording — stop recording to watch live.'
                : 'Press “Watch live” in the command bar to start the stream.'
            }
          />
          {/* Camera name + switcher stay reachable even when the stream errors,
              so a bad camera can always be cycled back out of. */}
          {ctl.state.kind !== 'idle' && (
            <>
              <div className="cam-badges">
                <span className={`cam-badge ${manualOn ? 'manual' : ''}`}>{manualOn ? 'MANUAL' : 'AUTO'}</span>
                <span className="cam-badge dim">{activeCamLabel}</span>
              </div>
              {cams.length > 1 && (
                <button className="cam-cycle-btn" onClick={cycleCamera} disabled={switchBusy} title="Cycle camera">
                  <CycleIcon />
                </button>
              )}
            </>
          )}
          {playing && (
            <>
              {rectTarget && renderRectPicker(rectTarget)}
              {rulers.length > 0 && (
                <div className="overlay-stack">
                  {rulers.map((d) => (
                    <div className="ctrl-slot overlay" key={d.key}>
                      <div className="ruler-hold">
                        <Ruler
                          spec={d.spec}
                          value={d.value}
                          tint={d.tint}
                          onChange={(v) => d.set(v, false)}
                          onCommit={(v) => d.set(v, true)}
                        />
                      </div>
                      <button
                        className="ruler-reset"
                        title="Reset to default"
                        onClick={() => d.set(d.dflt, true)}
                      >
                        <ResetIcon />
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </div>

        {loadErr && <div className="calib-sub">{loadErr}</div>}

        {confirmCam && (
          <div className="confirm-box">
            <p>
              The phone is recording. Switching camera reconfigures the pipeline — it briefly
              interrupts recording and starts a new clip. Continue?
            </p>
            <div className="confirm-actions">
              <button className="btn danger" onClick={() => doSwitch(confirmCam)}>Switch camera</button>
              <button className="btn" onClick={() => setConfirmCam(null)}>Cancel</button>
            </div>
          </div>
        )}

        {playing && caps && (
          <>
            <div className="adjust-row">
              <button
                className="pcam-auto"
                onClick={goAuto}
                disabled={!manualOn}
                title="Return every control to full auto"
              >
                <ResetIcon />
                <span>Full auto</span>
              </button>
              <HScroll className="adjust-scroll" centerKey={validOpen}>
                {modes.map((m) => (
                  <button
                    key={m.id}
                    data-key={m.id}
                    className={`adjust-btn ${validOpen === m.id ? 'open' : ''}`}
                    onClick={() => setOpenMode(m.id)}
                  >
                    {modeIcon(m.icon)}
                    <span>{m.label}</span>
                  </button>
                ))}
              </HScroll>
            </div>

            {activeSelect && (
              <div className="pcam-select-row">
                <label>{activeSelect.label}</label>
                <select
                  value={activeSelect.value}
                  onChange={(e) => activeSelect.onChange((e.target as HTMLSelectElement).value)}
                >
                  {activeSelect.options.map((o) => (
                    <option key={o.v} value={o.v}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </div>
            )}

            {note && <div className="calib-sub">{note}</div>}
          </>
        )}

        {playing && (
          <div className="calib-sub phonecam-hint">
            {rectTarget
              ? validOpen === 'zoom'
                ? 'Drag the ruler to zoom centred, or drag a box on the preview for an off-centre zoom rect (applied on release, Esc cancels).'
                : validOpen === 'focus'
                  ? 'Drag the ruler for manual focus, or drag a box on the preview to focus on that area (applied on release, Esc cancels).'
                  : 'Drag the ruler for exposure, or drag a box on the preview to spot-meter that area (applied on release, Esc cancels).'
              : 'Drag the ruler over the live preview. Values apply to recording too.'}
            {rectNote && <div style={{ marginTop: '4px' }}>{rectNote}</div>}
            {msg && <div style={{ color: 'var(--warn)', marginTop: '4px' }}>{msg}</div>}
            {invalidKey && !msg && <div style={{ color: 'var(--warn)', marginTop: '4px' }}>Rejected: {invalidKey}</div>}
          </div>
        )}
      </div>
    </>
  );
}

