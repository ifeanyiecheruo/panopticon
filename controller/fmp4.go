package main

import (
	"errors"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"panopticon-controller/internal/remux"
)

// fmp4Handler serves archived segments to the gallery player as fragmented
// MP4, remuxed on the fly:
//
//	GET /fmp4/<phoneID>/<filename>   -> /archive/<phoneID>/<filename>, as fMP4
//
// ClipPlayer feeds these to one MediaSource so a clip's segments - and a
// play-through into the next clip - play as one stream on one <video>
// (docs/design/components/controller-ui.md, "Clip playback"). The archive
// keeps the phone's progressive MP4 untouched: remuxing reads only the moov
// and copies the samples through, so doing it per request is cheap (~10ms a
// segment) and needs no second copy on disk.
//
// X-Codec carries the codec string MSE's addSourceBuffer needs.
func fmp4Handler(archiveDir string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		phoneID, name, ok := strings.Cut(strings.TrimPrefix(r.URL.Path, "/fmp4/"), "/")
		if !ok || !validArchiveName(phoneID) || !validArchiveName(name) || !strings.HasSuffix(name, ".mp4") {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		f, err := os.Open(filepath.Join(archiveDir, phoneID, name))
		if err != nil {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		defer f.Close()
		st, err := f.Stat()
		if err != nil {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}

		frag, err := remux.Open(f, st.Size())
		if err != nil {
			log.Printf("fmp4: %s/%s: %v", phoneID, name, err)
			status := http.StatusInternalServerError
			if errors.Is(err, remux.ErrUnsupported) {
				status = http.StatusUnsupportedMediaType
			}
			http.Error(w, "cannot remux segment", status)
			return
		}
		w.Header().Set("Content-Type", "video/mp4")
		w.Header().Set("Content-Length", strconv.FormatInt(frag.Size(), 10))
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Codec", frag.Codec)
		if r.Method == http.MethodHead {
			return
		}
		frag.WriteTo(w)
	})
}

// validArchiveName accepts one path element: no separators, no "..".
func validArchiveName(s string) bool {
	return s != "" && s != "." && s != ".." && !strings.ContainsAny(s, `/\`)
}
