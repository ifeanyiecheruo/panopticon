-- name: ListCameraCalibrations :many
SELECT manufacturer_model, camera_id, source_phone_id, calibrated_at_ms, camera_json
FROM camera_calibration WHERE manufacturer_model = ?
ORDER BY camera_id;

-- name: UpsertCameraCalibration :exec
INSERT INTO camera_calibration (manufacturer_model, camera_id, source_phone_id, calibrated_at_ms, camera_json)
VALUES (?, ?, ?, ?, ?)
ON CONFLICT(manufacturer_model, camera_id) DO UPDATE SET
    source_phone_id  = excluded.source_phone_id,
    calibrated_at_ms = excluded.calibrated_at_ms,
    camera_json      = excluded.camera_json;

-- name: DeleteCameraCalibrationsFromPhone :exec
DELETE FROM camera_calibration WHERE source_phone_id = ?;
