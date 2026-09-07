package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"sync"
	"time"

	"panopticon-controller/internal/appdirs"
	"panopticon-controller/internal/calibration"
	"panopticon-controller/internal/dbstore"
	"panopticon-controller/internal/pairing"
	"panopticon-controller/internal/phoneapi"
	"panopticon-controller/internal/syncer"
	"panopticon-controller/internal/unpair"

	wailsRuntime "github.com/wailsapp/wails/v2/pkg/runtime"
)

// App is the Wails-bound backend: every exported method here becomes
// callable from the frontend as window.go.main.App.<Method>(...).
type App struct {
	ctx      context.Context
	store    *dbstore.Store
	dirs     appdirs.Dirs
	syncMgr  *syncer.Manager
	quitting bool

	// livePriorMode remembers the mode a phone was in before StartLivePreview
	// switched it to "live", so StopLivePreview can put it back. Keyed by
	// phoneID; guarded by liveMu.
	liveMu        sync.Mutex
	livePriorMode map[string]string
}

func NewApp(store *dbstore.Store, dirs appdirs.Dirs, syncMgr *syncer.Manager) *App {
	return &App{store: store, dirs: dirs, syncMgr: syncMgr, livePriorMode: map[string]string{}}
}

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
}

// ---- View types returned to the frontend ----

type PhoneView struct {
	ID             string `json:"id"`
	Name           string `json:"name"`
	Manufacturer   string `json:"manufacturer"`
	Model          string `json:"model"`
	BaseURL        string `json:"baseUrl"`
	Reachable      bool   `json:"reachable"`
	Status         string `json:"status"` // "recording" | "standby" | "unreachable"
	BatteryPercent int    `json:"batteryPercent"`
	HasBattery     bool   `json:"hasBattery"`
	Charging       bool   `json:"charging"`
	LastSeenMs     int64  `json:"lastSeenMs"`
	SyncCursorMs   int64  `json:"syncCursorMs"`
	DiskUsageBytes int64  `json:"diskUsageBytes"`
}

// ListPhones returns every paired phone with a fresh, best-effort live
// status probe (short timeout — phoneapi.Status already applies its own
// metadata timeout). Fetched concurrently since the Fleet screen wants all
// cards to resolve without one slow phone stalling the others.
func (a *App) ListPhones() ([]PhoneView, error) {
	phones, err := a.store.ListPhones()
	if err != nil {
		return nil, err
	}

	views := make([]PhoneView, len(phones))
	var wg sync.WaitGroup
	for i, p := range phones {
		views[i] = PhoneView{
			ID: p.ID, Name: p.Name, Manufacturer: p.Manufacturer, Model: p.Model,
			BaseURL: p.BaseURL, LastSeenMs: p.LastSeenMs, SyncCursorMs: p.SyncCursorMs,
		}
		usage, _ := a.store.DiskUsageBytes(p.ID)
		views[i].DiskUsageBytes = usage

		wg.Add(1)
		go func(idx int, phone dbstore.Phone) {
			defer wg.Done()
			client := phoneapi.New(phone.BaseURL, phone.Token)
			status, err := client.Status(a.ctxOrBackground())
			if err != nil {
				views[idx].Reachable = false
				views[idx].Status = "unreachable"
				return
			}
			views[idx].Reachable = true
			views[idx].HasBattery = true
			views[idx].BatteryPercent = status.BatteryPercent
			views[idx].Charging = status.Charging
			if status.Status == "recording" {
				views[idx].Status = "recording"
			} else {
				views[idx].Status = "standby"
			}
		}(i, p)
	}
	wg.Wait()
	return views, nil
}

func (a *App) ctxOrBackground() context.Context {
	if a.ctx != nil {
		return a.ctx
	}
	return context.Background()
}

// ---- Add phone ----

type ParsedInvite struct {
	Address string `json:"address"`
	Code    string `json:"code"`
	OK      bool   `json:"ok"`
}

