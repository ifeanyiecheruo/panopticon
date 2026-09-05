package integrationtest

import (
	"context"
	"errors"
	"strings"
	"testing"

	"panopticon-controller/internal/calibration"
	"panopticon-controller/internal/dbstore"
	"panopticon-controller/internal/pairing"
	"panopticon-controller/internal/phoneapi"
)

// A minimal but well-formed GET /api/calibration/result body: one camera, two
// steps, 6 + 15 checks, 20 of 21 passing.
const sampleCalibrationResult = `{
  "runId": "cal-abc123",
  "runAtMs": 1755270015231,
  "cameras": {
    "0": {
      "deviceIdentity": {"cameraId": "0", "facing": "back", "focalLengthMm": 5.4},
      "steps": {
        "crop-region":  {"checksTotal": 6,  "checksPassed": 6},
        "zoom-quality": {"checksTotal": 15, "checksPassed": 14}
      }
    }
  }
}`

// TestAddPhone_IngestsCalibrationOnPair: a phone that has already calibrated
// gets its result pulled into the controller's model-keyed store the moment
// it's paired, with no sweep triggered.
func TestAddPhone_IngestsCalibrationOnPair(t *testing.T) {
	srv, fp := newFakePhoneServer(t, "GOOD-CODE")
	fp.calibrationResult = sampleCalibrationResult
	store, _ := newTestStore(t)

	address := strings.TrimPrefix(srv.URL, "http://")
	result, err := pairing.AddPhone(context.Background(), store, address, "GOOD-CODE")
	if err != nil {
		t.Fatalf("AddPhone failed: %v", err)
	}

	entry, err := store.GetCalibration(calibration.ModelKey("Google", "Pixel 6"))
	if err != nil {
		t.Fatalf("expected a calibration entry for Google|Pixel 6, got err: %v", err)
	}
	if entry.SourcePhoneID != result.PhoneID {
		t.Errorf("source phone id = %q, want %q", entry.SourcePhoneID, result.PhoneID)
	}
	if entry.CalibratedAtMs != 1755270015231 {
		t.Errorf("calibratedAtMs = %d, want 1755270015231", entry.CalibratedAtMs)
	}

	view, err := calibration.Lookup(store, mustGetPhone(t, store, result.PhoneID))
	if err != nil {
		t.Fatalf("Lookup: %v", err)
	}
	if !view.Present || view.ChecksTotal != 21 || view.ChecksPassed != 20 {
		t.Errorf("view = %+v, want present with 20/21 checks", view)
	}
	if view.ViaOtherPhone {
		t.Errorf("ViaOtherPhone should be false for the phone that sourced the data")
	}
}

// TestCalibration_ReusedAcrossPhonesOfSameModel: the first phone of a model to
// calibrate covers every other phone of that model. A second, never-calibrated
// phone of the same model shows as calibrated "via" the first.
func TestCalibration_ReusedAcrossPhonesOfSameModel(t *testing.T) {
	store, _ := newTestStore(t)

	srvA, fpA := newFakePhoneServer(t, "CODE-A")
	fpA.calibrationResult = sampleCalibrationResult
	fpA.phoneName = "Porch Cam"
	resA, err := pairing.AddPhone(context.Background(), store, strings.TrimPrefix(srvA.URL, "http://"), "CODE-A")
	if err != nil {
		t.Fatalf("pair A: %v", err)
	}

	srvB, fpB := newFakePhoneServer(t, "CODE-B")
	fpB.phoneName = "Garage Cam" // same Google / Pixel 6, but never calibrated
	resB, err := pairing.AddPhone(context.Background(), store, strings.TrimPrefix(srvB.URL, "http://"), "CODE-B")
	if err != nil {
		t.Fatalf("pair B: %v", err)
	}

	view, err := calibration.Lookup(store, mustGetPhone(t, store, resB.PhoneID))
	if err != nil {
		t.Fatalf("Lookup B: %v", err)
	}
	if !view.Present {
		t.Fatalf("phone B's model should read as calibrated via phone A")
	}
	if !view.ViaOtherPhone || view.SourcePhoneID != resA.PhoneID {
		t.Errorf("view = %+v, want ViaOtherPhone with source %q", view, resA.PhoneID)
	}
	if view.SourcePhoneName != "Porch Cam" {
		t.Errorf("SourcePhoneName = %q, want %q", view.SourcePhoneName, "Porch Cam")
	}
}

