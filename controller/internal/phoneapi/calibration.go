package phoneapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
)

// Sentinels specific to the calibration routes.
var (
	// ErrCalibrationRunning is POST /api/calibration/start's 409: a sweep is
	// already in progress.
	ErrCalibrationRunning = errors.New("calibration already running")

	// ErrNoCalibrationResult is GET /api/calibration/result's 404: this phone
	// has never completed a calibration. Per HANDOFF-controller-ux.md this is
	// a normal "nothing to ingest yet", not a failure.
	ErrNoCalibrationResult = errors.New("phone has no completed calibration result")
)

// CalibrationStepResult is the controller-relevant slice of one step's result
// — it only ever summarises "N/M checks", so the individual check list from
// phone-http-api.md is intentionally not decoded here.
type CalibrationStepResult struct {
	ChecksTotal  int `json:"checksTotal"`
	ChecksPassed int `json:"checksPassed"`
}

type CalibrationCameraResult struct {
	DeviceIdentity map[string]any                   `json:"deviceIdentity"`
	Steps          map[string]CalibrationStepResult `json:"steps"`
}

// CalibrationResult mirrors GET /api/calibration/result.
type CalibrationResult struct {
	RunID   string                             `json:"runId"`
	RunAtMs int64                              `json:"runAtMs"`
	Cameras map[string]CalibrationCameraResult `json:"cameras"`
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

	raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20)) // 1 MiB ceiling — a result doc is a few KB
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
