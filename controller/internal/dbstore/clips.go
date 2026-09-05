package dbstore

import "database/sql"

// ClipState mirrors the three-state lifecycle from HANDOFF-controller-ux.md:
// active (visible in Gallery) -> trashed (visible in Trash, still on disk) ->
// purged (tombstone only, file gone). The tombstone itself is only dropped
// once eviction from the phone's ring buffer is confirmed — that probe loop
// is out of scope for this slice (see README.md), so purged rows just
// accumulate here for now.
type ClipState string

const (
	ClipActive  ClipState = "active"
	ClipTrashed ClipState = "trashed"
	ClipPurged  ClipState = "purged"
)

type Clip struct {
	PhoneID       string
	Filename      string
	State         ClipState
	LocalPath     string
	ThumbnailPath string
	CreatedAtMs   int64
	DurationMs    int64
	SizeBytes     int64
	Width         int
	Height        int
}

// UpsertClip inserts a newly-synced clip, or is a no-op if (phone_id,
// filename) already exists — the sync loop calls this once per clip
// discovered via GET /api/clips, and dedupes on filename per
// phone-http-api.md.
func (s *Store) UpsertClip(c Clip) error {
	_, err := s.db.Exec(`
		INSERT INTO clips (phone_id, filename, state, local_path, thumbnail_path, created_at_ms, duration_ms, size_bytes, width, height)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(phone_id, filename) DO NOTHING`,
		c.PhoneID, c.Filename, c.State, c.LocalPath, c.ThumbnailPath, c.CreatedAtMs, c.DurationMs, c.SizeBytes, c.Width, c.Height)
	return err
}

// ClipExists reports whether (phoneID, filename) is already indexed —
// used by the sync loop to skip re-downloading a clip it already has
// (including one the user has since trashed/purged; per the data model,
// resync must never resurrect a purged clip's file).
func (s *Store) ClipExists(phoneID, filename string) (bool, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(1) FROM clips WHERE phone_id = ? AND filename = ?`, phoneID, filename).Scan(&n)
	return n > 0, err
}

// ListClips returns clips in a given state (or all states if state == "")
// optionally filtered to one phone (phoneID == "" means every phone),
// newest first.
func (s *Store) ListClips(phoneID string, state ClipState) ([]Clip, error) {
	query := `SELECT phone_id, filename, state, local_path, thumbnail_path, created_at_ms, duration_ms, size_bytes, width, height FROM clips WHERE 1=1`
	var args []any
	if phoneID != "" {
		query += ` AND phone_id = ?`
		args = append(args, phoneID)
	}
	if state != "" {
		query += ` AND state = ?`
		args = append(args, state)
	}
	query += ` ORDER BY created_at_ms DESC`

	rows, err := s.db.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []Clip
	for rows.Next() {
		var c Clip
		if err := rows.Scan(&c.PhoneID, &c.Filename, &c.State, &c.LocalPath, &c.ThumbnailPath, &c.CreatedAtMs, &c.DurationMs, &c.SizeBytes, &c.Width, &c.Height); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// GetClip looks up a single clip by its composite key.
func (s *Store) GetClip(phoneID, filename string) (Clip, error) {
	var c Clip
	err := s.db.QueryRow(`
		SELECT phone_id, filename, state, local_path, thumbnail_path, created_at_ms, duration_ms, size_bytes, width, height
		FROM clips WHERE phone_id = ? AND filename = ?`, phoneID, filename).
		Scan(&c.PhoneID, &c.Filename, &c.State, &c.LocalPath, &c.ThumbnailPath, &c.CreatedAtMs, &c.DurationMs, &c.SizeBytes, &c.Width, &c.Height)
	if err == sql.ErrNoRows {
		return Clip{}, ErrNotFound
	}
	return c, err
}

// SetClipState transitions a clip's state (Trash/Restore/Delete per the
// Trash bin action table in HANDOFF-controller-ux.md). Callers are
// responsible for actually removing the on-disk file before transitioning
// to purged; this method only updates the DB row.
func (s *Store) SetClipState(phoneID, filename string, state ClipState) error {
	_, err := s.db.Exec(`UPDATE clips SET state = ? WHERE phone_id = ? AND filename = ?`, state, phoneID, filename)
	return err
}

// DiskUsageBytes sums size_bytes across active+trashed clips (purged clips
// have no file left to count) — the aggregate disk-usage stat on Fleet.
func (s *Store) DiskUsageBytes(phoneID string) (int64, error) {
	query := `SELECT COALESCE(SUM(size_bytes),0) FROM clips WHERE state IN ('active','trashed')`
	var args []any
	if phoneID != "" {
		query += ` AND phone_id = ?`
		args = append(args, phoneID)
	}
	var total int64
	err := s.db.QueryRow(query, args...).Scan(&total)
	return total, err
}
