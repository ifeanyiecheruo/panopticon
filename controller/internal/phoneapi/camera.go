package phoneapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
)

// Camera-selection + manual-control routes (phone-http-api.md "Cameras" and
// "Camera control"). Mirrors phone-app's camera/CameraModels.kt.

// ErrUnknownCamera is POST /api/cameras/active's 404: the phone doesn't have a
// camera with that id.
var ErrUnknownCamera = errors.New("unknown camera id")

// InvalidControlKeyError is POST /api/camera/state's 400: a requested control
// key was out of range or unsupported on the active camera. Key names the
// offending field so the UI can point at it.
type InvalidControlKeyError struct {
	Key    string
	Reason string
}

func (e *InvalidControlKeyError) Error() string {
	if e.Key == "" {
		return "invalid camera control: " + e.Reason
	}
	return "invalid camera control key " + e.Key + ": " + e.Reason
}

// ---- wire types ----

type IntRange2 struct {
	Lo int `json:"lo"`
	Hi int `json:"hi"`
}

type LongRange2 struct {
	Lo int64 `json:"lo"`
	Hi int64 `json:"hi"`
}

type CameraInfo struct {
	CameraID      string   `json:"cameraId"`
	Facing        string   `json:"facing"` // back | front | external | unknown
	Label         string   `json:"label"`
	FocalLengthMm *float64 `json:"focalLengthMm"`
	IsActive      bool     `json:"isActive"`
}

type CamerasResponse struct {
	Cameras []CameraInfo `json:"cameras"`
}

// CameraCapabilities are one camera's declared per-key ranges (GET
// /api/camera/capabilities). The controller's adjuster UI bounds its sliders
// to these.
type CameraCapabilities struct {
	CameraID                  string      `json:"cameraId"`
	ZoomRatioRange            FloatRange2 `json:"zoomRatioRange"`
	ZoomViaRatioAPI           bool        `json:"zoomViaRatioApi"`
	AECompensationRange       IntRange2   `json:"aeCompensationRange"`
	AECompensationStepMilliEv int         `json:"aeCompensationStepMilliEv"`
	ExposureTimeRangeNs       *LongRange2 `json:"exposureTimeRangeNs"`
	SensitivityRange          *IntRange2  `json:"sensitivityRange"`
	MinFocusDistanceDiopters  float64     `json:"minFocusDistanceDiopters"`
	HasManualSensor           bool        `json:"hasManualSensor"`
	HasManualFocus            bool        `json:"hasManualFocus"`
	HasManualWhiteBalance     bool        `json:"hasManualWhiteBalance"`
	WBGainRange               FloatRange2 `json:"wbGainRange"`
	AWBModes                  []int       `json:"awbModes"`
	VideoStabilizationModes   []int       `json:"videoStabilizationModes"`
	OpticalStabilizationModes []int       `json:"opticalStabilizationModes"`
	MaxAERegions              int         `json:"maxAeRegions"`
	MaxAFRegions              int         `json:"maxAfRegions"`
	OutputResolutions         []string    `json:"outputResolutions"`
	PhysicalCameraIDs         []string    `json:"physicalCameraIds"`
	CroppingType              string      `json:"croppingType"`
	ActiveArrayWidth          int         `json:"activeArrayWidth"`
	ActiveArrayHeight         int         `json:"activeArrayHeight"`
}

// CameraControlKeys is the concrete manual-control key set. Every field is a
// pointer: nil = "leave this control on auto". zoomRatio and cropRegionNorm
// are mutually exclusive at apply time (cropRegionNorm wins).
type CameraControlKeys struct {
	ZoomRatio                 *float64  `json:"zoomRatio"`
	CropRegionNorm            *RectNorm `json:"cropRegionNorm"`
	AEExposureCompensation    *int      `json:"aeExposureCompensation"`
	AELock                    *bool     `json:"aeLock"`
	AERegionNorm              *RectNorm `json:"aeRegionNorm"`
	ManualExposure            *bool     `json:"manualExposure"`
	SensorExposureTimeNs      *int64    `json:"sensorExposureTimeNs"`
	SensorSensitivityISO      *int      `json:"sensorSensitivityIso"`
	ManualFocus               *bool     `json:"manualFocus"`
	LensFocusDistanceDiopters *float64  `json:"lensFocusDistanceDiopters"`
	AFRegionNorm              *RectNorm `json:"afRegionNorm"`
	AWBMode                   *int      `json:"awbMode"`
	ManualWhiteBalance        *bool     `json:"manualWhiteBalance"`
	WBRedGain                 *float64  `json:"wbRedGain"`
	WBGreenGain               *float64  `json:"wbGreenGain"`
	WBBlueGain                *float64  `json:"wbBlueGain"`
	VideoStabilizationMode    *int      `json:"videoStabilizationMode"`
	OpticalStabilizationMode  *int      `json:"opticalStabilizationMode"`
}

