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

// A completed result whose two cameras were finished by different runs, as a phone serves it
// after a re-run that finished the back camera ("cal-new", newer) but not yet the front one
// (still "cal-old" from an earlier run).
const perCameraResult = `{
  "runId": "cal-new",
  "runAtMs": 3000,
  "cameras": {
    "0": {"deviceIdentity": {"cameraId": "0", "facing": "back"}, "runId": "cal-new", "runAtMs": 3000,
          "steps": {"zoom-map": {"checksTotal": 4, "checksPassed": 4}}},
    "1": {"deviceIdentity": {"cameraId": "1", "facing": "front"}, "runId": "cal-old", "runAtMs": 1000,
          "steps": {"zoom-map": {"checksTotal": 2, "checksPassed": 2}}}
  }
}`

var pixel6 = calibration.ModelKey("Google", "Pixel 6")

// cameraSources maps each stored camera of the Pixel 6 model to the phone that supplied it.
func cameraSources(t *testing.T, store *dbstore.Store) map[string]string {
	t.Helper()
	rows, err := store.ListCameraCalibrations(pixel6)
	if err != nil {
		t.Fatalf("ListCameraCalibrations: %v", err)
	}
	out := map[string]string{}
	for _, row := range rows {
		out[row.CameraID] = row.SourcePhoneID
	}
	return out
}

func seedCamera(t *testing.T, store *dbstore.Store, cameraID, phoneID string, atMs int64) {
	t.Helper()
	if err := store.UpsertCameraCalibration(dbstore.CameraCalibration{
		ManufacturerModel: pixel6, CameraID: cameraID, SourcePhoneID: phoneID,
		CalibratedAtMs: atMs, CameraJSON: `{"deviceIdentity": {"cameraId": "` + cameraID + `"}}`,
	}); err != nil {
		t.Fatalf("seed camera %s: %v", cameraID, err)
	}
}

func pair(t *testing.T, store *dbstore.Store, code, result string) pairing.Result {
	t.Helper()
	srv, fp := newFakePhoneServer(t, code)
	fp.calibrationResult = result
	res, err := pairing.AddPhone(context.Background(), store, strings.TrimPrefix(srv.URL, "http://"), code)
	if err != nil {
		t.Fatalf("AddPhone %s: %v", code, err)
	}
	return res
}

