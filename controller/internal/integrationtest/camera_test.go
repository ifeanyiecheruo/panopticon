package integrationtest

import (
	"context"
	"errors"
	"strings"
	"testing"

	"panopticon-controller/internal/pairing"
	"panopticon-controller/internal/phoneapi"
)

// clientFor pairs against a fresh fake phone and returns a phoneapi.Client
// carrying the issued bearer token - the same object App's camera bindings build.
func clientFor(t *testing.T, invite string) (*phoneapi.Client, *fakePhone) {
	t.Helper()
	srv, fp := newFakePhoneServer(t, invite)
	store, _ := newTestStore(t)
	res, err := pairing.AddPhone(context.Background(), store, strings.TrimPrefix(srv.URL, "http://"), invite)
	if err != nil {
		t.Fatalf("AddPhone: %v", err)
	}
	phone, err := store.GetPhone(res.PhoneID)
	if err != nil {
		t.Fatalf("GetPhone: %v", err)
	}
	return phoneapi.New(phone.BaseURL, phone.Token), fp
}

func TestCameras_ListAndSwitchActive(t *testing.T) {
	client, _ := clientFor(t, "CAM-CODE")
	ctx := context.Background()

	list, err := client.Cameras(ctx)
	if err != nil {
		t.Fatalf("Cameras: %v", err)
	}
	if len(list.Cameras) != 3 {
		t.Fatalf("want 3 cameras (logical 0, physical 0:2, front 1), got %d", len(list.Cameras))
	}
	active := ""
	havePhysical := false
	for _, c := range list.Cameras {
		if c.IsActive {
			active = c.CameraID
		}
		if c.CameraID == "0:2" {
			havePhysical = true
		}
	}
	if active != "0" {
		t.Errorf("want camera 0 active initially, got %q", active)
	}
	if !havePhysical {
		t.Errorf("expected the physical sub-camera 0:2 to be listed: %+v", list.Cameras)
	}

	// Switch to the physical sub-camera.
	got, err := client.SetActiveCamera(ctx, "0:2")
	if err != nil {
		t.Fatalf("SetActiveCamera(0:2): %v", err)
	}
	if got != "0:2" {
		t.Errorf("SetActiveCamera returned %q, want 0:2", got)
	}

	list, _ = client.Cameras(ctx)
	for _, c := range list.Cameras {
		if c.CameraID == "0:2" && !c.IsActive {
			t.Errorf("camera 0:2 should now be active: %+v", list.Cameras)
		}
	}

	// Its capabilities should still carry the physical-camera list for the logical id.
	caps, err := client.CameraCapabilities(ctx, "0")
	if err != nil {
		t.Fatalf("CameraCapabilities(0): %v", err)
	}
	if len(caps.PhysicalCameraIDs) == 0 {
		t.Errorf("logical camera 0 should report physicalCameraIds, got %+v", caps)
	}
}

func TestCameras_SwitchToUnknownIsErrUnknownCamera(t *testing.T) {
	client, _ := clientFor(t, "CAM-CODE")
	_, err := client.SetActiveCamera(context.Background(), "9")
	if !errors.Is(err, phoneapi.ErrUnknownCamera) {
		t.Fatalf("want ErrUnknownCamera, got %v", err)
	}
}

func TestCameraCapabilities_ReportsManualSensor(t *testing.T) {
	client, _ := clientFor(t, "CAM-CODE")
	caps, err := client.CameraCapabilities(context.Background(), "")
	if err != nil {
		t.Fatalf("CameraCapabilities: %v", err)
	}
	if !caps.HasManualSensor || !caps.HasManualFocus {
		t.Errorf("want manual sensor + focus, got %+v", caps)
	}
	if caps.ZoomRatioRange.Lo != 1.0 || caps.ZoomRatioRange.Hi != 8.0 {
		t.Errorf("zoom range = %+v, want 1..8", caps.ZoomRatioRange)
	}
	if caps.ExposureTimeRangeNs == nil || caps.SensitivityRange == nil {
		t.Errorf("want exposure + sensitivity ranges, got %+v", caps)
	}
	if len(caps.AWBModes) == 0 || len(caps.VideoStabilizationModes) == 0 || len(caps.OpticalStabilizationModes) == 0 {
		t.Errorf("want AWB + video/optical-stabilization modes, got %+v", caps)
	}
	if !caps.HasManualWhiteBalance || caps.WBGainRange.Hi <= caps.WBGainRange.Lo {
		t.Errorf("want manual WB with a gain range, got %+v", caps)
	}
}