// ParseInviteURL lets the frontend auto-fill both paste-form fields when
// either receives a full invite URL, per HANDOFF-controller-ux.md's
// Add-phone flow.
func (a *App) ParseInviteURL(raw string) ParsedInvite {
	addr, code, ok := phoneapi.ParseInviteURL(raw)
	return ParsedInvite{Address: addr, Code: code, OK: ok}
}

type AddPhoneResult struct {
	OK      bool       `json:"ok"`
	Outcome string     `json:"outcome"` // "ok" | "unreachable" | "invalid_invite" | "other"
	Message string     `json:"message"`
	Phone   *PhoneView `json:"phone,omitempty"`
}

// AddPhone drives POST /api/pair via internal/pairing and reports a
// classified outcome so the UI can show the two distinct failure messages
// the handoff doc calls for (unreachable address vs. invalid invite).
func (a *App) AddPhone(address, code string) AddPhoneResult {
	ctx, cancel := context.WithTimeout(a.ctxOrBackground(), 20*time.Second)
	defer cancel()

	result, err := pairing.AddPhone(ctx, a.store, address, code)
	if err != nil {
		var pe *pairing.Error
		if errors.As(err, &pe) {
			outcome := "other"
			switch pe.Outcome {
			case pairing.OutcomeUnreachable:
				outcome = "unreachable"
			case pairing.OutcomeInvalidInvite:
				outcome = "invalid_invite"
			}
			return AddPhoneResult{OK: false, Outcome: outcome, Message: pe.Message}
		}
		return AddPhoneResult{OK: false, Outcome: "other", Message: err.Error()}
	}

	return AddPhoneResult{
		OK:      true,
		Outcome: "ok",
		Phone: &PhoneView{
			ID: result.PhoneID, Name: result.Name,
			Manufacturer: result.Manufacturer, Model: result.Model,
			Status: "standby",
		},
	}
}

// ---- Phone detail ----

type PhoneDetailView struct {
	Phone       PhoneView        `json:"phone"`
	Status      *phoneapi.Status `json:"status,omitempty"`
	StatusError string           `json:"statusError,omitempty"`
	Config      *phoneapi.Config `json:"config,omitempty"`
	ConfigError string           `json:"configError,omitempty"`
	Calibration calibration.View `json:"calibration"`
}

// GetPhoneDetail is the Phone-detail screen's data source: raw
// GET /api/status + GET /api/config (live preview / adjusters are still out
// of scope for this slice), plus the manufacturer+model calibration lookup
// (HANDOFF-controller-ux.md "Calibration data model") — re-checked
// opportunistically against the phone on every open.
func (a *App) GetPhoneDetail(phoneID string) (PhoneDetailView, error) {
	phone, err := a.store.GetPhone(phoneID)
	if err != nil {
		return PhoneDetailView{}, err
	}
	usage, _ := a.store.DiskUsageBytes(phoneID)

	view := PhoneView{
		ID: phone.ID, Name: phone.Name, Manufacturer: phone.Manufacturer, Model: phone.Model,
		BaseURL: phone.BaseURL, LastSeenMs: phone.LastSeenMs, SyncCursorMs: phone.SyncCursorMs,
		DiskUsageBytes: usage,
	}

	client := phoneapi.New(phone.BaseURL, phone.Token)
	detail := PhoneDetailView{Phone: view}

	status, err := client.Status(a.ctxOrBackground())
	if err != nil {
		detail.StatusError = err.Error()
		detail.Phone.Reachable = false
		detail.Phone.Status = "unreachable"
	} else {
		detail.Status = &status
		detail.Phone.Reachable = true
		detail.Phone.HasBattery = true
		detail.Phone.BatteryPercent = status.BatteryPercent
		detail.Phone.Charging = status.Charging
		if status.Status == "recording" {
			detail.Phone.Status = "recording"
		} else {
			detail.Phone.Status = "standby"
		}
	}

	config, err := client.Config(a.ctxOrBackground())
	if err != nil {
		detail.ConfigError = err.Error()
	} else {
		detail.Config = &config
	}

	// Re-check calibration against the phone opportunistically (cheap: one
	// GET that 404s fast if the phone has nothing). Never fatal — a failure
	// just means we show whatever's already cached for this model.
	if detail.Phone.Reachable {
		if _, err := calibration.IngestOpportunistic(a.ctxOrBackground(), a.store, phone); err != nil {
			log.Printf("phone detail: calibration re-check for %s: %v", phoneID, err)
		}
	}
	calView, err := calibration.Lookup(a.store, phone)
	if err != nil {
		log.Printf("phone detail: calibration lookup for %s: %v", phoneID, err)
	}
	detail.Calibration = calView

	return detail, nil
}

