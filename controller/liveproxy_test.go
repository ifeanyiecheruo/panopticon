package main

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"panopticon-controller/internal/appdirs"
	"panopticon-controller/internal/dbstore"
)

// newLiveProxyTestStore mirrors internal/integrationtest's newTestStore (a
// fresh on-disk SQLite store in a temp dir) - liveProxyHandler needs a real
// *dbstore.Store to resolve phoneID -> phoneapi.Client from.
func newLiveProxyTestStore(t *testing.T, phoneID, baseURL, token string) *dbstore.Store {
	t.Helper()
	dir := t.TempDir()
	dirs := appdirs.Dirs{Root: dir, DataDir: dir, ArchiveDir: dir}
	store, err := dbstore.Open(dirs.DBPath())
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { store.Close() })
	if err := store.InsertPhone(dbstore.Phone{ID: phoneID, Name: "Test Phone", BaseURL: baseURL, Token: token}); err != nil {
		t.Fatalf("insert phone: %v", err)
	}
	return store
}

// TestLiveProxy_Playlist verifies a playlist fetch carrying `_HLS_msn`/
// `_HLS_part` reaches the phone as query params unchanged - what lets the
// phone's own long-poll do the waiting instead of this proxy polling on a
// fixed interval - and that a normal reload (no `_HLS_msn`) doesn't invent
// blocking-reload params.
func TestLiveProxy_Playlist(t *testing.T) {
	tests := []struct {
		name      string
		reqQuery  string // appended to the proxy request's path, including "?"
		wantQuery string // query string expected to reach the phone
	}{
		{name: "forwards blocking reload params", reqQuery: "?_HLS_msn=5&_HLS_part=2", wantQuery: "_HLS_msn=5&_HLS_part=2"},
		{name: "plain reload has no blocking params", reqQuery: "", wantQuery: ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var gotQuery string
			phone := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path != "/live/live.m3u8" {
					t.Fatalf("unexpected path: %s", r.URL.Path)
				}
				gotQuery = r.URL.RawQuery
				w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
				w.Write([]byte("#EXTM3U\n"))
			}))
			defer phone.Close()

			store := newLiveProxyTestStore(t, "phone1", phone.URL, "tok")
			handler := liveProxyHandler(store)

			req := httptest.NewRequest(http.MethodGet, "/live/phone1/live.m3u8"+tt.reqQuery, nil)
			rec := httptest.NewRecorder()
			handler.ServeHTTP(rec, req)

			if rec.Code != http.StatusOK {
				t.Fatalf("status = %d, want 200 (body: %s)", rec.Code, rec.Body.String())
			}
			if gotQuery != tt.wantQuery {
				t.Errorf("phone saw query %q, want %q", gotQuery, tt.wantQuery)
			}
		})
	}
}

// TestLiveProxy_SegmentForwardsRangeAndRelaysPartialContent verifies an
// LL-HLS part/preload-hint fetch's Range header reaches the phone, and the
// phone's 206 + Content-Range come back unchanged - hls.js needs the real
// status/header to treat this as a partial fetch rather than a full segment.
func TestLiveProxy_SegmentForwardsRangeAndRelaysPartialContent(t *testing.T) {
	var gotRange string
	phone := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotRange = r.Header.Get("Range")
		w.Header().Set("Content-Range", "bytes 0-99/*")
		w.WriteHeader(http.StatusPartialContent)
		w.Write(make([]byte, 100))
	}))
	defer phone.Close()

	store := newLiveProxyTestStore(t, "phone1", phone.URL, "tok")
	handler := liveProxyHandler(store)

	req := httptest.NewRequest(http.MethodGet, "/live/phone1/live-3.ts", nil)
	req.Header.Set("Range", "bytes=0-99")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if gotRange != "bytes=0-99" {
		t.Errorf("phone saw Range %q, want bytes=0-99", gotRange)
	}
	if rec.Code != http.StatusPartialContent {
		t.Fatalf("status = %d, want 206", rec.Code)
	}
	if got := rec.Header().Get("Content-Range"); got != "bytes 0-99/*" {
		t.Errorf("Content-Range = %q, want bytes 0-99/*", got)
	}
	if rec.Body.Len() != 100 {
		t.Errorf("body length = %d, want 100", rec.Body.Len())
	}
}

// TestLiveProxy_SegmentErrorStatusPassesThrough verifies the proxy relays
// the phone's status as-is rather than collapsing every non-2xx to one
// generic error: a fully-evicted segment (phone 404s it) stays a plain 404 -
// hls.js retries a fragment 404 quickly - and a part/preload-hint byte range
// the phone hasn't muxed yet (416) passes straight through so the client can
// distinguish "come back in a moment" from "gone for good".
func TestLiveProxy_SegmentErrorStatusPassesThrough(t *testing.T) {
	tests := []struct {
		name        string
		reqRange    string // Range header sent to the proxy; "" = none
		phoneStatus int
	}{
		{name: "evicted segment is 404", phoneStatus: http.StatusNotFound},
		{name: "range not yet available is 416", reqRange: "bytes=500-599", phoneStatus: http.StatusRequestedRangeNotSatisfiable},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			phone := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(tt.phoneStatus)
			}))
			defer phone.Close()

			store := newLiveProxyTestStore(t, "phone1", phone.URL, "tok")
			handler := liveProxyHandler(store)

			req := httptest.NewRequest(http.MethodGet, "/live/phone1/live-3.ts", nil)
			if tt.reqRange != "" {
				req.Header.Set("Range", tt.reqRange)
			}
			rec := httptest.NewRecorder()
			handler.ServeHTTP(rec, req)

			if rec.Code != tt.phoneStatus {
				t.Fatalf("status = %d, want %d", rec.Code, tt.phoneStatus)
			}
		})
	}
}