// TestAddPhone_IngestsCalibrationOnPair: a phone that has already calibrated
// gets its result pulled into the controller's model-keyed store the moment
// it's paired, with no sweep triggered.
func TestAddPhone_IngestsCalibrationOnPair(t *testing.T) {
	store, _ := newTestStore(t)
	result := pair(t, store, "GOOD-CODE", sampleCalibrationResult)

	rows, err := store.ListCameraCalibrations(pixel6)
	if err != nil || len(rows) != 1 {
		t.Fatalf("expected one camera stored for Google|Pixel 6, got %+v err=%v", rows, err)
	}
	if rows[0].SourcePhoneID != result.PhoneID {
		t.Errorf("source phone id = %q, want %q", rows[0].SourcePhoneID, result.PhoneID)
	}
	if rows[0].CalibratedAtMs != 1755270015231 {
		t.Errorf("calibratedAtMs = %d, want 1755270015231", rows[0].CalibratedAtMs)
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
// must not overwrite a camera the controller already holds at least as fresh
// (only a manual re-run does an unconditional overwrite).
func TestCalibration_OpportunisticDoesNotClobberFresher(t *testing.T) {
	store, _ := newTestStore(t)
	seedCamera(t, store, "0", "ph_existing", 9_000_000_000_000) // far newer than sample's runAtMs

	pair(t, store, "GOOD-CODE", sampleCalibrationResult) // older runAtMs

	if got := cameraSources(t, store); got["0"] != "ph_existing" {
		t.Errorf("fresher camera was clobbered: sources %v", got)
	}
}

// TestCalibration_OpportunisticTakesEachCameraOnItsOwnFreshness: phones of one model each
// contribute the cameras they calibrated most recently - a phone that only re-did the back
// camera replaces the back camera and leaves the front camera from another phone alone.
func TestCalibration_OpportunisticTakesEachCameraOnItsOwnFreshness(t *testing.T) {
	store, _ := newTestStore(t)
	seedCamera(t, store, "0", "ph_other", 2000) // older than perCameraResult's back camera (3000)
	seedCamera(t, store, "1", "ph_other", 2000) // newer than its front camera (1000)

	res := pair(t, store, "GOOD-CODE", perCameraResult)

	got := cameraSources(t, store)
	if got["0"] != res.PhoneID || got["1"] != "ph_other" {
		t.Errorf("sources = %v, want back camera from %s and front kept from ph_other", got, res.PhoneID)
	}
}

// TestCalibration_OpportunisticSkipsPartial: an in-progress sweep's document is
// "complete": false - pairing must never store it, even when the controller
// holds nothing for the model yet.
func TestCalibration_OpportunisticSkipsPartial(t *testing.T) {
	store, _ := newTestStore(t)

	pair(t, store, "GOOD-CODE", strings.Replace(sampleCalibrationResult, `"runId"`, `"complete": false, "runId"`, 1))

	if got := cameraSources(t, store); len(got) != 0 {
		t.Errorf("partial result was ingested: sources %v", got)
	}
}

// TestCalibration_ReRunOverwritesOnlyTheCamerasItFinished: storing after a manual run forces
// the cameras that run finished, however fresh the held data looks; the cameras the phone
// still serves from earlier runs follow the freshness rule.
func TestCalibration_ReRunOverwritesOnlyTheCamerasItFinished(t *testing.T) {
	store, _ := newTestStore(t)
	res := pair(t, store, "GOOD-CODE", "") // nothing to ingest on pair
	seedCamera(t, store, "0", "ph_other", 9_000_000_000_000)
	seedCamera(t, store, "1", "ph_other", 9_000_000_000_000)

	phone := mustGetPhone(t, store, res.PhoneID)
	result, err := phoneapi.ParseResultSummary(perCameraResult)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	n, err := calibration.StoreRun(store, phone, result, []byte(perCameraResult), "cal-new")
	if err != nil {
		t.Fatalf("StoreRun: %v", err)
	}

	got := cameraSources(t, store)
	if n != 1 || got["0"] != res.PhoneID || got["1"] != "ph_other" {
		t.Errorf("stored %d, sources %v; want only the back camera, from %s", n, got, res.PhoneID)
	}
}

// TestCalibration_ReRunStoresResult drives the Phone-detail "Re-run" building
// blocks (what App.StartCalibration / App.GetCalibrationProgress wrap):
// StartCalibration -> poll status until "completed" -> pull the result and
// store its cameras under the model key.
func TestCalibration_ReRunStoresResult(t *testing.T) {
	srv, fp := newFakePhoneServer(t, "GOOD-CODE") // starts with no calibration result
	store, _ := newTestStore(t)

	res, err := pairing.AddPhone(context.Background(), store, strings.TrimPrefix(srv.URL, "http://"), "GOOD-CODE")
	if err != nil {
		t.Fatalf("AddPhone: %v", err)
	}
	// Nothing ingested on pair (phone had no result yet).
	if got := cameraSources(t, store); len(got) != 0 {
		t.Fatalf("did not expect a calibration entry before the re-run, got %v", got)
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
	fp.mu.Lock()
	fp.calibrationResult = sampleCalibrationResult // what the sweep leaves behind
	fp.mu.Unlock()

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
			if _, err := calibration.StoreRun(store, phone, result, raw, prog.RunID); err != nil {
				t.Fatalf("StoreRun: %v", err)
			}
			stored = true
		}
	}
	if !stored {
		t.Fatalf("sweep never reported completed")
	}

	if got := cameraSources(t, store); got["0"] != res.PhoneID {
		t.Errorf("sources after re-run = %v, want camera 0 from %s", got, res.PhoneID)
	}
}

// TestCalibration_EraseDropsOnlyThatPhonesCameras drives what App.EraseCalibration wraps: the
// phone erases its calibration, then the controller drops the cameras that phone supplied.
// A camera another phone of the model supplied stays.
func TestCalibration_EraseDropsOnlyThatPhonesCameras(t *testing.T) {
	store, _ := newTestStore(t)
	res := pair(t, store, "GOOD-CODE", sampleCalibrationResult) // supplies camera 0
	seedCamera(t, store, "1", "ph_other", 1)

	phone := mustGetPhone(t, store, res.PhoneID)
	client := phoneapi.New(phone.BaseURL, phone.Token)
	if err := client.EraseCalibration(context.Background()); err != nil {
		t.Fatalf("EraseCalibration: %v", err)
	}
	if err := store.DeleteCameraCalibrationsFromPhone(res.PhoneID); err != nil {
		t.Fatalf("DeleteCameraCalibrationsFromPhone: %v", err)
	}

	if got := cameraSources(t, store); len(got) != 1 || got["1"] != "ph_other" {
		t.Errorf("sources after erase = %v, want only ph_other's camera 1", got)
	}
	if _, _, err := client.CalibrationResultRaw(context.Background()); !errors.Is(err, phoneapi.ErrNoCalibrationResult) {
		t.Errorf("phone still serves a result after erase: err=%v", err)
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