// ---- Config (editable from Phone detail) ----

type ConfigResult struct {
	OK     bool             `json:"ok"`
	Error  string           `json:"error,omitempty"`
	Config *phoneapi.Config `json:"config,omitempty"`
}

// SetConfig batch-updates a phone's device config (POST /api/config) and returns
// the full resulting document so the form can re-seed from it. (Frame rotation
// is a camera setting - see SetCameraControls.)
func (a *App) SetConfig(phoneID string, patch phoneapi.ConfigPatch) ConfigResult {
	phone, err := a.store.GetPhone(phoneID)
	if err != nil {
		return ConfigResult{Error: err.Error()}
	}
	client := phoneapi.New(phone.BaseURL, phone.Token)
	cfg, err := client.SetConfig(a.ctxOrBackground(), patch)
	if err != nil {
		return ConfigResult{Error: err.Error()}
	}
	// The device name is the phone's name — keep the local pairing row in sync
	// so it propagates through Fleet, Gallery filters and clip attribution.
	if cfg.DeviceName != "" && cfg.DeviceName != phone.Name {
		if err := a.store.UpdatePhoneName(phoneID, cfg.DeviceName); err != nil {
			log.Printf("set config: rename phone %s: %v", phoneID, err)
		}
	}
	return ConfigResult{OK: true, Config: &cfg}
}

// ---- Recording (start/stop from Phone detail's command bar) ----

// SetRecording moves a phone into ("record") or out of ("standby") its
// motion-gated recording pipeline. This is the one controller-initiated path
// that *starts* recording; every camera-exclusive feature (live preview,
// calibration) still requires the phone be taken to standby first.
func (a *App) SetRecording(phoneID string, recording bool) CameraActionResult {
	phone, err := a.store.GetPhone(phoneID)
	if err != nil {
		return CameraActionResult{Outcome: "other", Message: err.Error()}
	}
	client := phoneapi.New(phone.BaseURL, phone.Token)
	mode := "standby"
	if recording {
		mode = "record"
	}
	err = client.SetMode(a.ctxOrBackground(), mode)
	switch {
	case err == nil:
		return CameraActionResult{OK: true, Outcome: "ok"}
	case errors.Is(err, phoneapi.ErrUnreachable):
		return CameraActionResult{Outcome: "unreachable", Message: "Could not reach the phone."}
	default:
		return CameraActionResult{Outcome: "other", Message: err.Error()}
	}
}

// ---- Calibration (re-run driven from Phone detail) ----

type CalibrationStartResult struct {
	OK      bool   `json:"ok"`
	Outcome string `json:"outcome"` // "ok" | "running" | "recording" | "unreachable" | "other"
	RunID   string `json:"runId,omitempty"`
	Message string `json:"message,omitempty"`
}

// StartCalibration triggers a device-wide sweep on one phone (Phone detail's
// "Run calibration" / "Re-run" action). Poll GetCalibrationProgress with the
// returned runId; the completed result is ingested into the model-keyed
// store automatically once the sweep finishes.
func (a *App) StartCalibration(phoneID string) CalibrationStartResult {
	phone, err := a.store.GetPhone(phoneID)
	if err != nil {
		return CalibrationStartResult{Outcome: "other", Message: err.Error()}
	}
	client := phoneapi.New(phone.BaseURL, phone.Token)
	resp, err := client.StartCalibration(a.ctxOrBackground())
	switch {
	case err == nil:
		return CalibrationStartResult{OK: true, Outcome: "ok", RunID: resp.RunID}
	case errors.Is(err, phoneapi.ErrCalibrationRunning):
		return CalibrationStartResult{Outcome: "running", Message: "A calibration sweep is already running on this phone."}
	case errors.Is(err, phoneapi.ErrPhoneRecording):
		return CalibrationStartResult{Outcome: "recording", Message: "The phone is recording. Stop recording on the phone before calibrating."}
	case errors.Is(err, phoneapi.ErrUnreachable):
		return CalibrationStartResult{Outcome: "unreachable", Message: "Could not reach the phone."}
	default:
		return CalibrationStartResult{Outcome: "other", Message: err.Error()}
	}
}

