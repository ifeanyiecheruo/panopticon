import type { LiveController } from './LivePreview';
import { RecordIcon, StopIcon, CalibrateIcon, UnpairIcon, GalleryIcon } from '../lib/icons';

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
 * (calibration progress, unpair confirmations) render below; the camera view has its own
 * play/pause control. */
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
  // Starting to record while the live view is up stops the broadcast first (PhoneDetail); only
  // the moment it is still connecting is off limits, since that start can't be taken back.
  const recordDisabled = !reachable || recordBusy || live.busy || calibRunning;
  const calibDisabled = !reachable || phoneRecording || calibRunning || livePreviewOn || !canCalibrate;

  return (
    <div className="cmd-bar">
      <button
        className={`btn ${phoneRecording ? 'danger' : 'primary'}`}
        disabled={recordDisabled}
        onClick={onToggleRecording}
        title={
          live.busy
            ? 'The live view is still connecting'
            : calibRunning
              ? 'Calibration is running'
              : undefined
        }
      >
        {phoneRecording ? <StopIcon /> : <RecordIcon />}
        {recordBusy ? '…' : phoneRecording ? 'Stop recording' : 'Start recording'}
      </button>

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
                ? 'Pause the preview first'
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
