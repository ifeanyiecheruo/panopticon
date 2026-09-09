// Package syncer runs the background per-phone clip sync loop: polling
// GET /api/clips?since=<cursor>, downloading new clip files + thumbnails
// into the local archive, and advancing the sync cursor only after each
// clip is safely persisted (crash-safe, idempotent on filename) — per
// docs/design/components/controller-sync.md and the old prototype's SyncService lesson
// (panopticon-prototype/ARCHITECTURE.md).
//
// One phone being slow or unreachable must never wedge the others: each
// paired phone gets its own goroutine and its own timeouts (via phoneapi),
// so a dead phone just sits there failing quietly on its own schedule.
package syncer

import (
	"context"
	"errors"
	"io"
	"log"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"panopticon-controller/internal/appdirs"
	"panopticon-controller/internal/dbstore"
	"panopticon-controller/internal/phoneapi"
)

// Manager supervises one sync goroutine per paired phone, starting/stopping
// them as phones are added/removed, without needing a restart.
type Manager struct {
	store    *dbstore.Store
	dirs     appdirs.Dirs
	interval time.Duration
	gapMs    int64 // segments this close (createdAtMs - prev.endMs) join one clip

	mu       sync.Mutex
	cancels  map[string]context.CancelFunc // phoneID -> stop this phone's goroutine
	rootCtx  context.Context
	rootStop context.CancelFunc
	wg       sync.WaitGroup
}

// NewManager builds a Manager. interval is how often each phone is polled;
// gapMs is the contiguity threshold for grouping downloaded segments into
// clips (see dbstore.GroupingGapMs). Both are constructor arguments so
// they're easy to tune without touching the loop logic.
func NewManager(store *dbstore.Store, dirs appdirs.Dirs, interval time.Duration, gapMs int64) *Manager {
	return &Manager{
		store:    store,
		dirs:     dirs,
		interval: interval,
		gapMs:    gapMs,
		cancels:  make(map[string]context.CancelFunc),
	}
}

// Start launches the supervisor: an immediate reconciliation of DB phones
// against running goroutines, repeated every few seconds so a newly paired
// phone gets picked up without a restart.
func (m *Manager) Start() {
	m.rootCtx, m.rootStop = context.WithCancel(context.Background())
	m.wg.Add(1)
	go func() {
		defer m.wg.Done()
		m.reconcile()
		ticker := time.NewTicker(5 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-m.rootCtx.Done():
				return
			case <-ticker.C:
				m.reconcile()
			}
		}
	}()
}

// Stop cancels every running per-phone loop and waits for them to exit.
func (m *Manager) Stop() {
	if m.rootStop != nil {
		m.rootStop()
	}
	m.wg.Wait()
}

// reconcile ensures exactly one goroutine is running per phone currently in
// the DB (handles newly-paired phones; an unpaired phone's goroutine is left
// to notice its phone record is gone on its next tick and exit — see
// syncOnePhone).
func (m *Manager) reconcile() {
	phones, err := m.store.ListPhones()
	if err != nil {
		log.Printf("syncer: list phones: %v", err)
		return
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	seen := make(map[string]bool, len(phones))
	for _, p := range phones {
		seen[p.ID] = true
		if _, ok := m.cancels[p.ID]; ok {
			continue // already running
		}
		ctx, cancel := context.WithCancel(m.rootCtx)
		m.cancels[p.ID] = cancel
		m.wg.Add(1)
		go func(phoneID string) {
			defer m.wg.Done()
			m.syncLoop(ctx, phoneID)
		}(p.ID)
	}
	// Stop loops for phones no longer paired (unpair happened).
	for id, cancel := range m.cancels {
		if !seen[id] {
			cancel()
			delete(m.cancels, id)
		}
	}
}

// syncLoop is the per-phone poll loop. Runs one sync pass immediately, then
// on m.interval, until ctx is cancelled (root Stop(), or this phone was
// unpaired and reconcile() cancelled just this one).
func (m *Manager) syncLoop(ctx context.Context, phoneID string) {
	m.runOnce(ctx, phoneID)
	ticker := time.NewTicker(m.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			m.runOnce(ctx, phoneID)
		}
	}
}

