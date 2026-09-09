// Package appdirs centralizes the on-disk layout for the controller's local
// state: the SQLite database, the downloaded-clip archive, and the
// single-instance lock file.
//
// For this vertical slice, everything lives under a "data"/"archive"
// directory relative to the current working directory (i.e. wherever the
// user launches the binary from) rather than a proper per-OS app-data
// directory (os.UserConfigDir()). That's a deliberate simplification for
// development — see docs/status/controller-app-data-dir.md.
package appdirs

import (
	"os"
	"path/filepath"
)

// Dirs holds resolved, created-if-missing paths for the controller's local
// state.
type Dirs struct {
	Root       string // project-relative root the rest of these live under
	DataDir    string // holds the sqlite DB and the single-instance lock file
	ArchiveDir string // holds downloaded clips/thumbnails, one subdir per phone
}

// Resolve computes the standard directory layout, creating any directories
// that don't yet exist.
func Resolve() (Dirs, error) {
	root, err := os.Getwd()
	if err != nil {
		return Dirs{}, err
	}

	d := Dirs{
		Root:       root,
		DataDir:    filepath.Join(root, "data"),
		ArchiveDir: filepath.Join(root, "archive"),
	}
	for _, dir := range []string{d.DataDir, d.ArchiveDir} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return Dirs{}, err
		}
	}
	return d, nil
}

// DBPath is the path to the SQLite database file.
func (d Dirs) DBPath() string {
	return filepath.Join(d.DataDir, "panopticon.db")
}

// LockPath is the path to the single-instance lock file.
func (d Dirs) LockPath() string {
	return filepath.Join(d.DataDir, "controller.lock")
}

// PhoneArchiveDir returns (and ensures exists) the archive directory for one
// paired phone's clips/thumbnails.
func (d Dirs) PhoneArchiveDir(phoneID string) (string, error) {
	dir := filepath.Join(d.ArchiveDir, phoneID)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	return dir, nil
}
