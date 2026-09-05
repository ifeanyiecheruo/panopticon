// Package phoneapi is the controller's HTTP client for the phone-side API
// defined in docs/implementation/phone-http-api.md.
//
// Every call here uses an explicit context timeout — Go's http.Client does
// NOT default to one, and the old prototype (see panopticon-prototype's
// QUIRKS.md, "fetch() to the phone can hang indefinitely") learned the hard
// way that a stalled-but-not-disconnected phone (weak wifi, phone busy) can
// hang a bare fetch/request forever. Timeouts here are tuned per call type,
// mirroring that prototype's own tuning: short for metadata, long for file
// downloads.
package phoneapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const (
	metadataTimeout  = 10 * time.Second
	fileTimeout      = 60 * time.Second
	thumbnailTimeout = 20 * time.Second
)

// Sentinel errors a caller can distinguish with errors.Is. HTTPError (below)
// carries the underlying status code for anything not covered here.
var (
	// ErrUnreachable means the request never got an HTTP response at all —
	// DNS failure, connection refused, or the context deadline expired
	// mid-flight. This is the "Could not reach <address> on the network"
	// case from the Add-phone flow.
	ErrUnreachable = errors.New("phone unreachable")

	// ErrUnauthorized is a 401: missing/unknown/revoked bearer token.
	ErrUnauthorized = errors.New("unauthorized")

	// ErrInvalidInvite covers POST /api/pair's 404 (unknown code) and 410
	// (expired/already used) — the "Invalid or expired invite code" case.
	ErrInvalidInvite = errors.New("invalid or expired invite code")

	// ErrEvicted is a 404 on a clip file/thumbnail download — the phone has
	// already evicted this clip from its ring buffer. Per the handoff doc
	// and the old prototype's lesson, this is a normal skip, not a failure.
	ErrEvicted = errors.New("clip evicted from phone")
)

// HTTPError is returned for any non-2xx response not covered by a sentinel
// above, carrying the status code and phone-supplied body for diagnostics.
type HTTPError struct {
	StatusCode int
	Body       string
}

func (e *HTTPError) Error() string {
	return fmt.Sprintf("phone returned HTTP %d: %s", e.StatusCode, e.Body)
}

// Client talks to one paired (or being-paired) phone.
type Client struct {
	BaseURL string // normalized, e.g. "http://192.168.1.87", no trailing slash
	Token   string // bearer token; empty is fine for the pre-pairing Pair() call
	HTTP    *http.Client
}

// New builds a client for a phone at baseURL (already normalized via
// NormalizeAddress) with the given bearer token.
func New(baseURL, token string) *Client {
	return &Client{
		BaseURL: baseURL,
		Token:   token,
		// No blanket Timeout here — each call below sets its own deadline via
		// context, since different call types warrant very different budgets
		// (a metadata GET vs. a multi-MB clip download).
		HTTP: &http.Client{},
	}
}

// NormalizeAddress turns a user-entered "phone address" (bare IP/hostname,
// optionally with a scheme and/or port) into a normalized "scheme://host"
// base URL with no path. Mirrors the controller-ux-mock.html's own
// parseInviteUrl behavior: an address with no scheme defaults to plain
// http://, not https:// — the design doc's own example URL happens to be
// https, but the mock (ground truth for exact behavior per the handoff doc)
// treats a bare address as http, and this codebase follows the mock.
func NormalizeAddress(addr string) string {
	addr = strings.TrimSpace(addr)
	if addr == "" {
		return ""
	}
	if !strings.Contains(addr, "://") {
		addr = "http://" + addr
	}
	u, err := url.Parse(addr)
	if err != nil || u.Host == "" {
		return addr
	}
	return u.Scheme + "://" + u.Host
}

// ParseInviteURL extracts (address, inviteCode) from a full invite URL, e.g.
// "https://192.168.1.87/api/connect?invite=XYZF-EBDO-ORMS". Returns ok=false
// if raw doesn't look like an invite URL (no "invite=" query param) — a bare
// short code has no address embedded and must be entered separately, per
// HANDOFF-controller-ux.md's Add-phone flow.
func ParseInviteURL(raw string) (address, code string, ok bool) {
	str := strings.TrimSpace(raw)
	if str == "" || !strings.Contains(strings.ToLower(str), "invite=") {
		return "", "", false
	}
	if !strings.Contains(str, "://") {
		str = "http://" + str
	}
	u, err := url.Parse(str)
	if err != nil || u.Host == "" {
		return "", "", false
	}
	invite := u.Query().Get("invite")
	if invite == "" {
		return "", "", false
	}
	return u.Host, invite, true
}

