package main

import (
	"errors"
	"net/http"
	"strconv"
	"strings"

	"panopticon-controller/internal/dbstore"
	"panopticon-controller/internal/phoneapi"
)

// stillProxyHandler serves the webview a same-origin JPEG still of what a
// recording phone is encoding:
//
//	GET /still/<phoneID>   -> proxied from the phone's GET /api/camera/snapshot
//
// The recording view's stand-in for live video (docs/design/decisions/
// 0015-recording-stills.md). The phone renders a still only when asked, so a
// still exists only while Phone detail is open and polling this. Same reasons
// as liveproxy.go for going through the controller: the bearer token stays
// server-side and there is no cross-origin fetch.
//
// The phone's own status passes through on failure (409 not recording, 503
// with a retryAfterMs body while its camera restarts or it is overheated), so
// the frontend can tell "stop polling" from "try again".
func stillProxyHandler(store *dbstore.Store) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		phoneID := strings.TrimPrefix(r.URL.Path, "/still/")
		if phoneID == "" || strings.Contains(phoneID, "/") {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		phone, err := store.GetPhone(phoneID)
		if err != nil {
			http.Error(w, "unknown phone", http.StatusNotFound)
			return
		}

		snap, err := phoneapi.New(phone.BaseURL, phone.Token).CameraSnapshot(r.Context())
		if err != nil {
			writeStillError(w, err)
			return
		}
		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("Cache-Control", "no-store")
		if snap.CapturedAtMs > 0 {
			w.Header().Set(phoneapi.CapturedAtHeader, strconv.FormatInt(snap.CapturedAtMs, 10))
		}
		w.Write(snap.JPEG)
	})
}

// writeStillError relays the phone's own refusal (status + JSON body) and
// reports a phone that can't be reached as a 504, which the frontend treats as
// retryable like any 503.
func writeStillError(w http.ResponseWriter, err error) {
	var httpErr *phoneapi.HTTPError
	switch {
	case errors.As(err, &httpErr):
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(httpErr.StatusCode)
		w.Write([]byte(httpErr.Body))
	case errors.Is(err, phoneapi.ErrUnreachable):
		http.Error(w, "phone unreachable", http.StatusGatewayTimeout)
	default:
		http.Error(w, "still proxy error", http.StatusBadGateway)
	}
}
