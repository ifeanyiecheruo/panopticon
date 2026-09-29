package appdirs

import (
	"os"
	"path/filepath"
	"testing"
)

func TestResolve_OverrideWins(t *testing.T) {
	home := t.TempDir()
	t.Setenv(EnvHome, home)
	d, err := Resolve()
	if err != nil {
		t.Fatal(err)
	}
	if d.Root != home || d.DataDir != filepath.Join(home, "panopticon-data") || d.ArchiveDir != filepath.Join(home, "panopticon-archive") {
		t.Errorf("Resolve() = %+v, want the layout under %s", d, home)
	}
}

func TestResolve_DefaultIsFixedAppDataDir(t *testing.T) {
	t.Setenv(EnvHome, "")
	want, err := defaultRoot()
	if err != nil {
		t.Fatal(err)
	}
	// The same root from two different launch directories.
	for _, cwd := range []string{t.TempDir(), t.TempDir()} {
		t.Chdir(cwd)
		d, err := Resolve()
		if err != nil {
			t.Fatal(err)
		}
		if d.Root != want {
			t.Errorf("from %s: Root = %s, want %s", cwd, d.Root, want)
		}
	}
	if filepath.Base(want) != appDirName {
		t.Errorf("default root %s should be an app-named directory", want)
	}
}

func TestArchivePath(t *testing.T) {
	d := At(t.TempDir())
	rel := ArchiveRelPath("ph_1", "seg.mp4")
	if got, want := d.ArchivePath(rel), filepath.Join(d.ArchiveDir, "ph_1", "seg.mp4"); got != want {
		t.Errorf("ArchivePath(%q) = %s, want %s", rel, got, want)
	}
	abs := filepath.Join(t.TempDir(), "elsewhere.mp4")
	if got := d.ArchivePath(abs); got != abs {
		t.Errorf("absolute path should pass through, got %s", got)
	}
	if got := d.ArchivePath(""); got != "" {
		t.Errorf("empty path should stay empty, got %q", got)
	}
}

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestMigrateLegacy_MovesLaunchDirStateOnce(t *testing.T) {
	launch := t.TempDir()
	t.Chdir(launch)
	legacy := At(launch)
	writeFile(t, legacy.DBPath(), "db")
	writeFile(t, filepath.Join(legacy.ArchiveDir, "ph_1", "seg.mp4"), "clip")

	d := At(filepath.Join(t.TempDir(), "Panopticon"))
	got, ok := LegacyCandidate(d)
	if !ok || got.Root != legacy.Root {
		t.Fatalf("LegacyCandidate = %+v, %v; want the launch dir", got, ok)
	}
	if err := MigrateLegacy(got, d); err != nil {
		t.Fatalf("MigrateLegacy: %v", err)
	}
	if b, err := os.ReadFile(d.DBPath()); err != nil || string(b) != "db" {
		t.Errorf("DB not at new location: %q, %v", b, err)
	}
	if b, err := os.ReadFile(filepath.Join(d.ArchiveDir, "ph_1", "seg.mp4")); err != nil || string(b) != "clip" {
		t.Errorf("archive not at new location: %q, %v", b, err)
	}
	for _, dir := range []string{legacy.DataDir, legacy.ArchiveDir} {
		if exists(dir) {
			t.Errorf("%s should have moved", dir)
		}
	}

	// Once moved - or whenever the new root already has a DB - nothing is a candidate.
	writeFile(t, legacy.DBPath(), "stale")
	if _, ok := LegacyCandidate(d); ok {
		t.Errorf("a root that already has a DB must never be migrated into")
	}
}

func TestMigrateLegacy_RefusesNonEmptyTargetAndMovesNothing(t *testing.T) {
	legacy := At(t.TempDir())
	writeFile(t, legacy.DBPath(), "db")
	writeFile(t, filepath.Join(legacy.ArchiveDir, "ph_1", "seg.mp4"), "clip")
	d := At(t.TempDir())
	writeFile(t, filepath.Join(d.ArchiveDir, "someone-elses.txt"), "x")

	if err := MigrateLegacy(legacy, d); err == nil {
		t.Fatal("expected an error migrating into a non-empty archive dir")
	}
	if !exists(legacy.DBPath()) || !exists(filepath.Join(legacy.ArchiveDir, "ph_1", "seg.mp4")) {
		t.Errorf("a refused migration must leave the legacy state where it was")
	}
}

func TestLegacyCandidate_NoneWithoutADatabase(t *testing.T) {
	launch := t.TempDir()
	t.Chdir(launch)
	writeFile(t, filepath.Join(At(launch).ArchiveDir, "ph_1", "seg.mp4"), "clip")
	if _, ok := LegacyCandidate(At(t.TempDir())); ok {
		t.Errorf("an archive with no database isn't controller state to migrate")
	}
	if _, ok := LegacyCandidate(At(launch)); ok {
		t.Errorf("the launch dir is never a candidate for itself")
	}
}
