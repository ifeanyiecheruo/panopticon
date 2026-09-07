import type { LiveController } from './LivePreview';
import { RecordIcon, StopIcon, LiveIcon, CalibrateIcon, UnpairIcon, GalleryIcon } from '../lib/icons';

interface Props {
  reachable: boolean;

  phoneRecording: boolean;
  recordBusy: boolean;
  onToggleRecording: () => void;

  live: LiveController;

  calibRunning: boolean;
  calibLabel: string; // "Run calibration" | "Re-run"
  canCalibrate: boolean; // phone reported a manufacturer/model to key by
  onCalibrate: () => void;

  onViewGallery: () => void;

  unpairOpen: boolean;
  onToggleUnpair: () => void;
}

/** Every phone action in one row at the top of Phone-detail. The expanded UIs
 * (live video, calibration progress, unpair confirmations) render below. */
export function PhoneCommandBar({
  reachable,
  phoneRecording,
  recordBusy,
  onToggleRecording,
  live,
  calibRunning,
  calibLabel,
  canCalibrate,
  onCalibrate,
  onViewGallery,
  unpairOpen,
  onToggleUnpair,
}: Props) {
  const livePreviewOn = live.live;
  const recordDisabled = !reachable || recordBusy || livePreviewOn || calibRunning;
  const watchDisabled = !reachable || phoneRecording || calibRunning || live.busy;
  const calibDisabled = !reachable || phoneRecording || calibRunning || livePreviewOn || !canCalibrate;

  return (
    <div className="cmd-bar">
      <button
        className={`btn ${phoneRecording ? 'danger' : 'primary'}`}
        disabled={recordDisabled}
        onClick={onToggleRecording}
        title={
          livePreviewOn
            ? 'Stop the live preview first'
            : calibRunning
              ? 'Calibration is running'
              : undefined
        }
      >
        {phoneRecording ? <StopIcon /> : <RecordIcon />}
        {recordBusy ? '…' : phoneRecording ? 'Stop recording' : 'Start recording'}
      </button>

      {live.live ? (
        <button className="btn danger" onClick={live.stop}>
          <StopIcon /> Stop live
        </button>
      ) : (
        <button
          className="btn"
          disabled={watchDisabled}
          onClick={live.watch}
          title={phoneRecording ? 'Stop recording to watch live' : undefined}
        >
          <LiveIcon /> {live.busy ? 'Starting…' : 'Watch live'}
        </button>
      )}

      <button className="btn" onClick={onViewGallery}>
        <GalleryIcon /> Gallery
      </button>

      <button
        className="btn"
        disabled={calibDisabled}
        onClick={onCalibrate}
        title={
          !canCalibrate
            ? 'Phone reported no manufacturer/model to key calibration by'
            : phoneRecording
              ? 'Stop recording to calibrate'
              : livePreviewOn
                ? 'Stop the live preview first'
                : undefined
        }
      >
        <CalibrateIcon /> {calibRunning ? 'Running…' : calibLabel}
      </button>

      <div className="cmd-bar-spacer" />

      <button className={`btn ${unpairOpen ? 'danger' : ''}`} onClick={onToggleUnpair}>
        <UnpairIcon /> Unpair
      </button>
    </div>
  );
}
