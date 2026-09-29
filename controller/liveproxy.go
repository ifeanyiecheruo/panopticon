package main

import (
	"errors"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"panopticon-controller/internal/dbstore"
	"panopticon-controller/internal/phoneapi"
)

// liveProxyHandler serves the webview's <video>/hls.js a same-origin view of a
// phone's live LL-HLS feed at:
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
// LL-HLS passthrough: a playlist fetch forwards `_HLS_msn`/`_HLS_part`
// verbatim (the phone blocks the reload until that part exists, so this proxy
// call just rides that same wait — no extra long-poll logic needed here); a
// segment fetch forwards any `Range` header (an LL-HLS part/preload-hint byte
// range against a segment still being encoded) and relays whatever status
// (200/206/416) and Content-Range the phone returned instead of always
// assuming 200.
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
			msn, part := parseHlsReloadParams(r.URL.Query())
			body, err := client.LivePlaylist(r.Context(), msn, part)
			if err != nil {
				writeProxyError(w, err)
				return
			}
			w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
			w.Header().Set("Cache-Control", "no-store")
			w.Write(body)

		case strings.HasPrefix(file, "live-") && strings.HasSuffix(file, ".ts"):
			result, err := client.LiveSegment(r.Context(), file, r.Header.Get("Range"))
			if err != nil {
				writeProxyError(w, err)
				return
			}
			defer result.Body.Close()
			w.Header().Set("Content-Type", "video/mp2t")
			w.Header().Set("Cache-Control", "no-store")
			if result.ContentRange != "" {
				w.Header().Set("Content-Range", result.ContentRange)
			}
			w.WriteHeader(result.StatusCode)
			io.Copy(w, result.Body)

		default:
			http.Error(w, "not found", http.StatusNotFound)
		}
	})
}

// parseHlsReloadParams pulls LL-HLS's `_HLS_msn`/`_HLS_part` query params off
// an incoming playlist request. msn == nil means "not a blocking reload" —
// matches phoneapi.Client.LivePlaylist's contract. A malformed `_HLS_msn`
// (non-numeric) is treated the same as absent, since a blocking reload is
// purely a latency optimization: worst case this falls back to serving
// whatever the playlist currently says, same as a plain reload.
func parseHlsReloadParams(q url.Values) (msn, part *int) {
	msnStr := q.Get("_HLS_msn")
	if msnStr == "" {
		return nil, nil
	}
	n, err := strconv.Atoi(msnStr)
	if err != nil {
		return nil, nil
	}
	msn = &n
	if partStr := q.Get("_HLS_part"); partStr != "" {
		if p, err := strconv.Atoi(partStr); err == nil {
			part = &p
		}
	}
	return msn, part
}

// writeProxyError collapses phoneapi's error shapes to a status hls.js can act
// on: an unreachable phone or an evicted/absent segment is a 404 (hls.js
// retries a fragment 404 quickly); a 416 (LL-HLS part not muxed yet) passes
// straight through; anything else is a 502.
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
