// Package trayapp wires up the system tray icon that is the app's
// persistent presence — per docs/design/decisions/0011-controller-runtime-and-state.md, this is a tray-app,
// not a window-first app: background sync must not depend on a window
// being open, and the tray icon is what makes that visible/controllable.
package trayapp

import (
	"github.com/getlantern/systray"
)

// Callbacks the tray menu items invoke. Kept as plain function fields
// (rather than importing the Wails runtime here) so this package doesn't
// need to know about Wails at all.
type Callbacks struct {
	OnOpen func() // "Open" clicked — show/focus the main window
	OnQuit func() // "Quit" clicked — actually exit the app
}

// Run starts the systray event loop. This BLOCKS the calling goroutine for
// the life of the tray icon (systray's Windows implementation pumps its own
// Win32 message loop), so callers should invoke it in its own goroutine —
// see main.go, which runs it alongside wails.Run() on the main goroutine.
// Two independent Win32 message loops on two different OS threads is a
// supported pattern (message loops are per-thread, not global), and is the
// standard way to combine Wails with a systray library.
func Run(cb Callbacks) {
	systray.Run(func() { onReady(cb) }, func() {})
}

// Quit programmatically stops the tray event loop (called from the Wails
// side when the user quits via a route other than the tray menu, so the
// tray icon doesn't outlive the window).
func Quit() {
	systray.Quit()
}

func onReady(cb Callbacks) {
	systray.SetIcon(iconBytes)
	systray.SetTitle("Panopticon")
	systray.SetTooltip("Panopticon controller — background sync running")

	openItem := systray.AddMenuItem("Open", "Open the Panopticon window")
	systray.AddSeparator()
	quitItem := systray.AddMenuItem("Quit", "Quit Panopticon (stops background sync)")

	go func() {
		for {
			select {
			case <-openItem.ClickedCh:
				if cb.OnOpen != nil {
					cb.OnOpen()
				}
			case <-quitItem.ClickedCh:
				if cb.OnQuit != nil {
					cb.OnQuit()
				}
				return
			}
		}
	}()
}
