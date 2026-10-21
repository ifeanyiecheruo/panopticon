package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// TestStillProxy_ServesJPEGWithCaptureTime verifies a still reaches the
// webview as-is, with the phone's capture time and the bearer token kept
// server-side.
func TestStillProxy_ServesJPEGWithCaptureTime(t *testing.T) {
	jpeg := []byte{0xFF, 0xD8, 0xFF, 0xD9}
	var gotAuth string
	phone := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/camera/snapshot" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		gotAuth = r.Header.Get("Authorization")
		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("X-Captured-At-Ms", "1755270015231")
		w.Write(jpeg)
	}))
	defer phone.Close()

	handler := stillProxyHandler(newLiveProxyTestStore(t, "phone1", phone.URL, "tok"))
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/still/phone1", nil))

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (body: %s)", rec.Code, rec.Body.String())
	}
	if gotAuth != "Bearer tok" {
		t.Errorf("phone saw Authorization %q, want the stored token", gotAuth)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "image/jpeg" {
		t.Errorf("Content-Type = %q, want image/jpeg", ct)
	}
	if at := rec.Header().Get("X-Captured-At-Ms"); at != "1755270015231" {
		t.Errorf("X-Captured-At-Ms = %q, want the phone's value", at)
	}
	if rec.Body.String() != string(jpeg) {
		t.Errorf("body was altered in transit")
	}
}

// TestStillProxy_RelaysPhoneRefusal verifies the phone's 409/503 and their
// JSON bodies pass straight through: the frontend reads them to decide
// between "the phone stopped recording" and "try again shortly".
func TestStillProxy_RelaysPhoneRefusal(t *testing.T) {
	tests := []struct {
		name   string
		status int
		body   string
	}{
		{"not recording", http.StatusConflict, `{"error":"not recording"}`},
		{"camera restarting", http.StatusServiceUnavailable, `{"error":"camera still starting","retryAfterMs":2000}`},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			phone := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(tt.status)
				w.Write([]byte(tt.body))
			}))
			defer phone.Close()

			handler := stillProxyHandler(newLiveProxyTestStore(t, "phone1", phone.URL, "tok"))
			rec := httptest.NewRecorder()
			handler.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/still/phone1", nil))

			if rec.Code != tt.status {
				t.Errorf("status = %d, want %d", rec.Code, tt.status)
			}
			if rec.Body.String() != tt.body {
				t.Errorf("body = %q, want %q", rec.Body.String(), tt.body)
			}
		})
	}
}

func TestStillProxy_UnknownPhone(t *testing.T) {
	handler := stillProxyHandler(newLiveProxyTestStore(t, "phone1", "http://127.0.0.1:1", "tok"))
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/still/nope", nil))
	if rec.Code != http.StatusNotFound {
		t.Errorf("status = %d, want 404", rec.Code)
	}
}
