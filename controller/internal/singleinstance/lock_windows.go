//go:build windows

// Package singleinstance prevents a second launch of the controller from
// running a duplicate sync loop / duplicate tray icon — the old prototype
// hit real, hard-to-diagnose degradation from exactly this (a stale/second
// instance silently competing with the live one; see
// panopticon-prototype/QUIRKS.md, "A stale server process can silently
// survive a restart attempt").
//
// This slice's approach is deliberately simpler than that prototype's own
// (lock file + HTTP graceful-shutdown handshake): open the lock file with
// an exclusive share mode via a direct CreateFile syscall. Go's os.OpenFile
// always requests FILE_SHARE_READ|FILE_SHARE_WRITE under the hood on
// Windows, so it can't express "nobody else may even open this file" —
// hence going straight to the Win32 API here. Because it's a real OS-level
// handle, Windows releases it automatically the instant the owning process
// exits for ANY reason (clean exit, crash, task-killed) — no stale-lock
// cleanup logic needed, unlike a plain PID file.
package singleinstance

import (
	"fmt"
	"os"
	"syscall"
)

// Lock holds the open handle; keep it alive (don't let it be GC'd/closed)
// for the life of the process. Call Release on clean shutdown (optional —
// process exit does this anyway).
type Lock struct {
	file *os.File
}

// ErrAlreadyRunning means another instance currently holds the lock.
var ErrAlreadyRunning = fmt.Errorf("another instance is already running")

// Acquire tries to take the single-instance lock at path. If another
// instance already holds it, returns ErrAlreadyRunning.
func Acquire(path string) (*Lock, error) {
	pathPtr, err := syscall.UTF16PtrFromString(path)
	if err != nil {
		return nil, err
	}

	handle, err := syscall.CreateFile(
		pathPtr,
		syscall.GENERIC_READ|syscall.GENERIC_WRITE,
		0, // share mode 0 = exclusive; no other process may open this path while we hold it
		nil,
		syscall.OPEN_ALWAYS,
		syscall.FILE_ATTRIBUTE_NORMAL,
		0,
	)
	if err != nil {
		// ERROR_SHARING_VIOLATION (32): another process holds this file
		// open with a conflicting share mode — i.e. another instance
		// already owns the lock. Not exported as a named constant in the
		// standard syscall package, hence the raw errno.
		const errorSharingViolation syscall.Errno = 32
		if err == errorSharingViolation {
			return nil, ErrAlreadyRunning
		}
		return nil, fmt.Errorf("open lock file: %w", err)
	}

	f := os.NewFile(uintptr(handle), path)
	// Record our own PID for diagnostics (not used for correctness — the
	// exclusive handle itself is what enforces single-instance).
	_, _ = f.WriteString(fmt.Sprintf("%d", os.Getpid()))

	return &Lock{file: f}, nil
}

// Release closes the lock handle early. Not required for correctness (the
// OS releases it on process exit regardless) but tidy for an explicit
// "Quit" path.
func (l *Lock) Release() error {
	if l == nil || l.file == nil {
		return nil
	}
	return l.file.Close()
}