func TestCameraState_ManualWhiteBalanceAndOpticalStab(t *testing.T) {
	client, _ := clientFor(t, "CAM-CODE")
	ctx := context.Background()

	on := true
	r, g, b := 2.5, 1.4, 3.1
	ois := 1
	set, err := client.SetCameraState(ctx, phoneapi.CameraStatePatch{
		ManualControlEnabled: &on,
		Keys: &phoneapi.CameraControlKeys{
			ManualWhiteBalance:       &on,
			WBRedGain:                &r,
			WBGreenGain:              &g,
			WBBlueGain:               &b,
			OpticalStabilizationMode: &ois,
		},
	})
	if err != nil {
		t.Fatalf("SetCameraState: %v", err)
	}
	if set.Keys.ManualWhiteBalance == nil || !*set.Keys.ManualWhiteBalance ||
		set.Keys.WBRedGain == nil || *set.Keys.WBRedGain != 2.5 ||
		set.Keys.OpticalStabilizationMode == nil || *set.Keys.OpticalStabilizationMode != 1 {
		t.Errorf("manual WB / OIS not echoed: %+v", set.Keys)
	}

	bad := 7
	_, err = client.SetCameraState(ctx, phoneapi.CameraStatePatch{
		Keys: &phoneapi.CameraControlKeys{OpticalStabilizationMode: &bad},
	})
	var ck *phoneapi.InvalidControlKeyError
	if !errors.As(err, &ck) || ck.Key != "opticalStabilizationMode" {
		t.Fatalf("want *InvalidControlKeyError{Key:opticalStabilizationMode}, got %v", err)
	}
}

func TestCameraState_WhiteBalanceRoundTripAndReject(t *testing.T) {
	client, _ := clientFor(t, "CAM-CODE")
	ctx := context.Background()

	awb := 5 // daylight
	stab := 1
	on := true
	set, err := client.SetCameraState(ctx, phoneapi.CameraStatePatch{
		ManualControlEnabled: &on,
		Keys:                 &phoneapi.CameraControlKeys{AWBMode: &awb, VideoStabilizationMode: &stab},
	})
	if err != nil {
		t.Fatalf("SetCameraState: %v", err)
	}
	if set.Keys.AWBMode == nil || *set.Keys.AWBMode != 5 || set.Keys.VideoStabilizationMode == nil || *set.Keys.VideoStabilizationMode != 1 {
		t.Errorf("wb/stab not echoed: %+v", set.Keys)
	}

	bad := 3 // fluorescent - not in the mock's list
	_, err = client.SetCameraState(ctx, phoneapi.CameraStatePatch{
		Keys: &phoneapi.CameraControlKeys{AWBMode: &bad},
	})
	var ck *phoneapi.InvalidControlKeyError
	if !errors.As(err, &ck) || ck.Key != "awbMode" {
		t.Fatalf("want *InvalidControlKeyError{Key:awbMode}, got %v", err)
	}
}

func TestCameraState_GetSetRoundTrip(t *testing.T) {
	client, _ := clientFor(t, "CAM-CODE")
	ctx := context.Background()

	st, err := client.CameraState(ctx)
	if err != nil {
		t.Fatalf("CameraState: %v", err)
	}
	if st.ManualControlEnabled {
		t.Errorf("expected manual control off initially")
	}

	zoom := 3.0
	on := true
	set, err := client.SetCameraState(ctx, phoneapi.CameraStatePatch{
		ManualControlEnabled: &on,
		Keys:                 &phoneapi.CameraControlKeys{ZoomRatio: &zoom},
	})
	if err != nil {
		t.Fatalf("SetCameraState: %v", err)
	}
	if !set.ManualControlEnabled || set.Keys.ZoomRatio == nil || *set.Keys.ZoomRatio != 3.0 {
		t.Errorf("state not echoed: %+v", set)
	}

	again, _ := client.CameraState(ctx)
	if again.Keys.ZoomRatio == nil || *again.Keys.ZoomRatio != 3.0 {
		t.Errorf("state not persisted across GET: %+v", again)
	}
}

func TestCameraState_InvalidKeyReturnsTypedError(t *testing.T) {
	client, _ := clientFor(t, "CAM-CODE")
	bad := 99.0
	_, err := client.SetCameraState(context.Background(), phoneapi.CameraStatePatch{
		Keys: &phoneapi.CameraControlKeys{ZoomRatio: &bad},
	})
	var ck *phoneapi.InvalidControlKeyError
	if !errors.As(err, &ck) {
		t.Fatalf("want *InvalidControlKeyError, got %v", err)
	}
	if ck.Key != "zoomRatio" {
		t.Errorf("want key zoomRatio, got %q", ck.Key)
	}
}
