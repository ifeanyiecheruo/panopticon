package main

import (
	"errors"
	"io"
	"net/http"
	"strings"

	"panopticon-controller/internal/dbstore"
	"panopticon-controller/internal/phoneapi"
)

// liveProxyHandler serves the webview's <video>/hls.js a same-origin view of a
// phone's live HLS feed at:
//
//	GET /live/<phoneID>/live.m3u8      -> proxied from the phone's GET /live/live.m3u8
//	GET /live/<phoneID>/live-<n>.ts    -> proxied from the phone's GET /live/live-<n>.ts
//
// Going through the controller (rather than pointing hls.js straight at the
// phone's LAN address) keeps the bearer token server-side and sidesteps CORS /
// mixed-content between the wails asset origin and the phone. The playlist's
// segment URIs are relative ("live-<n>.ts"), so they resolve against this same
// path prefix with no rewriting.
//
// Mounted on the wails asset-server middleware in main.go, alongside /archive/.
func liveProxyHandler(store *dbstore.Store) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rest := strings.TrimPrefix(r.URL.Path, "/live/")
		phoneID, file, ok := strings.Cut(rest, "/")
		if !ok || phoneID == "" || file == "" {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}

		phone, err := store.GetPhone(phoneID)
		if err != nil {
			http.Error(w, "unknown phone", http.StatusNotFound)
			return
		}
		client := phoneapi.New(phone.BaseURL, phone.Token)

		switch {
		case file == "live.m3u8":
			body, err := client.LivePlaylist(r.Context())
			if err != nil {
				writeProxyError(w, err)
				return
			}
			w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
			w.Header().Set("Cache-Control", "no-store")
			w.Write(body)

		case strings.HasPrefix(file, "live-") && strings.HasSuffix(file, ".ts"):
			rc, err := client.LiveSegment(r.Context(), file)
			if err != nil {
				writeProxyError(w, err)
				return
			}
			defer rc.Close()
			w.Header().Set("Content-Type", "video/mp2t")
			w.Header().Set("Cache-Control", "no-store")
			io.Copy(w, rc)

		default:
			http.Error(w, "not found", http.StatusNotFound)
		}
	})
}

// writeProxyError collapses phoneapi's error shapes to a status hls.js can act
// on: an unreachable phone or an evicted/absent segment is a 404 (hls.js
// retries a fragment 404 quickly); anything else is a 502.
func writeProxyError(w http.ResponseWriter, err error) {
	var httpErr *phoneapi.HTTPError
	switch {
	case errors.Is(err, phoneapi.ErrEvicted), errors.Is(err, phoneapi.ErrUnreachable):
		http.Error(w, "live segment unavailable", http.StatusNotFound)
	case errors.As(err, &httpErr):
		http.Error(w, httpErr.Body, httpErr.StatusCode)
	default:
		http.Error(w, "live proxy error", http.StatusBadGateway)
	}
}
