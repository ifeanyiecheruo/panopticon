package main

import (
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"panopticon-controller/internal/syncer"
)

// aoiHandler gives the gallery player each archived segment's area of
// interest, which the syncer stored beside it (syncer.AOISuffix):
//
//	GET /aoi/<phoneID>/<filename>  -> {"l","t","r","b"}, or 204 when it had none
//
// The player stretches the picture's contrast to the levels inside that rect;
// a 204 means play the segment as recorded.
func aoiHandler(archiveDir string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		phoneID, name, ok := strings.Cut(strings.TrimPrefix(r.URL.Path, "/aoi/"), "/")
		if !ok || !validArchiveName(phoneID) || !validArchiveName(name) || !strings.HasSuffix(name, ".mp4") {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		segment := filepath.Join(archiveDir, phoneID, name)
		if _, err := os.Stat(segment); err != nil {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		f, err := os.Open(segment + syncer.AOISuffix)
		if err != nil {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		defer f.Close()
		w.Header().Set("Content-Type", "application/json")
		if r.Method == http.MethodHead {
			return
		}
		_, _ = io.Copy(w, f)
	})
}
