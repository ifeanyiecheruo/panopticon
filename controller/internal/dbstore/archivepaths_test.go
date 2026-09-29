package dbstore

import (
	"path/filepath"
	"testing"
)

// TestRelativizeArchivePaths: absolute archive paths from older builds become
// archive-relative (so the archive can move), everything else is untouched,
// and a second pass finds nothing to do.
func TestRelativizeArchivePaths(t *testing.T) {
	store := newTestStore(t)
	oldArchive := filepath.Join(t.TempDir(), "panopticon-archive")
	segs := []Segment{
		{PhoneID: "ph_1", Filename: "a.mp4", ClipID: "c1",
			LocalPath:     filepath.Join(oldArchive, "ph_1", "a.mp4"),
			ThumbnailPath: filepath.Join(oldArchive, "ph_1", "a.mp4.jpg")},
		{PhoneID: "ph_1", Filename: "b.mp4", ClipID: "c1",
			LocalPath: filepath.Join(oldArchive, "ph_1", "b.mp4")}, // no thumbnail
		{PhoneID: "ph_1", Filename: "c.mp4", ClipID: "c1",
			LocalPath: "ph_1/c.mp4", ThumbnailPath: "ph_1/c.mp4.jpg"}, // already relative
	}
	for _, s := range segs {
		if err := store.UpsertSegment(s); err != nil {
			t.Fatalf("upsert %s: %v", s.Filename, err)
		}
	}

	n, err := store.RelativizeArchivePaths()
	if err != nil || n != 2 {
		t.Fatalf("RelativizeArchivePaths = %d, %v; want 2 rewritten", n, err)
	}
	got, err := store.ListSegmentsForClip("c1")
	if err != nil {
		t.Fatal(err)
	}
	want := map[string][2]string{
		"a.mp4": {"ph_1/a.mp4", "ph_1/a.mp4.jpg"},
		"b.mp4": {"ph_1/b.mp4", ""},
		"c.mp4": {"ph_1/c.mp4", "ph_1/c.mp4.jpg"},
	}
	for _, s := range got {
		if w := want[s.Filename]; s.LocalPath != w[0] || s.ThumbnailPath != w[1] {
			t.Errorf("%s: paths = (%q, %q), want (%q, %q)", s.Filename, s.LocalPath, s.ThumbnailPath, w[0], w[1])
		}
	}

	if n, err := store.RelativizeArchivePaths(); err != nil || n != 0 {
		t.Errorf("second pass = %d, %v; want a no-op", n, err)
	}
}
