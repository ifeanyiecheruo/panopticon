package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
)

// fakeLivePhone is just enough of a phone for StartLivePreview/StopLivePreview: a mode, a
// live/start that answers per mode, and a log of the mode changes the controller asked for.
type fakeLivePhone struct {
	mu          sync.Mutex
	mode        string
	refuseInRec bool     // answer live/start with 409 in record, like an older build or a warm phone
	modesSet    []string // every POST /api/mode body, in order
	liveStarts  int
	liveStops   int
}

func (f *fakeLivePhone) serve(t *testing.T) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/api/status":
			json.NewEncoder(w).Encode(map[string]any{"mode": f.mode, "status": "idle"})
		case "/api/mode":
			var body struct{ Mode string }
			json.NewDecoder(r.Body).Decode(&body)
			f.modesSet = append(f.modesSet, body.Mode)
			f.mode = body.Mode
			json.NewEncoder(w).Encode(map[string]string{"mode": body.Mode})
		case "/api/live/start":
			f.liveStarts++
			if f.mode == "standby" || (f.mode == "record" && f.refuseInRec) {
				w.WriteHeader(http.StatusConflict)
				w.Write([]byte(`{"error":"phone is warm (thermal moderate): no live view while recording, stills only"}`))
				return
			}
			w.Write([]byte(`{"started":true,"viewerCount":1}`))
		case "/api/live/stop":
			f.liveStops++
			w.Write([]byte(`{"stopped":true,"viewerCount":0}`))
		default:
			t.Errorf("unexpected request %s %s", r.Method, r.URL.Path)
			w.WriteHeader(http.StatusNotFound)
		}
	}))
}

func newLivePreviewTestApp(t *testing.T, phone *fakeLivePhone) *App {
	t.Helper()
	srv := phone.serve(t)
	t.Cleanup(srv.Close)
	return &App{store: newLiveProxyTestStore(t, "phone1", srv.URL, "tok"), livePriorMode: map[string]string{}}
}

// TestStartLivePreview_WhileRecording_StreamsWithoutModeChange: a recording phone is asked to
// stream off its recording encoder and is never moved out of record.
func TestStartLivePreview_WhileRecording_StreamsWithoutModeChange(t *testing.T) {
	phone := &fakeLivePhone{mode: "record"}
	app := newLivePreviewTestApp(t, phone)

	res := app.StartLivePreview("phone1")
	if !res.OK || res.PlaylistPath != "/live/phone1/live.m3u8" {
		t.Fatalf("StartLivePreview = %+v, want ok with the playlist path", res)
	}
	if len(phone.modesSet) != 0 {
		t.Errorf("mode changes = %v, want none while recording", phone.modesSet)
	}
	if err := app.StopLivePreview("phone1"); err != nil {
		t.Fatalf("StopLivePreview: %v", err)
	}
	if phone.liveStops != 1 || len(phone.modesSet) != 0 || phone.mode != "record" {
		t.Errorf("after stop: stops=%d modes=%v mode=%s, want one stop and still recording",
			phone.liveStops, phone.modesSet, phone.mode)
	}
}

// TestStartLivePreview_RecordingPhoneDeclines_ReportsRecording: the phone's 409 in record comes
// back as Outcome "recording" with the phone's reason, which is the frontend's cue for stills.
func TestStartLivePreview_RecordingPhoneDeclines_ReportsRecording(t *testing.T) {
	phone := &fakeLivePhone{mode: "record", refuseInRec: true}
	app := newLivePreviewTestApp(t, phone)

	res := app.StartLivePreview("phone1")
	if res.OK || res.Outcome != "recording" {
		t.Fatalf("StartLivePreview = %+v, want Outcome recording", res)
	}
	if res.Message != "phone is warm (thermal moderate): no live view while recording, stills only" {
		t.Errorf("Message = %q, want the phone's own reason", res.Message)
	}
	if len(phone.modesSet) != 0 {
		t.Errorf("mode changes = %v, want none", phone.modesSet)
	}
}

// TestStopLivePreview_PhoneStartedRecording_LeavesItRecording: live was started from standby,
// then the phone began recording by another route. Stopping must not put it back in standby.
func TestStopLivePreview_PhoneStartedRecording_LeavesItRecording(t *testing.T) {
	phone := &fakeLivePhone{mode: "standby"}
	app := newLivePreviewTestApp(t, phone)

	if res := app.StartLivePreview("phone1"); !res.OK {
		t.Fatalf("StartLivePreview = %+v", res)
	}
	phone.mu.Lock()
	phone.mode = "record" // someone pressed record on the phone
	phone.mu.Unlock()

	if err := app.StopLivePreview("phone1"); err != nil {
		t.Fatalf("StopLivePreview: %v", err)
	}
	if phone.mode != "record" {
		t.Errorf("mode = %s after stop, want record (modes set: %v)", phone.mode, phone.modesSet)
	}
}

// TestStopLivePreview_FromLive_RestoresPriorMode: the existing contract still holds when the
// phone is actually in live mode.
func TestStopLivePreview_FromLive_RestoresPriorMode(t *testing.T) {
	phone := &fakeLivePhone{mode: "standby"}
	app := newLivePreviewTestApp(t, phone)

	if res := app.StartLivePreview("phone1"); !res.OK {
		t.Fatalf("StartLivePreview = %+v", res)
	}
	if err := app.StopLivePreview("phone1"); err != nil {
		t.Fatalf("StopLivePreview: %v", err)
	}
	if got, want := phone.modesSet, []string{"live", "standby"}; len(got) != 2 || got[0] != want[0] || got[1] != want[1] {
		t.Errorf("modes set = %v, want %v", got, want)
	}
}