// TestCalibration_OpportunisticDoesNotClobberFresher: an opportunistic ingest
// must not overwrite an entry the controller already holds that is at least as
// fresh (only a manual re-run does an unconditional overwrite).
func TestCalibration_OpportunisticDoesNotClobberFresher(t *testing.T) {
	store, _ := newTestStore(t)
	key := calibration.ModelKey("Google", "Pixel 6")

	if err := store.UpsertCalibration(dbstore.Calibration{
		ManufacturerModel: key,
		SourcePhoneID:     "ph_existing",
		CalibratedAtMs:    9_000_000_000_000, // far newer than sample's runAtMs
		ResultJSON:        sampleCalibrationResult,
	}); err != nil {
		t.Fatalf("seed calibration: %v", err)
	}

	srv, fp := newFakePhoneServer(t, "GOOD-CODE")
	fp.calibrationResult = sampleCalibrationResult // older runAtMs
	if _, err := pairing.AddPhone(context.Background(), store, strings.TrimPrefix(srv.URL, "http://"), "GOOD-CODE"); err != nil {
		t.Fatalf("AddPhone: %v", err)
	}

	entry, err := store.GetCalibration(key)
	if err != nil {
		t.Fatalf("GetCalibration: %v", err)
	}
	if entry.SourcePhoneID != "ph_existing" || entry.CalibratedAtMs != 9_000_000_000_000 {
		t.Errorf("fresher entry was clobbered: %+v", entry)
	}
}

// TestCalibration_ReRunStoresResult drives the Phone-detail "Re-run" building
// blocks (what App.StartCalibration / App.GetCalibrationProgress wrap):
// StartCalibration -> poll status until "completed" -> pull the result and
// store it unconditionally under the model key.
func TestCalibration_ReRunStoresResult(t *testing.T) {
	srv, _ := newFakePhoneServer(t, "GOOD-CODE") // starts with no calibration result
	store, _ := newTestStore(t)

	res, err := pairing.AddPhone(context.Background(), store, strings.TrimPrefix(srv.URL, "http://"), "GOOD-CODE")
	if err != nil {
		t.Fatalf("AddPhone: %v", err)
	}
	// Nothing ingested on pair (phone had no result yet).
	if _, err := store.GetCalibration(calibration.ModelKey("Google", "Pixel 6")); err == nil {
		t.Fatalf("did not expect a calibration entry before the re-run")
	}

	phone := mustGetPhone(t, store, res.PhoneID)
	client := phoneapi.New(phone.BaseURL, phone.Token)

	// A sweep is blocked until recording is explicitly stopped.
	if _, err := client.StartCalibration(context.Background()); !errors.Is(err, phoneapi.ErrPhoneRecording) {
		t.Fatalf("expected ErrPhoneRecording while recording, got %v", err)
	}
	if err := client.SetMode(context.Background(), "standby"); err != nil {
		t.Fatalf("SetMode standby: %v", err)
	}

	start, err := client.StartCalibration(context.Background())
	if err != nil || start.RunID == "" {
		t.Fatalf("StartCalibration: %+v err=%v", start, err)
	}

	var stored bool
	for i := 0; i < 10 && !stored; i++ {
		prog, err := client.CalibrationStatusCall(context.Background(), start.RunID)
		if err != nil {
			t.Fatalf("CalibrationStatusCall: %v", err)
		}
		if prog.Status == "completed" {
			result, raw, err := client.CalibrationResultRaw(context.Background())
			if err != nil {
				t.Fatalf("CalibrationResultRaw: %v", err)
			}
			if err := calibration.StoreResult(store, phone, result, raw); err != nil {
				t.Fatalf("StoreResult: %v", err)
			}
			stored = true
		}
	}
	if !stored {
		t.Fatalf("sweep never reported completed")
	}

	entry, err := store.GetCalibration(calibration.ModelKey("Google", "Pixel 6"))
	if err != nil {
		t.Fatalf("GetCalibration after re-run: %v", err)
	}
	if entry.SourcePhoneID != res.PhoneID {
		t.Errorf("source phone id = %q, want %q", entry.SourcePhoneID, res.PhoneID)
	}
}

func mustGetPhone(t *testing.T, store *dbstore.Store, id string) dbstore.Phone {
	t.Helper()
	p, err := store.GetPhone(id)
	if err != nil {
		t.Fatalf("GetPhone %s: %v", id, err)
	}
	return p
}
