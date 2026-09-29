package dbstore

import (
	"context"
	"path/filepath"
	"strings"

	"panopticon-controller/internal/dbstore/queries"
)

// archiveDirName is the archive directory's name (appdirs.Dirs.ArchiveDir);
// every absolute archive path an older build stored runs through it.
const archiveDirName = "panopticon-archive"

// RelativizeArchivePaths rewrites segment file/thumbnail paths stored absolute
// by older builds to the form stored now: relative to the archive directory
// (appdirs.ArchiveRelPath), so moving the archive - as the move to a fixed
// app-data directory does - needs no rewrite. Idempotent: once converted, no
// row matches. Returns how many rows it rewrote.
//
// Rows of purged clips point at files that are legitimately gone; they're
// rewritten all the same, since only the path's shape changes.
func (s *Store) RelativizeArchivePaths() (int, error) {
	ctx := context.Background()
	rows, err := s.q.ListSegmentsWithAbsoluteArchivePaths(ctx)
	if err != nil || len(rows) == 0 {
		return 0, err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	q := s.q.WithTx(tx)
	for _, r := range rows {
		err := q.SetSegmentPaths(ctx, queries.SetSegmentPathsParams{
			LocalPath:     archiveRelative(r.LocalPath),
			ThumbnailPath: archiveRelative(r.ThumbnailPath),
			PhoneID:       r.PhoneID,
			Filename:      r.Filename,
		})
		if err != nil {
			return 0, err
		}
	}
	return len(rows), tx.Commit()
}

// archiveRelative returns the part of an absolute archive path after the
// archive directory, slash-separated; anything else is returned unchanged.
func archiveRelative(p string) string {
	if !filepath.IsAbs(p) {
		return p
	}
	slashed := filepath.ToSlash(p)
	marker := "/" + archiveDirName + "/"
	i := strings.LastIndex(slashed, marker)
	if i < 0 {
		return p
	}
	return slashed[i+len(marker):]
}
