package phoneapi

import (
	"context"
	"io"
	"net/http"
	"strconv"
	"time"
)

// snapshotTimeout covers the phone's own wait for the next camera frame (up to
// ~3s while its pipeline restarts) plus the transfer of a ~100KB JPEG.
const snapshotTimeout = 10 * time.Second

// CapturedAtHeader carries the epoch ms a still's frame was rendered at.
const CapturedAtHeader = "X-Captured-At-Ms"

// Snapshot is one GET /api/camera/snapshot still.
type Snapshot struct {
	JPEG []byte
	// CapturedAtMs is the phone-clock epoch ms of the frame; 0 if the phone
	// didn't say.
	CapturedAtMs int64
}

// CameraSnapshot fetches a JPEG still of what a recording phone is encoding,
// produced on demand. 409 (not recording) and 503 (camera restarting or phone
// overheated; the body carries retryAfterMs when worth retrying) surface as
// an *HTTPError so the caller can pass them through.
func (c *Client) CameraSnapshot(ctx context.Context) (Snapshot, error) {
	ctx, cancel := context.WithTimeout(ctx, snapshotTimeout)
	defer cancel()
	resp, err := c.request(ctx, http.MethodGet, "/api/camera/snapshot", nil, nil)
	if err != nil {
		return Snapshot{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusUnauthorized {
		return Snapshot{}, ErrUnauthorized
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return Snapshot{}, &HTTPError{StatusCode: resp.StatusCode, Body: string(b)}
	}
	jpeg, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return Snapshot{}, err
	}
	at, _ := strconv.ParseInt(resp.Header.Get(CapturedAtHeader), 10, 64)
	return Snapshot{JPEG: jpeg, CapturedAtMs: at}, nil
}
