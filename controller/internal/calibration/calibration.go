// Package calibration implements the controller side of the calibration
// data model from docs/design/decisions/0009-calibration-model.md: a shared resource keyed by
// manufacturer+model (not per-phone), populated opportunistically by pulling
// GET /api/calibration/result from a paired phone and reused for every other
// phone of the same model.
package calibration

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"

	"panopticon-controller/internal/dbstore"
	"panopticon-controller/internal/phoneapi"
)

// ModelKey builds the "manufacturer|model" key the calibration table is
// keyed by. The separator is "|" so a manufacturer or model that itself
// contains a space can't collide with another pair. Returns "" if both parts
// are empty (an un-identified phone — nothing to key on).
func ModelKey(manufacturer, model string) string {
	m := strings.TrimSpace(manufacturer)
	d := strings.TrimSpace(model)
	if m == "" && d == "" {
		return ""
	}
	return m + "|" + d
}

// CameraZoomSummary is the per-camera headline the Phone-detail Calibration
// section shows (the full per-resolution sample map stays in the DB blob and
// is read only by EffectiveRect).
type CameraZoomSummary struct {
	CameraID             string               `json:"cameraId"`
	Facing               string               `json:"facing"`
	OpticalRange         phoneapi.FloatRange2 `json:"opticalRange"`
	DigitalRange         phoneapi.FloatRange2 `json:"digitalRange"`
	CrossoverRatio       *float64             `json:"crossoverRatio"`
	PositionHonored      bool                 `json:"positionHonored"`
	PositionMetadataLied bool                 `json:"positionMetadataLied"`
	QualityCollapseRatio *float64             `json:"qualityCollapseRatio"`
	Resolutions          int                  `json:"resolutions"`
}

// View is the Phone-detail summary of a model's calibration state.
type View struct {
	ModelKey        string              `json:"modelKey"`
	Present         bool                `json:"present"`
	ChecksPassed    int                 `json:"checksPassed"`
	ChecksTotal     int                 `json:"checksTotal"`
	CalibratedAtMs  int64               `json:"calibratedAtMs"`
	SourcePhoneID   string              `json:"sourcePhoneId"`
	SourcePhoneName string              `json:"sourcePhoneName"`
	ViaOtherPhone   bool                `json:"viaOtherPhone"` // data came from a different phone of the same model
	Cameras         []CameraZoomSummary `json:"cameras"`
}

// Load assembles a model's stored cameras into one result, for the code that reads calibration
// data (EffectiveRect, Lookup). Returns dbstore.ErrNotFound if no camera of the model is stored.
// A camera row whose JSON can't be parsed is left out.
func Load(store *dbstore.Store, key string) (phoneapi.CalibrationResult, []dbstore.CameraCalibration, error) {
	rows, err := store.ListCameraCalibrations(key)
	if err != nil {
		return phoneapi.CalibrationResult{}, nil, err
	}
	if len(rows) == 0 {
		return phoneapi.CalibrationResult{}, nil, dbstore.ErrNotFound
	}
	result := phoneapi.CalibrationResult{Cameras: map[string]phoneapi.CalibrationCameraResult{}}
	for _, row := range rows {
		var cam phoneapi.CalibrationCameraResult
		if err := json.Unmarshal([]byte(row.CameraJSON), &cam); err == nil {
			result.Cameras[row.CameraID] = cam
		}
		result.RunAtMs = max(result.RunAtMs, row.CalibratedAtMs)
	}
	return result, rows, nil
}