// ---- Live preview (driven from Phone detail) ----

type LivePreviewResult struct {
	OK           bool   `json:"ok"`
	Outcome      string `json:"outcome"` // "ok" | "recording" | "unreachable" | "other"
	PlaylistPath string `json:"playlistPath,omitempty"`
	Message      string `json:"message,omitempty"`
}

// StartLivePreview moves one phone into live mode and starts its HLS broadcast,
// returning the same-origin playlist path the frontend should hand to hls.js
// (served by liveProxyHandler). Mirrors StartCalibration's contract: a phone
// that's actively recording is left alone (Outcome "recording") - the caller's
// UI disables the button with that reason rather than us force-stopping a
// recording.
func (a *App) StartLivePreview(phoneID string) LivePreviewResult {
	phone, err := a.store.GetPhone(phoneID)
	if err != nil {
		return LivePreviewResult{Outcome: "other", Message: err.Error()}
	}
	client := phoneapi.New(phone.BaseURL, phone.Token)

	status, err := client.Status(a.ctxOrBackground())
	if err != nil {
		if errors.Is(err, phoneapi.ErrUnreachable) {
			return LivePreviewResult{Outcome: "unreachable", Message: "Could not reach the phone."}
		}
		return LivePreviewResult{Outcome: "other", Message: err.Error()}
	}
	if status.Mode == "record" {
		return LivePreviewResult{Outcome: "recording", Message: "The phone is recording. Stop recording on the phone before watching live."}
	}

	priorMode := status.Mode // "standby" or already "live"
	if status.Mode != "live" {
		if err := client.SetMode(a.ctxOrBackground(), "live"); err != nil {
			if errors.Is(err, phoneapi.ErrUnreachable) {
				return LivePreviewResult{Outcome: "unreachable", Message: "Could not reach the phone."}
			}
			return LivePreviewResult{Outcome: "other", Message: err.Error()}
		}
	}
	if _, err := client.LiveStart(a.ctxOrBackground()); err != nil {
		return LivePreviewResult{Outcome: "other", Message: err.Error()}
	}

	a.liveMu.Lock()
	a.livePriorMode[phoneID] = priorMode
	a.liveMu.Unlock()

	return LivePreviewResult{OK: true, Outcome: "ok", PlaylistPath: "/live/" + phoneID + "/live.m3u8"}
}

// StopLivePreview ends the broadcast and restores the phone to the mode it was
// in before StartLivePreview (defaulting to "record", the sticky resting mode).
// Best-effort: a phone that's already unreachable just stops on its own via its
// inactivity watchdog.
func (a *App) StopLivePreview(phoneID string) error {
	phone, err := a.store.GetPhone(phoneID)
	if err != nil {
		return err
	}
	client := phoneapi.New(phone.BaseURL, phone.Token)

	a.liveMu.Lock()
	prior, ok := a.livePriorMode[phoneID]
	delete(a.livePriorMode, phoneID)
	a.liveMu.Unlock()
	if !ok || prior == "" || prior == "live" {
		prior = "record"
	}

	_ = client.LiveStop(a.ctxOrBackground())
	return client.SetMode(a.ctxOrBackground(), prior)
}

type CalibrationProgressResult struct {
	OK       bool                          `json:"ok"`
	Error    string                        `json:"error,omitempty"`
	Progress *phoneapi.CalibrationProgress `json:"progress,omitempty"`
	// Stored is set true on the poll where a just-completed sweep's result was
	// ingested into the model-keyed calibration store.
	Stored bool `json:"stored"`
}