func (c *Client) request(ctx context.Context, method, path string, query url.Values, body any) (*http.Response, error) {
	full := c.BaseURL + path
	if len(query) > 0 {
		full += "?" + query.Encode()
	}

	var bodyReader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, fmt.Errorf("encode request body: %w", err)
		}
		bodyReader = bytes.NewReader(b)
	}

	req, err := http.NewRequestWithContext(ctx, method, full, bodyReader)
	if err != nil {
		return nil, fmt.Errorf("build request: %w", err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if c.Token != "" {
		req.Header.Set("Authorization", "Bearer "+c.Token)
	}

	resp, err := c.HTTP.Do(req)
	if err != nil {
		// Any transport-level failure (dial refused, DNS, TLS, or the
		// context deadline firing mid-request) is exactly the "can't reach
		// this phone at all" case — collapse it to one sentinel rather than
		// exposing net/http's many underlying error shapes to callers.
		return nil, fmt.Errorf("%w: %v", ErrUnreachable, err)
	}
	return resp, nil
}

// doJSON performs a request and decodes a JSON response body into out (if
// non-nil), translating well-known status codes to sentinel errors.
func (c *Client) doJSON(ctx context.Context, method, path string, query url.Values, body any, out any) error {
	resp, err := c.request(ctx, method, path, query, body)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized {
		return ErrUnauthorized
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return &HTTPError{StatusCode: resp.StatusCode, Body: string(b)}
	}
	if out == nil {
		return nil
	}
	if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
		return fmt.Errorf("decode response: %w", err)
	}
	return nil
}

// ---- Pairing ----

type PairRequest struct {
	PublicKey string `json:"publicKey"`
	Name      string `json:"name"`
	Kind      string `json:"kind"`
}

type PairResponse struct {
	ControllerID string `json:"controllerId"`
	Token        string `json:"token"`
	Phone        struct {
		PhoneID string `json:"phoneId"`
		Name    string `json:"name"`
	} `json:"phone"`
}

// Pair redeems an invite code against POST /api/pair. Distinguishes an
// unreachable address (ErrUnreachable) from a bad invite (ErrInvalidInvite,
// covering both 404 unknown-code and 410 expired/used) so the Add-phone flow
// can show the two distinct error messages the handoff doc calls for.
func (c *Client) Pair(ctx context.Context, inviteCode string, req PairRequest) (PairResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, metadataTimeout)
	defer cancel()

	var out PairResponse
	err := c.doJSON(ctx, http.MethodPost, "/api/pair", url.Values{"invite": {inviteCode}}, req, &out)
	if err != nil {
		var httpErr *HTTPError
		if errors.As(err, &httpErr) && (httpErr.StatusCode == http.StatusNotFound || httpErr.StatusCode == http.StatusGone) {
			return PairResponse{}, ErrInvalidInvite
		}
		return PairResponse{}, err
	}
	return out, nil
}

// Unpair calls DELETE /api/pair (self-revoke).
func (c *Client) Unpair(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, metadataTimeout)
	defer cancel()
	return c.doJSON(ctx, http.MethodDelete, "/api/pair", nil, nil, nil)
}

// ---- Device identity & status ----

type DeviceInfo struct {
	Manufacturer string `json:"manufacturer"`
	Model        string `json:"model"`
	Device       string `json:"device"`
}

func (c *Client) Device(ctx context.Context) (DeviceInfo, error) {
	ctx, cancel := context.WithTimeout(ctx, metadataTimeout)
	defer cancel()
	var out DeviceInfo
	err := c.doJSON(ctx, http.MethodGet, "/api/device", nil, nil, &out)
	return out, err
}

type BuildInfo struct {
	AppVersionName string `json:"appVersionName"`
	AppVersionCode int    `json:"appVersionCode"`
	BuildType      string `json:"buildType"`
	GitSha         string `json:"gitSha"`
}

func (c *Client) BuildInfoCall(ctx context.Context) (BuildInfo, error) {
	ctx, cancel := context.WithTimeout(ctx, metadataTimeout)
	defer cancel()
	var out BuildInfo
	err := c.doJSON(ctx, http.MethodGet, "/api/build-info", nil, nil, &out)
	return out, err
}