// Lookup returns the stored calibration view for a phone's model, or a
// Present:false view (the "Calibration needed" state) if this model has no
// camera stored yet. The source fields describe the most recently calibrated
// camera; ViaOtherPhone means none of the model's cameras came from this phone.
func Lookup(store *dbstore.Store, phone dbstore.Phone) (View, error) {
	key := ModelKey(phone.Manufacturer, phone.Model)
	v := View{ModelKey: key}
	if key == "" {
		return v, nil
	}
	summary, rows, err := Load(store, key)
	if errors.Is(err, dbstore.ErrNotFound) {
		return v, nil
	}
	if err != nil {
		return v, err
	}

	// A stored camera we can't parse still counts as "present" (so we don't
	// re-prompt a sweep), we just can't show its detail.
	v.Present = true
	v.ChecksTotal = summary.ChecksTotal()
	v.ChecksPassed = summary.ChecksPassed()
	newest := rows[0]
	fromThisPhone := false
	for _, row := range rows {
		if row.CalibratedAtMs > newest.CalibratedAtMs {
			newest = row
		}
		fromThisPhone = fromThisPhone || row.SourcePhoneID == phone.ID
	}
	v.CalibratedAtMs = newest.CalibratedAtMs
	v.SourcePhoneID = newest.SourcePhoneID
	v.ViaOtherPhone = !fromThisPhone
	if v.ViaOtherPhone {
		if src, err := store.GetPhone(newest.SourcePhoneID); err == nil {
			v.SourcePhoneName = src.Name
		}
	}
	for id, cam := range summary.Cameras {
		v.Cameras = append(v.Cameras, CameraZoomSummary{
			CameraID:             id,
			Facing:               cam.DeviceIdentity.Facing,
			OpticalRange:         cam.OpticalRange,
			DigitalRange:         cam.DigitalRange,
			CrossoverRatio:       cam.CrossoverRatio,
			PositionHonored:      cam.PositionHonored,
			PositionMetadataLied: len(cam.PositionMetadataLiedRatios) > 0,
			QualityCollapseRatio: cam.QualityCollapseRatio,
			Resolutions:          len(cam.PerResolution),
		})
	}
	sort.Slice(v.Cameras, func(i, j int) bool { return v.Cameras[i].CameraID < v.Cameras[j].CameraID })
	return v, nil
}

// IngestOpportunistic pulls the phone's completed calibration and stores each
// camera under the model key — but only a camera we don't already hold a
// newer-or-equal entry for. This is the "populated opportunistically ...
// right after pairing (and optionally re-checked when opening Phone detail)"
// path; it never clobbers fresher data. Returns (ingested anything, error);
// a phone with no result yet is (false, nil), not an error.
func IngestOpportunistic(ctx context.Context, store *dbstore.Store, phone dbstore.Phone) (bool, error) {
	if ModelKey(phone.Manufacturer, phone.Model) == "" {
		return false, nil
	}
	client := phoneapi.New(phone.BaseURL, phone.Token)
	result, raw, err := client.CalibrationResultRaw(ctx)
	if err != nil {
		if errors.Is(err, phoneapi.ErrNoCalibrationResult) {
			return false, nil
		}
		return false, err
	}
	n, err := storeCameras(store, phone, result, raw, "")
	return n > 0, err
}

// StoreRun stores the phone's completed calibration after a sweep the user ran
// (runID). Cameras that run finished overwrite unconditionally — a manual
// re-run always wins, per the data model. Cameras it didn't finish are still
// in the phone's completed result from earlier runs; those follow the
// freshness rule, like an opportunistic ingest. Returns how many cameras it
// stored.
func StoreRun(store *dbstore.Store, phone dbstore.Phone, result phoneapi.CalibrationResult, raw []byte, runID string) (int, error) {
	if ModelKey(phone.Manufacturer, phone.Model) == "" {
		return 0, errors.New("phone has no manufacturer/model to key calibration by")
	}
	return storeCameras(store, phone, result, raw, runID)
}

// storeCameras writes each camera of a phone's completed result as its own
// row: unconditionally if forceRunID finished it, otherwise only if it is
// fresher than the row already held. A partial result (an in-progress sweep)
// is never stored.
func storeCameras(store *dbstore.Store, phone dbstore.Phone, result phoneapi.CalibrationResult, raw []byte, forceRunID string) (int, error) {
	if !result.IsComplete() {
		return 0, nil
	}
	key := ModelKey(phone.Manufacturer, phone.Model)
	var verbatim struct {
		Cameras map[string]json.RawMessage `json:"cameras"`
	}
	if err := json.Unmarshal(raw, &verbatim); err != nil {
		return 0, fmt.Errorf("decode calibration cameras: %w", err)
	}
	existing, err := store.ListCameraCalibrations(key)
	if err != nil {
		return 0, err
	}
	heldAt := map[string]int64{}
	for _, row := range existing {
		heldAt[row.CameraID] = row.CalibratedAtMs
	}

	stored := 0
	for id, cam := range result.Cameras {
		runID, runAtMs := result.CameraRun(cam)
		held, ok := heldAt[id]
		if !(forceRunID != "" && runID == forceRunID) && ok && held >= runAtMs {
			continue // we already hold this camera at least as fresh
		}
		if err := store.UpsertCameraCalibration(dbstore.CameraCalibration{
			ManufacturerModel: key,
			CameraID:          id,
			SourcePhoneID:     phone.ID,
			CalibratedAtMs:    runAtMs,
			CameraJSON:        string(verbatim.Cameras[id]),
		}); err != nil {
			return stored, fmt.Errorf("store calibration for camera %s: %w", id, err)
		}
		stored++
	}
	return stored, nil
}