// runOnce performs one delta-pull-and-download pass for one phone. Never
// panics or blocks other phones on failure — errors are logged and the loop
// just tries again next tick.
func (m *Manager) runOnce(ctx context.Context, phoneID string) {
	phone, err := m.store.GetPhone(phoneID)
	if err != nil {
		if errors.Is(err, dbstore.ErrNotFound) {
			return // unpaired since this tick was scheduled; reconcile will stop us
		}
		log.Printf("syncer[%s]: load phone: %v", phoneID, err)
		return
	}

	client := phoneapi.New(phone.BaseURL, phone.Token)
	resp, err := client.Segments(ctx, phone.SyncCursorMs)
	if err != nil {
		// Unreachable/auth-failed/etc: this is exactly the "phone can be
		// flaky, don't let it wedge anything else" case — just log and let
		// the next tick retry. Fleet/Phone-detail surfaces reachability
		// live via its own GET /api/status call, not via this loop's state.
		log.Printf("syncer[%s]: poll segments: %v", phoneID, err)
		return
	}
	if err := m.store.UpdatePhoneLastSeen(phoneID, dbstore.NowMs()); err != nil {
		log.Printf("syncer[%s]: update last_seen: %v", phoneID, err)
	}

	segments := resp.Segments
	sort.Slice(segments, func(i, j int) bool { return segments[i].CreatedAtMs < segments[j].CreatedAtMs })

	archiveDir, err := m.dirs.PhoneArchiveDir(phoneID)
	if err != nil {
		log.Printf("syncer[%s]: archive dir: %v", phoneID, err)
		return
	}

	cursor := phone.SyncCursorMs
	for _, seg := range segments {
		exists, err := m.store.SegmentExists(phoneID, seg.Filename)
		if err != nil {
			log.Printf("syncer[%s]: check existing segment %s: %v", phoneID, seg.Filename, err)
			break // stop this tick; retry from here next time
		}
		if exists {
			// Already indexed (possibly since trashed/purged by the user) —
			// never re-download. Safe to advance past it.
			cursor = maxInt64(cursor, seg.CreatedAtMs)
			continue
		}

		localPath := filepath.Join(archiveDir, seg.Filename)
		thumbPath := filepath.Join(archiveDir, seg.Filename+".jpg")

		if err := downloadToFile(ctx, client.DownloadSegmentFile, seg.Filename, localPath); err != nil {
			if errors.Is(err, phoneapi.ErrEvicted) {
				// Phone already evicted this segment from its ring buffer
				// between listing it and us fetching it — a normal race,
				// not an error (see docs/design/http-api.md and the old
				// prototype's identical lesson). Never retry it: advance
				// past it and move on.
				log.Printf("syncer[%s]: segment %s already evicted, skipping", phoneID, seg.Filename)
				cursor = maxInt64(cursor, seg.CreatedAtMs)
				continue
			}
			// Any other failure (network hiccup, disk full, ...): stop this
			// tick here so the next tick retries this same segment, rather
			// than advancing the cursor past a segment we never actually got.
			log.Printf("syncer[%s]: download segment %s: %v", phoneID, seg.Filename, err)
			break
		}

		// Thumbnail is best-effort: a missing thumbnail shouldn't block
		// archiving the segment itself.
		if err := downloadToFile(ctx, client.DownloadSegmentThumbnail, seg.Filename, thumbPath); err != nil {
			log.Printf("syncer[%s]: download thumbnail %s: %v", phoneID, seg.Filename, err)
			thumbPath = ""
		}

		endMs := seg.EndMs
		if endMs == 0 {
			endMs = seg.CreatedAtMs + seg.DurationMs
		}

		// Assign to a clip: extend the phone's open (active, most-recently-
		// ended) clip if this segment starts within gapMs of its end,
		// otherwise open a new one.
		clipID, err := m.assignClip(phoneID, seg, endMs)
		if err != nil {
			log.Printf("syncer[%s]: assign clip for %s: %v", phoneID, seg.Filename, err)
			break
		}

		err = m.store.UpsertSegment(dbstore.Segment{
			PhoneID:       phoneID,
			Filename:      seg.Filename,
			ClipID:        clipID,
			LocalPath:     localPath,
			ThumbnailPath: thumbPath,
			CreatedAtMs:   seg.CreatedAtMs,
			DurationMs:    seg.DurationMs,
			EndMs:         endMs,
			SizeBytes:     seg.SizeBytes,
			Width:         seg.Width,
			Height:        seg.Height,
		})
		if err != nil {
			log.Printf("syncer[%s]: index segment %s: %v", phoneID, seg.Filename, err)
			break
		}

		// Only now — file downloaded, clip assigned, segment row inserted —
		// is it safe to move the cursor. A crash right here re-pulls this
		// same segment next run; UpsertSegment's ON CONFLICT DO NOTHING makes
		// that idempotent (the clip may end up over-counted by one on that
		// rare path — acceptable; the counts are display sugar).
		cursor = maxInt64(cursor, seg.CreatedAtMs)
		if err := m.store.AdvanceSyncCursor(phoneID, cursor); err != nil {
			log.Printf("syncer[%s]: advance cursor: %v", phoneID, err)
		}
	}
}

// assignClip returns the clip id a freshly-downloaded segment belongs to,
// extending the phone's current open clip when the segment is contiguous with
// it (gap <= m.gapMs) or creating a new active clip otherwise.
func (m *Manager) assignClip(phoneID string, seg phoneapi.SegmentMeta, endMs int64) (string, error) {
	open, err := m.store.GetOpenClip(phoneID)
	if err == nil && seg.CreatedAtMs-open.EndedAtMs <= m.gapMs {
		if err := m.store.ExtendClip(open.ID, endMs, seg.SizeBytes); err != nil {
			return "", err
		}
		return open.ID, nil
	}
	if err != nil && !errors.Is(err, dbstore.ErrNotFound) {
		return "", err
	}
	clip := dbstore.Clip{
		ID:           dbstore.NewClipID(),
		PhoneID:      phoneID,
		StartedAtMs:  seg.CreatedAtMs,
		EndedAtMs:    endMs,
		SegmentCount: 1,
		SizeBytes:    seg.SizeBytes,
		State:        dbstore.ClipActive,
		CreatedAtMs:  dbstore.NowMs(),
	}
	if err := m.store.InsertClip(clip); err != nil {
		return "", err
	}
	return clip.ID, nil
}

// downloadToFile streams a phoneapi binary download to disk. Written to a
// ".part" temp file and renamed into place on success, so a crash mid-write
// never leaves a truncated file at the final path for the gallery to trip
// over.
func downloadToFile(ctx context.Context, fetch func(context.Context, string) (io.ReadCloser, error), filename, destPath string) error {
	rc, err := fetch(ctx, filename)
	if err != nil {
		return err
	}
	defer rc.Close()

	tmpPath := destPath + ".part"
	f, err := os.Create(tmpPath)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, rc); err != nil {
		f.Close()
		os.Remove(tmpPath)
		return err
	}
	if err := f.Close(); err != nil {
		os.Remove(tmpPath)
		return err
	}
	return os.Rename(tmpPath, destPath)
}

func maxInt64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}
