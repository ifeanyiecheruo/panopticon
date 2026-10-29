# Plan: tray icon stops responding

**Side:** controller · **Size:** small

## Goal

The tray icon's menu (Open / Quit) always opens on click. A stuck tray never leaves the
controller impossible to quit without Task Manager.

## Context

Seen on 2026-10-29: a long-running controller's tray icon ignored right and left clicks. The
window had no Quit, so the only way out was killing the process, and a relaunch just printed
"already running".

The likely cause is a thread-affinity bug in how `main.go` starts the tray:

- `getlantern/systray` locks the OS thread in its package `init()`. That pins the **main**
  goroutine, so the library expects `systray.Run` to be called from `main`.
- `main.go` calls `go trayapp.Run(...)` on a **new** goroutine, because `main` belongs to
  `wails.Run`. Nothing pins that goroutine to an OS thread.
- `Register` creates the tray's hidden window on whatever thread the goroutine is on. Then
  `nativeLoop` calls `GetMessage(hwnd=0)`, which only returns messages for windows owned by
  the **calling** thread.
- Go's scheduler is free to move the goroutine to another OS thread around any syscall, and the
  sync loop, HTTP and remux traffic make that likely over hours. After a move, the pump runs on
  a thread that owns no window. The tray's click messages (`wmSystrayMessage` →
  `showMenu`) queue up on the original thread and are never dispatched, so the icon stays
  visible but dead.

That fits what was seen: it worked after launch and failed after a long run. The same day, a
build that had been up about 15 minutes quit from its tray menu normally. Another, launched at
15:20 and used for clip playback with motion analysis running (more goroutines and syscalls),
had a dead tray by 15:32, after about 12 minutes. So the failure is timing-dependent, not tied
to a long uptime, which is what a goroutine moving threads would give.

There's a second, smaller gap. `trayapp`'s click loop `return`s after the first Quit, so if that
quit stalls (`syncMgr.Stop` blocking, say), later Quit clicks do nothing either.

## Status (2026-10-29)

Steps 1 and 3 are done: `trayapp.Run` locks its OS thread, the click loop keeps listening
after a Quit, and `App.RequestQuit` is idempotent (`sync.Once` around stopping sync). It's
built, and has run since 17:56 that day; the multi-hour soak and a tray Quit at the end of it
are still to do. Steps 2 and 4 are not started.

## Approach

1. **Pin the tray goroutine.** Call `runtime.LockOSThread()` as the first line of `trayapp.Run`
   and never unlock it, so window creation and the message pump share one thread for the life of
   the tray. Note the reason in `Run`'s doc comment, which currently says only that two loops on
   two threads are fine.
2. **Window-side Quit.** Add a Quit to the nav rail (or its overflow) that calls the existing
   `App.RequestQuit` (its comment already promises a frontend affordance). Then a broken tray
   is never the only way out.
3. **Don't drop later Quit clicks.** Keep the click loop running after a Quit. `RequestQuit`
   should be idempotent, with `quitting` checked first.
4. **Optional:** let a second launch hand off, not just exit. Today a relaunch prints "already
   running". A relaunch could instead signal the running instance to show its window (or quit,
   with a `--quit` flag). That would also let `make run-controller` replace a running build.

## Affected files

- `controller/internal/trayapp/tray.go`: `LockOSThread`, the doc comment, the click loop.
- `controller/app.go`: make `RequestQuit` idempotent.
- `controller/frontend/src/components/Shell.tsx`: the Quit affordance.
- Step 4 only: `controller/main.go` and `controller/internal/singleinstance`.

## Testing

- **Confirm the cause first.** Log `GetCurrentThreadId` when the tray window is created, and in
  a goroutine that, with the fix absent, sees the goroutine move (sample
  `windows.GetCurrentThreadId` before and after a burst of syscalls while the pump is blocked).
  Alternatively, run the unfixed build with heavy sync and remux load, and check the menu every
  few minutes.
- **With the fix:** run a long soak, at least as long as the run that broke, with Gallery
  playback and sync active. Open the tray menu periodically, and finish with Quit from the tray.
- Click the window-side Quit with the tray working, and again with the window hidden then
  re-opened from the tray.

## Acceptance

- After a multi-hour soak, the tray menu opens on the first click, and tray Quit exits the
  process. The lock is released, so an immediate relaunch succeeds.
- The window's Quit exits the same way.

## Not in scope

- Replacing `getlantern/systray`.
- A tray menu with more than Open / Quit.
