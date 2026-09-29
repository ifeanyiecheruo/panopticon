//go:build dev || bindings

package appdirs

// useLaunchDir keeps two kinds of build on the launch directory, as before:
//
//   - `wails dev` (the `dev` tag), so development against the mock phone never
//     touches real footage;
//   - wails' bindings generator (the `bindings` tag), which runs main() in the
//     project directory to collect the bound methods - part of every
//     `wails build` and `wails generate module`. It must never open, create or
//     migrate real state.
const useLaunchDir = true
