package phoneapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
)

// Sentinels specific to the calibration routes.
var (
	// ErrCalibrationRunning is POST /api/calibration/start's 409: a sweep is
	// already in progress.
	ErrCalibrationRunning = errors.New("calibration already running")

	// ErrPhoneRecording is POST /api/calibration/start's 409 when the phone is
	// in RECORD mode - the camera is busy and recording has to be explicitly
	// stopped (POST /api/mode standby) first.
	ErrPhoneRecording = errors.New("phone is recording; stop it before calibrating")

	// ErrNoCalibrationResult is GET /api/calibration/result's 404: this phone
	// has never completed a calibration. Per HANDOFF-controller-ux.md this is
	// a normal "nothing to ingest yet", not a failure.
	ErrNoCalibrationResult = errors.New("phone has no completed calibration result")
)

// ---- Rich zoom-probe result (mirrors phone-app calibration/CalibrationModels.kt) ----

type RectNorm struct {
	L float64 `json:"l"`
	T float64 `json:"t"`
	R float64 `json:"r"`
	B float64 `json:"b"`
}

type FloatRange2 struct {
	Lo float64 `json:"lo"`
	Hi float64 `json:"hi"`
}

// ZoomSample: one requested zoom and what the HAL actually did with it.
type ZoomSample struct {
	RequestedRatio         float64   `json:"requestedRatio"`
	ReportedRatio          *float64  `json:"reportedRatio"`
	RatioHonored           bool      `json:"ratioHonored"`
	RequestedCropNorm      RectNorm  `json:"requestedCropNorm"`
	EffectiveCropNorm      RectNorm  `json:"effectiveCropNorm"`
	PositionRequestedNorm  *RectNorm `json:"positionRequestedNorm"`
	PositionReportedNorm   *RectNorm `json:"positionReportedNorm"`
	PositionMetadataMatch  *bool     `json:"positionMetadataMatch"`
	PositionFrameShifted   *bool     `json:"positionFrameShifted"`
	PositionHonored        *bool     `json:"positionHonored"`
	ActivePhysicalID       *string   `json:"activePhysicalId"`
	LensFocalLengthMm      *float64  `json:"lensFocalLengthMm"`
	Sharpness              float64   `json:"sharpness"`
	SharpnessRelToBaseline float64   `json:"sharpnessRelToBaseline"`
}

type ResolutionZoomMap struct {
	Width   int          `json:"width"`
	Height  int          `json:"height"`
	Samples []ZoomSample `json:"samples"`
}

type CameraDeviceIdentity struct {
	CameraID          string       `json:"cameraId"`
	Facing            string       `json:"facing"`
	FocalLengthsMm    []float64    `json:"focalLengthsMm"`
	IsLogicalMultiCam bool         `json:"isLogicalMultiCam"`
	PhysicalIDs       []string     `json:"physicalIds"`
	ActiveArrayWidth  int          `json:"activeArrayWidth"`
	ActiveArrayHeight int          `json:"activeArrayHeight"`
	CroppingType      string       `json:"croppingType"`
	MaxDigitalZoom    *float64     `json:"maxDigitalZoom"`
	ZoomRatioRange    *FloatRange2 `json:"zoomRatioRange"`
}

// CalibrationStepResult keeps the "N/M checks" summary the controller shows.
type CalibrationStepResult struct {
	ChecksTotal  int `json:"checksTotal"`
	ChecksPassed int `json:"checksPassed"`
}

type CalibrationCameraResult struct {
	DeviceIdentity      CameraDeviceIdentity            `json:"deviceIdentity"`
	OpticalRange        FloatRange2                     `json:"opticalRange"`
	DigitalRange        FloatRange2                     `json:"digitalRange"`
	CrossoverRatio      *float64                        `json:"crossoverRatio"`
	CrossoverMethod     string                          `json:"crossoverMethod"`
	PositionHonored          bool                       `json:"positionHonored"`
	PositionFailRatios       []float64                  `json:"positionFailRatios"`
	PositionMetadataLiedRatios []float64                `json:"positionMetadataLiedRatios"`
	QualityCollapseRatio     *float64                   `json:"qualityCollapseRatio"`
	PerResolution       map[string]ResolutionZoomMap    `json:"perResolution"`
	Steps               map[string]CalibrationStepResult `json:"steps"`
}

type ResultDeviceIdentity struct {
	Manufacturer   string `json:"manufacturer"`
	Model          string `json:"model"`
	Device         string `json:"device"`
	AppVersionName string `json:"appVersionName"`
}

// CalibrationResult mirrors GET /api/calibration/result.
type CalibrationResult struct {
	RunID          string                            `json:"runId"`
	RunAtMs        int64                             `json:"runAtMs"`
	DeviceIdentity ResultDeviceIdentity              `json:"deviceIdentity"`
	Cameras        map[string]CalibrationCameraResult `json:"cameras"`
}

// ChecksTotal / ChecksPassed sum the per-step counts across every camera —
// the "14/14 checks completed" figure Phone detail shows.
func (r CalibrationResult) ChecksTotal() int {
	n := 0
	for _, cam := range r.Cameras {
		for _, step := range cam.Steps {
			n += step.ChecksTotal
		}
	}
	return n
}

