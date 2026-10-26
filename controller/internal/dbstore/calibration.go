package dbstore

import (
	"context"

	"panopticon-controller/internal/dbstore/queries"
)

// CameraCalibration is one camera's entry in the controller's shared-by-manufacturer+model
// calibration, per docs/design/decisions/0009-calibration-model.md: each camera of a model is
// stored on its own, from whichever phone of that model most recently finished calibrating it,
// and reused for every other phone of the same model. CameraJSON is the phone's
// GET /api/calibration/result cameras.<id> object stored verbatim.
type CameraCalibration struct {
	ManufacturerModel string
	CameraID          string
	SourcePhoneID     string
	CalibratedAtMs    int64
	CameraJSON        string
}

// ListCameraCalibrations returns every calibrated camera of a "manufacturer|model" key, by
// camera id. Empty if this model has never been calibrated.
func (s *Store) ListCameraCalibrations(manufacturerModel string) ([]CameraCalibration, error) {
	rows, err := s.q.ListCameraCalibrations(context.Background(), manufacturerModel)
	if err != nil {
		return nil, err
	}
	out := make([]CameraCalibration, 0, len(rows))
	for _, row := range rows {
		out = append(out, CameraCalibration{
			ManufacturerModel: row.ManufacturerModel,
			CameraID:          row.CameraID,
			SourcePhoneID:     row.SourcePhoneID,
			CalibratedAtMs:    row.CalibratedAtMs,
			CameraJSON:        row.CameraJson,
		})
	}
	return out, nil
}

// UpsertCameraCalibration writes (replacing any existing entry) one camera's calibration. It
// is unconditional; callers decide freshness themselves (see internal/calibration).
func (s *Store) UpsertCameraCalibration(c CameraCalibration) error {
	return s.q.UpsertCameraCalibration(context.Background(), queries.UpsertCameraCalibrationParams{
		ManufacturerModel: c.ManufacturerModel,
		CameraID:          c.CameraID,
		SourcePhoneID:     c.SourcePhoneID,
		CalibratedAtMs:    c.CalibratedAtMs,
		CameraJson:        c.CameraJSON,
	})
}

// DeleteCameraCalibrationsFromPhone drops every camera calibration that phone supplied - what
// erasing a phone's calibration removes here. Cameras other phones of the model supplied stay.
func (s *Store) DeleteCameraCalibrationsFromPhone(phoneID string) error {
	return s.q.DeleteCameraCalibrationsFromPhone(context.Background(), phoneID)
}
