// Package appdirs centralizes the on-disk layout for the controller's local
// state: the SQLite database, the downloaded-clip archive, and the
// single-instance lock file.
//
// Everything lives under one root, as "panopticon-data/" (DB + lock) and
// "panopticon-archive/<phoneId>/" (clips + thumbnails). The root is, in order:
//
//   - $PANOPTICON_HOME, if set - an explicit override for tests and scratch
//     runs;
//   - the launch directory, in a `wails dev` build (the `dev` build tag - see
//     appdirs_dev.go), so development against the mock phone never touches
//     real footage;
//   - otherwise a fixed per-user application-data directory (see defaultRoot),
//     so the controller finds the same state however it was launched.
//
// Earlier builds always used the launch directory. MigrateLegacy moves state
// found there into the fixed location.
//
// The names are deliberately project-prefixed rather than plain "data" /
// "archive": the launch-directory layouts land in whatever directory the
// binary was run from, so they have to be ignorable repo-wide, and a bare
// `data/` ignore rule would also swallow any legitimate source directory of
// that name.
package appdirs

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
)

// EnvHome overrides the state root when set.
const EnvHome = "PANOPTICON_HOME"

const appDirName = "Panopticon"

// Dirs holds resolved paths for the controller's local state.
type Dirs struct {
	Root       string // the rest of these live under it
	DataDir    string // holds the sqlite DB and the single-instance lock file
	ArchiveDir string // holds downloaded clips/thumbnails, one subdir per phone
}

// At lays out the standard directories under root, without creating them.
func At(root string) Dirs {
	return Dirs{
		Root:       root,
		DataDir:    filepath.Join(root, "panopticon-data"),
		ArchiveDir: filepath.Join(root, "panopticon-archive"),
	}
}

// Resolve picks the state root (see the package doc) without creating
// anything - call Ensure once any legacy state has been migrated in.
func Resolve() (Dirs, error) {
	if home := os.Getenv(EnvHome); home != "" {
		abs, err := filepath.Abs(home)
		if err != nil {
			return Dirs{}, err
		}
		return At(abs), nil
	}
	if useLaunchDir {
		cwd, err := os.Getwd()
		if err != nil {
			return Dirs{}, err
		}
		return At(cwd), nil
	}
	root, err := defaultRoot()
	if err != nil {
		return Dirs{}, err
	}
	return At(root), nil
}

// defaultRoot is the per-user application-data directory. Clip archives run
// to gigabytes, so this must be somewhere that is neither roamed between
// machines nor treated as a disposable cache.
func defaultRoot() (string, error) {
	var base string
	var err error
	switch runtime.GOOS {
	case "windows":
		// %LocalAppData%: per-machine. (os.UserConfigDir is %AppData%, which
		// roams.)
		base, err = os.UserCacheDir()
	case "darwin":
		base, err = os.UserConfigDir() // ~/Library/Application Support
	default:
		// $XDG_DATA_HOME, not ~/.cache (os.UserCacheDir) - cleaners wipe that.
		base = os.Getenv("XDG_DATA_HOME")
		if base == "" {
			var home string
			home, err = os.UserHomeDir()
			base = filepath.Join(home, ".local", "share")
		}
	}
	if err != nil {
		return "", err
	}
	return filepath.Join(base, appDirName), nil
}

// Ensure creates the directories if they don't exist yet.
func (d Dirs) Ensure() error {
	for _, dir := range []string{d.DataDir, d.ArchiveDir} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return err
		}
	}
	return nil
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

// ArchiveRelPath is how a file under the archive is stored in the DB:
// slash-separated and relative to ArchiveDir, so the archive can move without
// a rewrite of every row. ArchivePath turns it back into a real path.
func ArchiveRelPath(phoneID, filename string) string {
	return phoneID + "/" + filename
}

// ArchivePath resolves a stored archive path to a real one. Absolute paths
// (rows written before paths were stored relative) pass through unchanged.
func (d Dirs) ArchivePath(stored string) string {
	if stored == "" || filepath.IsAbs(stored) {
		return stored
	}
	return filepath.Join(d.ArchiveDir, filepath.FromSlash(stored))
}

// LegacyCandidate returns the launch-directory layout earlier builds used, if
// it holds a controller database and d - a different root - doesn't have one
// yet. That's the state MigrateLegacy moves.
func LegacyCandidate(d Dirs) (Dirs, bool) {
	cwd, err := os.Getwd()
	if err != nil {
		return Dirs{}, false
	}
	legacy := At(cwd)
	if filepath.Clean(legacy.Root) == filepath.Clean(d.Root) {
		return Dirs{}, false
	}
	if !exists(legacy.DBPath()) || exists(d.DBPath()) {
		return Dirs{}, false
	}
	return legacy, true
}

// MigrateLegacy moves legacy's data and archive directories to d by rename -
// the archive first, so a failure on the data dir can put it back and leave
// everything where it was. The caller must know no controller is running
// against legacy (see main.go), and that d has no database yet
// (LegacyCandidate). Rename can't cross volumes; that surfaces as an error,
// with nothing moved.
func MigrateLegacy(legacy, d Dirs) error {
	if err := os.MkdirAll(d.Root, 0o755); err != nil {
		return err
	}
	// Rename won't replace an existing directory on Windows; an empty one left
	// by an earlier run is fine to clear, anything else is not ours to touch.
	for _, dir := range []string{d.DataDir, d.ArchiveDir} {
		if err := os.Remove(dir); err != nil && !errors.Is(err, os.ErrNotExist) {
			return fmt.Errorf("%s already exists and isn't empty: %w", dir, err)
		}
	}
	movedArchive := false
	if exists(legacy.ArchiveDir) {
		if err := os.Rename(legacy.ArchiveDir, d.ArchiveDir); err != nil {
			return fmt.Errorf("move %s: %w", legacy.ArchiveDir, err)
		}
		movedArchive = true
	}
	if err := os.Rename(legacy.DataDir, d.DataDir); err != nil {
		if movedArchive {
			if undoErr := os.Rename(d.ArchiveDir, legacy.ArchiveDir); undoErr != nil {
				return fmt.Errorf("move %s: %w (and moving the archive back failed: %v - it is now at %s)",
					legacy.DataDir, err, undoErr, d.ArchiveDir)
			}
		}
		return fmt.Errorf("move %s: %w", legacy.DataDir, err)
	}
	return nil
}

func exists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}
