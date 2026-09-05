//go:build !windows

// Fallback for non-Windows builds: this project targets Windows (per the
// controller handoff doc), so this is a best-effort PID-file implementation
// kept only so `go build ./...` doesn't break on other platforms — it is
// not exercised or hardened the way lock_windows.go is.
package singleinstance

import (
	"fmt"
	"os"
	"strconv"
	"syscall"
)

type Lock struct {
	path string
	file *os.File
}

var ErrAlreadyRunning = fmt.Errorf("another instance is already running")

func Acquire(path string) (*Lock, error) {
	if data, err := os.ReadFile(path); err == nil {
		if pid, convErr := strconv.Atoi(string(data)); convErr == nil {
			if proc, findErr := os.FindProcess(pid); findErr == nil {
				if proc.Signal(syscall.Signal(0)) == nil {
					return nil, ErrAlreadyRunning
				}
			}
		}
	}
	f, err := os.Create(path)
	if err != nil {
		return nil, err
	}
	_, _ = f.WriteString(strconv.Itoa(os.Getpid()))
	return &Lock{path: path, file: f}, nil
}

func (l *Lock) Release() error {
	if l == nil || l.file == nil {
		return nil
	}
	err := l.file.Close()
	os.Remove(l.path)
	return err
}