// GetCalibrationProgress polls one phone's sweep. When it reports "completed"
// this also pulls the full result and writes it under the phone's
// manufacturer+model key (a manual re-run always overwrites, per the data
// model).
func (a *App) GetCalibrationProgress(phoneID, runID string) CalibrationProgressResult {
	phone, err := a.store.GetPhone(phoneID)
	if err != nil {
		return CalibrationProgressResult{Error: err.Error()}
	}
	client := phoneapi.New(phone.BaseURL, phone.Token)
	prog, err := client.CalibrationStatusCall(a.ctxOrBackground(), runID)
	if err != nil {
		return CalibrationProgressResult{Error: err.Error()}
	}

	res := CalibrationProgressResult{OK: true, Progress: &prog}
	if prog.Status == "completed" {
		result, raw, rerr := client.CalibrationResultRaw(a.ctxOrBackground())
		if rerr != nil {
			log.Printf("calibration progress: fetch completed result for %s: %v", phoneID, rerr)
		} else if err := calibration.StoreResult(a.store, phone, result, raw); err != nil {
			log.Printf("calibration progress: store result for %s: %v", phoneID, err)
		} else {
			res.Stored = true
		}
	}
	return res
}

// CancelCalibration cooperatively stops an in-progress sweep on one phone.
func (a *App) CancelCalibration(phoneID, runID string) error {
	phone, err := a.store.GetPhone(phoneID)
	if err != nil {
		return err
	}
	client := phoneapi.New(phone.BaseURL, phone.Token)
	return client.CancelCalibration(a.ctxOrBackground(), runID)
}

// ---- Camera selection + manual controls (driven from Phone detail) ----

type CamerasResult struct {
	OK      bool                  `json:"ok"`
	Error   string                `json:"error,omitempty"`
	Cameras []phoneapi.CameraInfo `json:"cameras"`
}

// ListCameras returns the phone's physical cameras and which one is active.
func (a *App) ListCameras(phoneID string) CamerasResult {
	phone, err := a.store.GetPhone(phoneID)
	if err != nil {
		return CamerasResult{Error: err.Error()}
	}
	client := phoneapi.New(phone.BaseURL, phone.Token)
	resp, err := client.Cameras(a.ctxOrBackground())
	if err != nil {
		return CamerasResult{Error: err.Error()}
	}
	return CamerasResult{OK: true, Cameras: resp.Cameras}
}

type CameraActionResult struct {
	OK      bool   `json:"ok"`
	Outcome string `json:"outcome"` // "ok" | "unknown_camera" | "invalid_key" | "unreachable" | "other"
	Message string `json:"message,omitempty"`
	// InvalidKey is set only with outcome "invalid_key".
	InvalidKey string `json:"invalidKey,omitempty"`
}

// SetActiveCamera switches which physical camera the phone's pipelines use.
// This is a disruptive reconfigure on the phone (like a mode switch); a camera
// switch mid-recording ends the current clip and starts a new one.
func (a *App) SetActiveCamera(phoneID, cameraID string) CameraActionResult {
	phone, err := a.store.GetPhone(phoneID)
	if err != nil {
		return CameraActionResult{Outcome: "other", Message: err.Error()}
	}
	client := phoneapi.New(phone.BaseURL, phone.Token)
	_, err = client.SetActiveCamera(a.ctxOrBackground(), cameraID)
	switch {
	case err == nil:
		return CameraActionResult{OK: true, Outcome: "ok"}
	case errors.Is(err, phoneapi.ErrUnknownCamera):
		return CameraActionResult{Outcome: "unknown_camera", Message: "The phone has no camera with that id."}
	case errors.Is(err, phoneapi.ErrUnreachable):
		return CameraActionResult{Outcome: "unreachable", Message: "Could not reach the phone."}
	default:
		return CameraActionResult{Outcome: "other", Message: err.Error()}
	}
}