type CameraStateResponse struct {
	CameraID             string            `json:"cameraId"`
	RotationDegrees      int               `json:"rotationDegrees"`
	VideoResolution      string            `json:"videoResolution"`
	ManualControlEnabled bool              `json:"manualControlEnabled"`
	Keys                 CameraControlKeys `json:"keys"`
}

// CameraStatePatch is POST /api/camera/state's body. Keys, when non-nil,
// replaces the applied set wholesale (send the full desired set). Any subset of
// the fields may be sent; omitted fields are left unchanged on the phone.
type CameraStatePatch struct {
	ManualControlEnabled *bool `json:"manualControlEnabled,omitempty"`
	// RotationDegrees is the preview/record frame rotation (0/90/180/270).
	RotationDegrees *int `json:"rotationDegrees,omitempty"`
	// VideoResolution is the record/broadcast size "<w>x<h>" (one of the
	// camera's CameraCapabilities.OutputResolutions).
	VideoResolution *string            `json:"videoResolution,omitempty"`
	Keys            *CameraControlKeys `json:"keys,omitempty"`
}

type activeCameraRequest struct {
	CameraID string `json:"cameraId"`
}

type activeCameraResponse struct {
	ActiveCameraID string `json:"activeCameraId"`
}

// ---- calls ----

// Cameras lists the phone's physical cameras and which is active.
func (c *Client) Cameras(ctx context.Context) (CamerasResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, metadataTimeout)
	defer cancel()
	var out CamerasResponse
	err := c.doJSON(ctx, http.MethodGet, "/api/cameras", nil, nil, &out)
	return out, err
}

// SetActiveCamera switches the active camera (a disruptive reconfigure on the
// phone). A 404 comes back as ErrUnknownCamera.
func (c *Client) SetActiveCamera(ctx context.Context, cameraID string) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, metadataTimeout)
	defer cancel()
	var out activeCameraResponse
	err := c.doJSON(ctx, http.MethodPost, "/api/cameras/active", nil, activeCameraRequest{CameraID: cameraID}, &out)
	if err != nil {
		var httpErr *HTTPError
		if errors.As(err, &httpErr) && httpErr.StatusCode == http.StatusNotFound {
			return "", ErrUnknownCamera
		}
		return "", err
	}
	return out.ActiveCameraID, nil
}

// CameraCapabilities reads the declared control ranges for one camera (or the
// active camera when cameraID is ""). A pure characteristics read on the phone
// - works in any mode.
func (c *Client) CameraCapabilities(ctx context.Context, cameraID string) (CameraCapabilities, error) {
	ctx, cancel := context.WithTimeout(ctx, metadataTimeout)
	defer cancel()
	var q url.Values
	if cameraID != "" {
		q = url.Values{"cameraId": {cameraID}}
	}
	var out CameraCapabilities
	err := c.doJSON(ctx, http.MethodGet, "/api/camera/capabilities", q, nil, &out)
	if err != nil {
		var httpErr *HTTPError
		if errors.As(err, &httpErr) && httpErr.StatusCode == http.StatusNotFound {
			return CameraCapabilities{}, ErrUnknownCamera
		}
		return CameraCapabilities{}, err
	}
	return out, nil
}

// CameraState reads the phone's current manual-control state.
func (c *Client) CameraState(ctx context.Context) (CameraStateResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, metadataTimeout)
	defer cancel()
	var out CameraStateResponse
	err := c.doJSON(ctx, http.MethodGet, "/api/camera/state", nil, nil, &out)
	return out, err
}

// SetCameraState validates-then-applies a new manual-control state. A 400
// (bad key) comes back as *InvalidControlKeyError carrying the key name.
func (c *Client) SetCameraState(ctx context.Context, patch CameraStatePatch) (CameraStateResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, metadataTimeout)
	defer cancel()

	resp, err := c.request(ctx, http.MethodPost, "/api/camera/state", nil, patch)
	if err != nil {
		return CameraStateResponse{}, err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized {
		return CameraStateResponse{}, ErrUnauthorized
	}
	if resp.StatusCode == http.StatusBadRequest {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		var ce struct {
			Error string `json:"error"`
			Key   string `json:"key"`
		}
		_ = json.Unmarshal(b, &ce)
		return CameraStateResponse{}, &InvalidControlKeyError{Key: ce.Key, Reason: ce.Error}
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return CameraStateResponse{}, &HTTPError{StatusCode: resp.StatusCode, Body: string(b)}
	}

	var out CameraStateResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return CameraStateResponse{}, err
	}
	return out, nil
}
