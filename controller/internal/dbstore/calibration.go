package dbstore

import (
	"context"
	"database/sql"
	"errors"

	"panopticon-controller/internal/dbstore/queries"
)

// Calibration is the controller's shared-by-manufacturer+model calibration
// record, per HANDOFF-controller-ux.md's "Calibration data model": one entry
// per device model, populated by whichever phone of that model calibrates
// first (or by a manual re-run), reused for every other phone of the same
// model. ResultJSON is the phone's GET /api/calibration/result body stored
// verbatim.
type Calibration struct {
	ManufacturerModel string
	SourcePhoneID     string
	CalibratedAtMs    int64
	ResultJSON        string
}

// GetCalibration looks up the calibration entry for a "manufacturer|model"
// key. Returns ErrNotFound if this model has never been calibrated.
func (s *Store) GetCalibration(manufacturerModel string) (Calibration, error) {
	row, err := s.q.GetCalibration(context.Background(), manufacturerModel)
	if errors.Is(err, sql.ErrNoRows) {
		return Calibration{}, ErrNotFound
	}
	if err != nil {
		return Calibration{}, err
	}
	return Calibration{
		ManufacturerModel: row.ManufacturerModel,
		SourcePhoneID:     row.SourcePhoneID,
		CalibratedAtMs:    row.CalibratedAtMs,
		ResultJSON:        row.ResultJson,
	}, nil
}

// UpsertCalibration writes (replacing any existing entry) the calibration
// record for a model. Used by a manual re-run, which per the data model is
// unconditional last-write-wins with no merge. The opportunistic
// ingest-on-pair path guards on freshness itself (see internal/calibration).
func (s *Store) UpsertCalibration(c Calibration) error {
	return s.q.UpsertCalibration(context.Background(), queries.UpsertCalibrationParams{
		ManufacturerModel: c.ManufacturerModel,
		SourcePhoneID:     c.SourcePhoneID,
		CalibratedAtMs:    c.CalibratedAtMs,
		ResultJson:        c.ResultJSON,
	})
}