// CameraControlsView is Phone-detail's adjuster-panel data source: the active
// camera's declared ranges, the current manual-control state, and the model's
// calibration cameras (for the zoom-rect picker's predicted-crop overlay).
type CameraControlsView struct {
	OK           bool                          `json:"ok"`
	Error        string                        `json:"error,omitempty"`
	Capabilities *phoneapi.CameraCapabilities  `json:"capabilities,omitempty"`
	State        *phoneapi.CameraStateResponse `json:"state,omitempty"`
	Calibration  calibration.View              `json:"calibration"`
}

// GetCameraControls fetches GET /api/camera/capabilities (active camera) +
// GET /api/camera/state in one round trip for the adjuster panel, plus the
// model-keyed calibration summary already used elsewhere in Phone detail.
func (a *App) GetCameraControls(phoneID string) CameraControlsView {
	phone, err := a.store.GetPhone(phoneID)
	if err != nil {
		return CameraControlsView{Error: err.Error()}
	}
	client := phoneapi.New(phone.BaseURL, phone.Token)

	state, err := client.CameraState(a.ctxOrBackground())
	if err != nil {
		return CameraControlsView{Error: err.Error()}
	}
	caps, err := client.CameraCapabilities(a.ctxOrBackground(), state.CameraID)
	if err != nil {
		return CameraControlsView{Error: err.Error()}
	}
	calView, cerr := calibration.Lookup(a.store, phone)
	if cerr != nil {
		log.Printf("camera controls: calibration lookup for %s: %v", phoneID, cerr)
	}
	return CameraControlsView{OK: true, Capabilities: &caps, State: &state, Calibration: calView}
}

// SetCameraControls applies a new manual-control state on the phone
// (validate-then-apply; a bad key comes back as outcome "invalid_key" naming
// the field). The phone persists the state so it also governs recording.
func (a *App) SetCameraControls(phoneID string, patch phoneapi.CameraStatePatch) CameraActionResult {
	phone, err := a.store.GetPhone(phoneID)
	if err != nil {
		return CameraActionResult{Outcome: "other", Message: err.Error()}
	}
	client := phoneapi.New(phone.BaseURL, phone.Token)
	_, err = client.SetCameraState(a.ctxOrBackground(), patch)
	switch {
	case err == nil:
		return CameraActionResult{OK: true, Outcome: "ok"}
	case errors.Is(err, phoneapi.ErrUnreachable):
		return CameraActionResult{Outcome: "unreachable", Message: "Could not reach the phone."}
	default:
		var ck *phoneapi.InvalidControlKeyError
		if errors.As(err, &ck) {
			return CameraActionResult{Outcome: "invalid_key", InvalidKey: ck.Key, Message: ck.Reason}
		}
		return CameraActionResult{Outcome: "other", Message: err.Error()}
	}
}

// ComputeEffectiveRect maps a requested zoom + centre for one camera at one
// output resolution to the crop the phone's HAL will actually apply, using the
// stored empirical calibration for the phone's model. Backs the zoom-rect
// picker's predicted-crop overlay.
func (a *App) ComputeEffectiveRect(
	phoneID, cameraID string, width, height int, zoom, centerX, centerY float64,
) (calibration.EffectiveRectResult, error) {
	phone, err := a.store.GetPhone(phoneID)
	if err != nil {
		return calibration.EffectiveRectResult{}, err
	}
	key := calibration.ModelKey(phone.Manufacturer, phone.Model)
	entry, err := a.store.GetCalibration(key)
	if err != nil {
		return calibration.EffectiveRectResult{}, fmt.Errorf("no calibration for this model: %w", err)
	}
	return calibration.EffectiveRect(entry, cameraID, width, height, zoom, centerX, centerY)
}

// ---- Gallery / Trash ----
//
// The UX says "clip" but a clip is a group: one gallery item per contiguous
// run of segments (see internal/dbstore + internal/syncer). ClipView carries
// the aggregate span/size plus the ordered Segments the player walks end to
// end; SegmentView is one file within it.

type SegmentView struct {
	Filename    string `json:"filename"`
	VideoURL    string `json:"videoUrl"`
	DurationMs  int64  `json:"durationMs"`
	CreatedAtMs int64  `json:"createdAtMs"`
}

