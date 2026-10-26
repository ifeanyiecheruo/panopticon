package dbstore

import (
	"context"
	"database/sql"
	"encoding/json"
	"path/filepath"
	"testing"
)

// A whole-phone result as migration 001's calibration table stored it: two cameras.
const wholePhoneResult = `{"runId":"cal-old","runAtMs":1000,"cameras":{` +
	`"0":{"deviceIdentity":{"cameraId":"0","facing":"back"},"steps":{"zoom-map":{"checksTotal":3,"checksPassed":3}}},` +
	`"1":{"deviceIdentity":{"cameraId":"1","facing":"front"},"steps":{"zoom-map":{"checksTotal":2,"checksPassed":1}}}}}`

// openAtVersion opens a fresh database migrated only up to version.
func openAtVersion(t *testing.T, version int64) (*sql.DB, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "panopticon.db")
	db, err := sql.Open("sqlite", "file:"+path+"?_pragma=foreign_keys(ON)")
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	t.Cleanup(func() { db.Close() })
	provider, err := createMigrationsProvider(db)
	if err != nil {
		t.Fatalf("provider: %v", err)
	}
	if _, err := provider.UpTo(context.Background(), version); err != nil {
		t.Fatalf("migrate to %d: %v", version, err)
	}
	return db, path
}

func TestMigration004_SplitsEachModelIntoCameras(t *testing.T) {
	db, path := openAtVersion(t, 3)
	if _, err := db.Exec(`INSERT INTO calibration VALUES ('Google|Pixel 6', 'ph_a', 1000, ?)`, wholePhoneResult); err != nil {
		t.Fatalf("seed: %v", err)
	}
	db.Close()

	store, err := Open(path)
	if err != nil {
		t.Fatalf("Open (migrates to latest): %v", err)
	}
	defer store.Close()

	rows, err := store.ListCameraCalibrations("Google|Pixel 6")
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(rows) != 2 || rows[0].CameraID != "0" || rows[1].CameraID != "1" {
		t.Fatalf("rows = %+v, want cameras 0 and 1", rows)
	}
	for _, row := range rows {
		if row.SourcePhoneID != "ph_a" || row.CalibratedAtMs != 1000 {
			t.Errorf("camera %s: source %q at %d, want ph_a at 1000", row.CameraID, row.SourcePhoneID, row.CalibratedAtMs)
		}
	}
	var front struct {
		DeviceIdentity struct{ Facing string } `json:"deviceIdentity"`
	}
	if err := json.Unmarshal([]byte(rows[1].CameraJSON), &front); err != nil || front.DeviceIdentity.Facing != "front" {
		t.Errorf("camera 1 json = %s (err %v), want the front camera's object", rows[1].CameraJSON, err)
	}
}

func TestMigration004_DownRebuildsOneResultPerModel(t *testing.T) {
	db, _ := openAtVersion(t, 4)
	for _, row := range []struct {
		camera, phone string
		at            int64
	}{{"0", "ph_a", 1000}, {"1", "ph_b", 2000}} {
		if _, err := db.Exec(`INSERT INTO camera_calibration VALUES ('Google|Pixel 6', ?, ?, ?, '{"steps":{}}')`, row.camera, row.phone, row.at); err != nil {
			t.Fatalf("seed: %v", err)
		}
	}
	provider, err := createMigrationsProvider(db)
	if err != nil {
		t.Fatalf("provider: %v", err)
	}
	if _, err := provider.DownTo(context.Background(), 3); err != nil {
		t.Fatalf("down to 3: %v", err)
	}

	var source, resultJSON string
	var at int64
	if err := db.QueryRow(`SELECT source_phone_id, calibrated_at_ms, result_json FROM calibration WHERE manufacturer_model = 'Google|Pixel 6'`).
		Scan(&source, &at, &resultJSON); err != nil {
		t.Fatalf("read rebuilt row: %v", err)
	}
	if source != "ph_b" || at != 2000 {
		t.Errorf("rebuilt row credited to %q at %d, want the newest camera's ph_b at 2000", source, at)
	}
	var result struct {
		Cameras map[string]json.RawMessage `json:"cameras"`
	}
	if err := json.Unmarshal([]byte(resultJSON), &result); err != nil || len(result.Cameras) != 2 {
		t.Errorf("result_json = %s (err %v), want both cameras", resultJSON, err)
	}
}

func TestDeleteCameraCalibrationsFromPhone_KeepsOtherPhonesCameras(t *testing.T) {
	store, err := Open(filepath.Join(t.TempDir(), "panopticon.db"))
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer store.Close()
	for _, c := range []CameraCalibration{
		{ManufacturerModel: "Google|Pixel 6", CameraID: "0", SourcePhoneID: "ph_a", CalibratedAtMs: 1, CameraJSON: "{}"},
		{ManufacturerModel: "Google|Pixel 6", CameraID: "1", SourcePhoneID: "ph_b", CalibratedAtMs: 1, CameraJSON: "{}"},
	} {
		if err := store.UpsertCameraCalibration(c); err != nil {
			t.Fatalf("upsert: %v", err)
		}
	}

	if err := store.DeleteCameraCalibrationsFromPhone("ph_a"); err != nil {
		t.Fatalf("delete: %v", err)
	}

	rows, err := store.ListCameraCalibrations("Google|Pixel 6")
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(rows) != 1 || rows[0].CameraID != "1" {
		t.Errorf("rows = %+v, want only ph_b's camera 1", rows)
	}
}
