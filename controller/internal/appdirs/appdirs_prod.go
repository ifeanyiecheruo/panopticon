//go:build !dev && !bindings

package appdirs

// useLaunchDir is false in a real build: state lives in the fixed per-user
// application-data directory. See appdirs_dev.go.
const useLaunchDir = false
