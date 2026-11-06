package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"panopticon-controller/internal/syncer"
)

func TestAOIHandler(t *testing.T) {
	archive := t.TempDir()
	dir := filepath.Join(archive, "ph_1")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"plain.mp4", "aimed.mp4"} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	const rect = `{"l":0.1,"t":0.2,"r":0.5,"b":0.6}`
	if err := os.WriteFile(filepath.Join(dir, "aimed.mp4"+syncer.AOISuffix), []byte(rect), 0o644); err != nil {
		t.Fatal(err)
	}
	// A sidecar alone is not a segment.
	if err := os.WriteFile(filepath.Join(dir, "gone.mp4"+syncer.AOISuffix), []byte(rect), 0o644); err != nil {
		t.Fatal(err)
	}
	h := aoiHandler(archive)

	for _, tc := range []struct {
		path string
		want int
		body string
	}{
		{"/aoi/ph_1/aimed.mp4", http.StatusOK, rect},
		{"/aoi/ph_1/plain.mp4", http.StatusNoContent, ""},
		{"/aoi/ph_1/gone.mp4", http.StatusNotFound, ""},
		{"/aoi/ph_1/aimed.mp4.jpg", http.StatusNotFound, ""},
		{"/aoi/../ph_1/aimed.mp4", http.StatusNotFound, ""},
	} {
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		req.URL.Path = tc.path
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if rec.Code != tc.want {
			t.Errorf("%s: status %d, want %d", tc.path, rec.Code, tc.want)
		}
		if tc.body != "" && rec.Body.String() != tc.body {
			t.Errorf("%s: body %q, want %q", tc.path, rec.Body.String(), tc.body)
		}
	}
}