func (r CalibrationResult) ChecksPassed() int {
	n := 0
	for _, cam := range r.Cameras {
		for _, step := range cam.Steps {
			n += step.ChecksPassed
		}
	}
	return n
}

// ParseResultSummary decodes a stored result JSON blob into the summary
// struct (for the check counts). Used by the controller when reading a
// calibration entry back out of its own DB.
func ParseResultSummary(rawJSON string) (CalibrationResult, error) {
	var out CalibrationResult
	err := json.Unmarshal([]byte(rawJSON), &out)
	return out, err
}

// CalibrationResultRaw fetches GET /api/calibration/result (the phone's last
// persisted result — no runId). It returns both the decoded summary and the
// verbatim JSON body, so the controller can store the phone's result exactly
// as sent (unknown fields preserved) while still reading the check counts.
func (c *Client) CalibrationResultRaw(ctx context.Context) (CalibrationResult, []byte, error) {
	ctx, cancel := context.WithTimeout(ctx, metadataTimeout)
	defer cancel()

	resp, err := c.request(ctx, http.MethodGet, "/api/calibration/result", nil, nil)
	if err != nil {
		return CalibrationResult{}, nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized {
		return CalibrationResult{}, nil, ErrUnauthorized
	}
	if resp.StatusCode == http.StatusNotFound {
		return CalibrationResult{}, nil, ErrNoCalibrationResult
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return CalibrationResult{}, nil, &HTTPError{StatusCode: resp.StatusCode, Body: string(b)}
	}

	raw, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20)) // 8 MiB ceiling — a full per-resolution zoom map
	if err != nil {
		return CalibrationResult{}, nil, fmt.Errorf("read calibration result: %w", err)
	}
	var out CalibrationResult
	if err := json.Unmarshal(raw, &out); err != nil {
		return CalibrationResult{}, nil, fmt.Errorf("decode calibration result: %w", err)
	}
	return out, raw, nil
}

// CalibrationStartResponse mirrors POST /api/calibration/start.
type CalibrationStartResponse struct {
	RunID       string   `json:"runId"`
	Status      string   `json:"status"`
	StartedAtMs int64    `json:"startedAtMs"`
	CameraIDs   []string `json:"cameraIds"`
}

// StartCalibration kicks off a device-wide sweep. A 409 (already running)
// comes back as ErrCalibrationRunning.
func (c *Client) StartCalibration(ctx context.Context) (CalibrationStartResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, metadataTimeout)
	defer cancel()

	var out CalibrationStartResponse
	err := c.doJSON(ctx, http.MethodPost, "/api/calibration/start", nil, struct{}{}, &out)
	if err != nil {
		var httpErr *HTTPError
		if errors.As(err, &httpErr) && httpErr.StatusCode == http.StatusConflict {
			if strings.Contains(strings.ToLower(httpErr.Body), "recording") {
				return CalibrationStartResponse{}, ErrPhoneRecording
			}
			return CalibrationStartResponse{}, ErrCalibrationRunning
		}
		return CalibrationStartResponse{}, err
	}
	return out, nil
}

// CalibrationStepProgress is the "check N of M" counter within the current step.
type CalibrationStepProgress struct {
	Index int `json:"index"`
	Total int `json:"total"`
}

// CalibrationProgress mirrors GET /api/calibration/status.
type CalibrationProgress struct {
	RunID              string                  `json:"runId"`
	Status             string                  `json:"status"`
	CurrentCameraID    string                  `json:"currentCameraId"`
	CamerasCompleted   int                     `json:"camerasCompleted"`
	CamerasTotal       int                     `json:"camerasTotal"`
	CurrentStep        string                  `json:"currentStep"`
	StepsCompleted     int                     `json:"stepsCompleted"`
	StepsTotal         int                     `json:"stepsTotal"`
	ProgressWithinStep CalibrationStepProgress `json:"progressWithinStep"`
	StartedAtMs        int64                   `json:"startedAtMs"`
}

// CalibrationStatusCall polls GET /api/calibration/status?runId=<runID>.
func (c *Client) CalibrationStatusCall(ctx context.Context, runID string) (CalibrationProgress, error) {
	ctx, cancel := context.WithTimeout(ctx, metadataTimeout)
	defer cancel()

	q := url.Values{}
	if runID != "" {
		q.Set("runId", runID)
	}
	var out CalibrationProgress
	err := c.doJSON(ctx, http.MethodGet, "/api/calibration/status", q, nil, &out)
	return out, err
}

// CancelCalibration issues DELETE /api/calibration/:runID (cooperative stop).
func (c *Client) CancelCalibration(ctx context.Context, runID string) error {
	ctx, cancel := context.WithTimeout(ctx, metadataTimeout)
	defer cancel()
	return c.doJSON(ctx, http.MethodDelete, "/api/calibration/"+url.PathEscape(runID), nil, nil, nil)
}

// SetMode issues POST /api/mode. The controller doesn't drive this from the UI
// (recording is stopped on the phone itself), but it's the low-level move that
// frees the camera - used by tests and available for future flows.
func (c *Client) SetMode(ctx context.Context, mode string) error {
	ctx, cancel := context.WithTimeout(ctx, metadataTimeout)
	defer cancel()
	return c.doJSON(ctx, http.MethodPost, "/api/mode", nil, map[string]string{"mode": mode}, nil)
}
