package integrationtest

import (
	"context"
	"errors"
	"strings"
	"testing"

	"panopticon-controller/internal/pairing"
	"panopticon-controller/internal/phoneapi"
)

func pairFake(t *testing.T) (*fakePhone, *phoneapi.Client) {
	t.Helper()
	srv, fp := newFakePhoneServer(t, "GOOD-CODE")
	store, _ := newTestStore(t)
	res, err := pairing.AddPhone(context.Background(), store, strings.TrimPrefix(srv.URL, "http://"), "GOOD-CODE")
	if err != nil {
		t.Fatalf("AddPhone: %v", err)
	}
	phone := mustGetPhone(t, store, res.PhoneID)
	return fp, phoneapi.New(phone.BaseURL, phone.Token)
}

func TestThermal_StatusDecodesGuard(t *testing.T) {
	fp, client := pairFake(t)

	// A phone build that predates the guard: no field at all, not a zero-valued one.
	st, err := client.Status(context.Background())
	if err != nil {
		t.Fatalf("Status: %v", err)
	}
	if st.ThermalShutdown != nil {
		t.Fatalf("expected nil ThermalShutdown from an old phone, got %+v", st.ThermalShutdown)
	}

	fp.mu.Lock()
	fp.thermalGuard = true
	fp.mu.Unlock()
	st, err = client.Status(context.Background())
	if err != nil {
		t.Fatalf("Status: %v", err)
	}
	g := st.ThermalShutdown
	if g == nil || g.Active || g.SinceMs != nil || g.Source != "thermalStatus" || g.Level != "light" ||
		g.CriticalLevel != "severe" || g.SafeLevel != "moderate" || g.BatteryTempC != nil {
		t.Fatalf("cool guard decoded wrong: %+v", g)
	}

	fp.mu.Lock()
	fp.overheated = true
	fp.mu.Unlock()
	st, err = client.Status(context.Background())
	if err != nil {
		t.Fatalf("Status: %v", err)
	}
	g = st.ThermalShutdown
	if st.Status != "overheated" || g == nil || !g.Active || g.Reason != "thermalStatus" || g.SinceMs == nil || g.Level != "severe" {
		t.Fatalf("tripped guard decoded wrong: status=%q guard=%+v", st.Status, g)
	}
}

func TestThermal_CalibrationRefusedWhileOverheated(t *testing.T) {
	fp, client := pairFake(t)
	if err := client.SetMode(context.Background(), "standby"); err != nil {
		t.Fatalf("SetMode standby: %v", err)
	}
	fp.mu.Lock()
	fp.overheated = true
	fp.mu.Unlock()

	// A 409 like "already running" or "recording", but must not be mistaken for either.
	_, err := client.StartCalibration(context.Background())
	if !errors.Is(err, phoneapi.ErrPhoneOverheated) {
		t.Fatalf("expected ErrPhoneOverheated, got %v", err)
	}

	fp.mu.Lock()
	fp.overheated = false
	fp.mu.Unlock()
	if _, err := client.StartCalibration(context.Background()); err != nil {
		t.Fatalf("StartCalibration once cooled: %v", err)
	}
}
