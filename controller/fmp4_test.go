package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestFmp4HandlerRefusesAnythingButOneArchivedSegment(t *testing.T) {
	archive := t.TempDir()
	if err := os.MkdirAll(filepath.Join(archive, "ph_1"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(archive, "ph_1", "garbage.mp4"), []byte("not an mp4"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(archive, "secret.mp4"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	h := fmp4Handler(archive)

	for _, tc := range []struct {
		path string
		want int
	}{
		{"/fmp4/ph_1/missing.mp4", http.StatusNotFound},
		{"/fmp4/ph_1/garbage.mp4.jpg", http.StatusNotFound},
		{"/fmp4/ph_1", http.StatusNotFound},
		{"/fmp4/../secret.mp4", http.StatusNotFound},
		{`/fmp4/ph_1/..\secret.mp4`, http.StatusNotFound},
		{"/fmp4/ph_1/garbage.mp4", http.StatusInternalServerError},
	} {
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		req.URL.Path = tc.path
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if rec.Code != tc.want {
			t.Errorf("%s: status %d, want %d", req.URL.Path, rec.Code, tc.want)
		}
	}
}