type ClipView struct {
	PhoneID      string        `json:"phoneId"`
	PhoneName    string        `json:"phoneName"`
	ClipID       string        `json:"clipId"`
	State        string        `json:"state"`
	StartedAtMs  int64         `json:"startedAtMs"`
	EndedAtMs    int64         `json:"endedAtMs"`
	DurationMs   int64         `json:"durationMs"` // wall-clock span ended-started
	SizeBytes    int64         `json:"sizeBytes"`
	ThumbnailURL string        `json:"thumbnailUrl"`
	HasThumbnail bool          `json:"hasThumbnail"`
	Segments     []SegmentView `json:"segments"`
}

// ListClips returns active clips for the aggregate Gallery, optionally
// filtered to one phone (phoneID == "" means every phone, i.e. the "All"
// chip).
func (a *App) ListClips(phoneID string) ([]ClipView, error) {
	return a.listClipsByState(phoneID, dbstore.ClipActive)
}

// ListTrash returns trashed clips across every phone (Trash has no
// phone-filter chip row per the handoff doc).
func (a *App) ListTrash() ([]ClipView, error) {
	return a.listClipsByState("", dbstore.ClipTrashed)
}

func (a *App) listClipsByState(phoneID string, state dbstore.ClipState) ([]ClipView, error) {
	clips, err := a.store.ListClips(phoneID, state)
	if err != nil {
		return nil, err
	}
	// Cache phone names to avoid one DB round-trip per clip.
	names := make(map[string]string)
	out := make([]ClipView, len(clips))
	for i, c := range clips {
		name, ok := names[c.PhoneID]
		if !ok {
			if p, err := a.store.GetPhone(c.PhoneID); err == nil {
				name = p.Name
			} else {
				name = c.PhoneID
			}
			names[c.PhoneID] = name
		}

		segs, err := a.store.ListSegmentsForClip(c.ID)
		if err != nil {
			return nil, err
		}
		segViews := make([]SegmentView, len(segs))
		for j, s := range segs {
			segViews[j] = SegmentView{
				Filename:    s.Filename,
				VideoURL:    archiveURL(c.PhoneID, s.Filename),
				DurationMs:  s.DurationMs,
				CreatedAtMs: s.CreatedAtMs,
			}
		}

		cv := ClipView{
			PhoneID: c.PhoneID, PhoneName: name, ClipID: c.ID, State: string(c.State),
			StartedAtMs: c.StartedAtMs, EndedAtMs: c.EndedAtMs,
			DurationMs: c.EndedAtMs - c.StartedAtMs, SizeBytes: c.SizeBytes,
			Segments: segViews,
		}
		// Thumbnail is the first segment's <filename>.jpg.
		if len(segs) > 0 {
			cv.ThumbnailURL = archiveURL(c.PhoneID, segs[0].Filename+".jpg")
			cv.HasThumbnail = segs[0].ThumbnailPath != ""
		}
		out[i] = cv
	}
	return out, nil
}

func archiveURL(phoneID, name string) string {
	return "/archive/" + phoneID + "/" + name
}

// TrashClip: active -> trashed (segment files stay on disk, restorable).
func (a *App) TrashClip(phoneID, clipID string) error {
	return a.store.SetClipState(phoneID, clipID, dbstore.ClipTrashed)
}

// RestoreClip: trashed -> active.
func (a *App) RestoreClip(phoneID, clipID string) error {
	return a.store.SetClipState(phoneID, clipID, dbstore.ClipActive)
}

// DeleteClipPermanently: trashed -> purged. Deletes every segment's on-disk
// file + thumbnail immediately (permanent-on-disk right away per the handoff
// doc) but keeps the DB rows as tombstones — the segment rows stop resync from
// resurrecting the files, and the eviction-probe loop that would eventually
// drop the tombstones entirely is out of scope for this slice (see README).
func (a *App) DeleteClipPermanently(phoneID, clipID string) error {
	segs, err := a.store.ListSegmentsForClip(clipID)
	if err != nil {
		return err
	}
	for _, s := range segs {
		if s.LocalPath != "" {
			if err := removeIfExists(s.LocalPath); err != nil {
				log.Printf("delete segment file: %v", err)
			}
		}
		if s.ThumbnailPath != "" {
			if err := removeIfExists(s.ThumbnailPath); err != nil {
				log.Printf("delete segment thumbnail: %v", err)
			}
		}
	}
	return a.store.SetClipState(phoneID, clipID, dbstore.ClipPurged)
}

