-- +goose Up

-- Calibration is stored per camera, not per model: a camera whose sweep finished is stored even
-- while the phone's other cameras are still unfinished, and phones of the same model can each
-- contribute the cameras they calibrated most recently. camera_json is the phone's
-- cameras.<id> object from GET /api/calibration/result, verbatim.
CREATE TABLE camera_calibration (
    manufacturer_model TEXT NOT NULL,
    camera_id TEXT NOT NULL,
    source_phone_id TEXT NOT NULL,
    calibrated_at_ms INTEGER NOT NULL,
    camera_json TEXT NOT NULL,
    PRIMARY KEY (manufacturer_model, camera_id)
);

-- Each stored whole-phone result becomes one row per camera it holds.
INSERT INTO camera_calibration (manufacturer_model, camera_id, source_phone_id, calibrated_at_ms, camera_json)
SELECT c.manufacturer_model, j.key, c.source_phone_id, c.calibrated_at_ms, j.value
FROM calibration c, json_each(c.result_json, '$.cameras') j
WHERE json_valid(c.result_json);

DROP TABLE calibration;

-- +goose Down

CREATE TABLE calibration (
    manufacturer_model TEXT PRIMARY KEY,
    source_phone_id TEXT NOT NULL,
    calibrated_at_ms INTEGER NOT NULL,
    result_json TEXT NOT NULL
);

-- One whole-model result again, credited to the phone that calibrated its newest camera.
INSERT INTO calibration (manufacturer_model, source_phone_id, calibrated_at_ms, result_json)
SELECT cc.manufacturer_model,
       (SELECT newest.source_phone_id FROM camera_calibration newest
        WHERE newest.manufacturer_model = cc.manufacturer_model
        ORDER BY newest.calibrated_at_ms DESC LIMIT 1),
       MAX(cc.calibrated_at_ms),
       json_object('runId', '', 'runAtMs', MAX(cc.calibrated_at_ms),
                   'cameras', json_group_object(cc.camera_id, json(cc.camera_json)))
FROM camera_calibration cc
GROUP BY cc.manufacturer_model;

DROP TABLE camera_calibration;
