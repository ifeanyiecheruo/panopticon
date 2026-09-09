// Package calibration implements the controller side of the calibration
// data model from docs/design/decisions/0009-calibration-model.md: a shared resource keyed by
// manufacturer+model (not per-phone), populated opportunistically by pulling
// GET /api/calibration/result from a paired phone and reused for every other
// phone of the same model.
package calibration

import (
	"context"
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

// Lookup returns the stored calibration view for a phone's model, or a
// Present:false view (the "Calibration needed" state) if this model has no
// entry yet.
func Lookup(store *dbstore.Store, phone dbstore.Phone) (View, error) {
	key := ModelKey(phone.Manufacturer, phone.Model)
	v := View{ModelKey: key}
	if key == "" {
		return v, nil
	}
	entry, err := store.GetCalibration(key)
	if errors.Is(err, dbstore.ErrNotFound) {
		return v, nil
	}
	if err != nil {
		return v, err
	}

	summary, perr := phoneapi.ParseResultSummary(entry.ResultJSON)
	v.Present = true
	v.ChecksTotal = summary.ChecksTotal()
	v.ChecksPassed = summary.ChecksPassed()
	v.CalibratedAtMs = entry.CalibratedAtMs
	v.SourcePhoneID = entry.SourcePhoneID
	v.ViaOtherPhone = entry.SourcePhoneID != "" && entry.SourcePhoneID != phone.ID
	if v.ViaOtherPhone {
		if src, err := store.GetPhone(entry.SourcePhoneID); err == nil {
			v.SourcePhoneName = src.Name
		}
	}
	if perr != nil {
		// A stored blob we can't parse still counts as "present" (so we don't
		// re-prompt a sweep), we just can't show detail.
		return v, nil
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

// IngestOpportunistic pulls the phone's last persisted calibration result and
// stores it under the model key — but only if we don't already hold a
// newer-or-equal entry for that model. This is the "populated opportunistically
// ... right after pairing (and optionally re-checked when opening Phone
// detail)" path; it never clobbers fresher data. Returns (ingested, error);
// a phone with no result yet is (false, nil), not an error.
func IngestOpportunistic(ctx context.Context, store *dbstore.Store, phone dbstore.Phone) (bool, error) {
	key := ModelKey(phone.Manufacturer, phone.Model)
	if key == "" {
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

	existing, err := store.GetCalibration(key)
	if err == nil && existing.CalibratedAtMs >= result.RunAtMs {
		return false, nil // we already hold something at least as fresh
	}
	if err != nil && !errors.Is(err, dbstore.ErrNotFound) {
		return false, err
	}

	if err := store.UpsertCalibration(dbstore.Calibration{
		ManufacturerModel: key,
		SourcePhoneID:     phone.ID,
		CalibratedAtMs:    result.RunAtMs,
		ResultJSON:        string(raw),
	}); err != nil {
		return false, fmt.Errorf("store calibration: %w", err)
	}
	return true, nil
}

// StoreResult writes a result under the model key unconditionally
// (last-write-wins) — used by a manual re-run, which per the data model
// always overwrites regardless of cache state.
func StoreResult(store *dbstore.Store, phone dbstore.Phone, result phoneapi.CalibrationResult, raw []byte) error {
	key := ModelKey(phone.Manufacturer, phone.Model)
	if key == "" {
		return errors.New("phone has no manufacturer/model to key calibration by")
	}
	return store.UpsertCalibration(dbstore.Calibration{
		ManufacturerModel: key,
		SourcePhoneID:     phone.ID,
		CalibratedAtMs:    result.RunAtMs,
		ResultJSON:        string(raw),
	})
}