// EmptyTrash purges every trashed clip (bulk version of DeleteClipPermanently).
func (a *App) EmptyTrash() (int, error) {
	clips, err := a.store.ListClips("", dbstore.ClipTrashed)
	if err != nil {
		return 0, err
	}
	for _, c := range clips {
		if err := a.DeleteClipPermanently(c.PhoneID, c.ID); err != nil {
			log.Printf("empty trash: %v", err)
		}
	}
	return len(clips), nil
}

// ---- Unpair / force-unpair ----

type UnpairResult struct {
	OK      bool   `json:"ok"`
	Outcome string `json:"outcome"` // "ok" | "needs_confirmation" | "unreachable" | "revoke_failed" | "other"
	// UnsyncedCount is meaningful only with outcome "needs_confirmation".
	UnsyncedCount int    `json:"unsyncedCount"`
	Message       string `json:"message,omitempty"`
}

func unpairResult(r unpair.Result) UnpairResult {
	out := UnpairResult{UnsyncedCount: r.UnsyncedCount, Message: r.Message}
	switch r.Outcome {
	case unpair.OutcomeOK:
		out.OK, out.Outcome = true, "ok"
	case unpair.OutcomeNeedsConfirmation:
		out.Outcome = "needs_confirmation"
	case unpair.OutcomeUnreachable:
		out.Outcome = "unreachable"
	case unpair.OutcomeRevokeFailed:
		out.Outcome = "revoke_failed"
	default:
		out.Outcome = "other"
	}
	return out
}

// UnpairPhone is the safe unpair path (HANDOFF-controller-ux.md). With
// confirmed=false it first checks for clips the phone still has that we never
// archived and returns outcome "needs_confirmation" (changing nothing) if
// there are any; call again with confirmed=true to proceed. It removes the
// local pairing only after the phone's token is actually revoked. Already-
// archived clips are kept.
func (a *App) UnpairPhone(phoneID string, confirmed bool) UnpairResult {
	ctx, cancel := context.WithTimeout(a.ctxOrBackground(), 20*time.Second)
	defer cancel()
	res, _ := unpair.Unpair(ctx, a.store, phoneID, confirmed)
	return unpairResult(res)
}

// ForceUnpairPhone removes the local pairing regardless of whether the phone
// can be reached or its token revoked. Best-effort revoke attempt; no
// unsynced-clips check (the phone may be unreachable). Needs a more
// deliberate confirmation in the UI than plain Unpair.
func (a *App) ForceUnpairPhone(phoneID string) UnpairResult {
	ctx, cancel := context.WithTimeout(a.ctxOrBackground(), 20*time.Second)
	defer cancel()
	res, _ := unpair.Force(ctx, a.store, phoneID)
	return unpairResult(res)
}

// ---- Window / app lifecycle (tray integration) ----

// ShowWindow is called from the tray's "Open" item.
func (a *App) ShowWindow() {
	if a.ctx != nil {
		wailsRuntime.WindowShow(a.ctx)
		wailsRuntime.WindowUnminimise(a.ctx)
	}
}

// RequestQuit is called both from a frontend "Quit" affordance and from the
// tray's "Quit" item — actually tears everything down rather than just
// hiding the window.
func (a *App) RequestQuit() {
	a.quitting = true
	if a.syncMgr != nil {
		a.syncMgr.Stop()
	}
	if a.ctx != nil {
		wailsRuntime.Quit(a.ctx)
	}
}

// IsQuitting reports whether a real quit is underway, so main.go's
// OnBeforeClose handler knows whether to hide-to-tray or let the window
// actually close.
func (a *App) IsQuitting() bool {
	return a.quitting
}

func removeIfExists(path string) error {
	err := os.Remove(path)
	if err != nil && os.IsNotExist(err) {
		return nil
	}
	return err
}