type Status struct {
	Mode             string `json:"mode"`
	Status           string `json:"status"`
	CameraHealthy    bool   `json:"cameraHealthy"`
	LiveViewers      int    `json:"liveViewers"`
	StorageUsedBytes int64  `json:"storageUsedBytes"`
	StorageCapBytes  int64  `json:"storageCapBytes"`
	BatteryPercent   int    `json:"batteryPercent"`
	Charging         bool   `json:"charging"`
	ServerTimeMs     int64  `json:"serverTimeMs"`
}

func (c *Client) Status(ctx context.Context) (Status, error) {
	ctx, cancel := context.WithTimeout(ctx, metadataTimeout)
	defer cancel()
	var out Status
	err := c.doJSON(ctx, http.MethodGet, "/api/status", nil, nil, &out)
	return out, err
}

type Config struct {
	DeviceName         string `json:"deviceName"`
	MotionSensitivity  string `json:"motionSensitivity"`
	StorageCapBytes    int64  `json:"storageCapBytes"`
	RingBufferMaxAgeMs int64  `json:"ringBufferMaxAgeMs"`
	RotationDegrees    int    `json:"rotationDegrees"`
}

func (c *Client) Config(ctx context.Context) (Config, error) {
	ctx, cancel := context.WithTimeout(ctx, metadataTimeout)
	defer cancel()
	var out Config
	err := c.doJSON(ctx, http.MethodGet, "/api/config", nil, nil, &out)
	return out, err
}

// ---- Clips (sync) ----

type ClipMeta struct {
	Filename    string `json:"filename"`
	URL         string `json:"url"`
	CreatedAtMs int64  `json:"createdAtMs"`
	DurationMs  int64  `json:"durationMs"`
	EndMs       int64  `json:"endMs"`
	SizeBytes   int64  `json:"sizeBytes"`
	Width       int    `json:"width"`
	Height      int    `json:"height"`
}

type ClipsResponse struct {
	Clips []ClipMeta `json:"clips"`
}

// Clips performs the delta-pull: GET /api/clips?since=<sinceMs>.
func (c *Client) Clips(ctx context.Context, sinceMs int64) (ClipsResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, metadataTimeout)
	defer cancel()
	var out ClipsResponse
	err := c.doJSON(ctx, http.MethodGet, "/api/clips", url.Values{"since": {strconv.FormatInt(sinceMs, 10)}}, nil, &out)
	return out, err
}

// downloadBinary is the shared implementation for clip file/thumbnail
// downloads: on success the caller owns the returned ReadCloser and MUST
// close it. A 404 becomes ErrEvicted — per phone-http-api.md ("404 if
// evicted") and the old prototype's lesson, this is a normal skip, not a
// retryable failure.
func (c *Client) downloadBinary(ctx context.Context, path string, timeout time.Duration) (io.ReadCloser, error) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	resp, err := c.request(ctx, http.MethodGet, path, nil, nil)
	if err != nil {
		cancel()
		return nil, err
	}
	if resp.StatusCode == http.StatusNotFound {
		resp.Body.Close()
		cancel()
		return nil, ErrEvicted
	}
	if resp.StatusCode == http.StatusUnauthorized {
		resp.Body.Close()
		cancel()
		return nil, ErrUnauthorized
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		resp.Body.Close()
		cancel()
		return nil, &HTTPError{StatusCode: resp.StatusCode, Body: string(b)}
	}
	// Wrap so cancel() runs once the caller finishes reading/closes the body
	// — otherwise the context (and its timer) would leak for the life of the
	// download.
	return &cancelOnCloseReader{ReadCloser: resp.Body, cancel: cancel}, nil
}

type cancelOnCloseReader struct {
	io.ReadCloser
	cancel context.CancelFunc
}

func (r *cancelOnCloseReader) Close() error {
	defer r.cancel()
	return r.ReadCloser.Close()
}

// DownloadClipFile fetches GET /api/clips/:filename/file.
func (c *Client) DownloadClipFile(ctx context.Context, filename string) (io.ReadCloser, error) {
	return c.downloadBinary(ctx, "/api/clips/"+url.PathEscape(filename)+"/file", fileTimeout)
}

// DownloadThumbnail fetches GET /api/clips/:filename/thumbnail.
func (c *Client) DownloadThumbnail(ctx context.Context, filename string) (io.ReadCloser, error) {
	return c.downloadBinary(ctx, "/api/clips/"+url.PathEscape(filename)+"/thumbnail", thumbnailTimeout)
}
