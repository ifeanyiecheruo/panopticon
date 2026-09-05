-- name: GetCalibration :one
SELECT manufacturer_model, source_phone_id, calibrated_at_ms, result_json
FROM calibration WHERE manufacturer_model = ?;

-- name: UpsertCalibration :exec
INSERT INTO calibration (manufacturer_model, source_phone_id, calibrated_at_ms, result_json)
VALUES (?, ?, ?, ?)
ON CONFLICT(manufacturer_model) DO UPDATE SET
    source_phone_id  = excluded.source_phone_id,
    calibrated_at_ms = excluded.calibrated_at_ms,
    result_json      = excluded.result_json;
