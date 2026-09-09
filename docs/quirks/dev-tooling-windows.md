# Development tooling (Windows) - new findings this project

Device/environment context and the "Reconfirmed" / "Carried forward" convention: see
[`README.md`](README.md). Everything below is specific to building this repo on the dev machine:
Windows 10, an MSYS2 `make`, and the repo rooted under `C:\Users\<user>\OneDrive\...`.

### `gradlew.bat` invoked bare through `cmd.exe //c` isn't found, even from the right cwd
**Assumed:** once `cd /d <path> && gradlew.bat ...` has changed into the project directory (which
it genuinely does - confirmed via a bare `cd` printout), a bare `gradlew.bat` invocation would be
found the same way Explorer/PowerShell would find it.
**Actually:** `cmd.exe //c "cd /d <path> && gradlew.bat ..."` (invoked from this environment's
Bash tool, itself calling `cmd.exe` explicitly per the old prototype's Makefile convention)
reliably printed `'gradlew.bat' is not recognized as an internal or external command` - even
though `dir`/`where` in the same session found the file, and a bare `cd` (no `%cd%` var
substitution - see next entry) confirmed the cwd was correct.
**Workaround:** prefix with `.\`: `.\gradlew.bat ...` resolved and ran correctly every time.
**Where:** used throughout manual testing in this session; worth carrying into `phone-app/Makefile`'s
`GRADLEW` variable (explicit `./gradlew.bat`, not a bare `gradlew.bat`, when routed through `cmd.exe`).

### `%cd%` inside a `cmd.exe //c "cd /d X && echo %cd%"` one-liner reports the *pre-cd* directory
**Assumed:** `%cd%` expands to the current directory at the point the `echo` command actually
runs, i.e. after `cd /d X` has taken effect earlier on the same line.
**Actually:** cmd.exe expands `%variables%` once, at parse time, before executing *any* command on
the line (without delayed expansion, `!var!`) - so `%cd%` in a `cd /d X && echo %cd%` one-liner
shows the directory the shell was in *before* the line ran, not after the `cd`. This produced a
confusing false lead while debugging the entry above (the cwd looked "one directory too shallow").
**Workaround:** use a bare `cd` (no arguments) to print the actual current directory instead of
`echo %cd%`, or enable delayed expansion (`setlocal enabledelayedexpansion` + `!cd!`) if a variable
is really needed.
**Where:** debugging notes only, not encoded in any script - worth remembering if `Makefile` ever
needs to introspect cwd from a `cmd.exe`-routed recipe.

### adb can mark a still-connected device "offline" until `adb kill-server`/`start-server`
**Assumed:** `adb devices` reliably reflects a physically connected, USB-debugging-enabled
device's real state.
**Actually:** with two devices attached (this Pixel 6, serial `1C281FDF6005H0`, plus a second
unrelated device), the Pixel 6 showed as `offline` in `adb devices` at the start of this session
despite being physically connected and previously working - `adb -s 1C281FDF6005H0 shell ...`
failed with `device offline` for every command.
**Workaround:** `adb kill-server && adb start-server` (then re-run `adb devices`) recovered it
immediately, no physical reconnection needed.
**Where:** dev workflow note - worth adding to `phone-app/Makefile`'s `install:phone`/`run:phone`
troubleshooting output if this recurs.

### This MSYS2 `make` also strips `GOPATH`/`USERPROFILE`/`TMP`/`TEMP`, breaking `go` inside recipes
**Assumed:** the old prototype's finding (`LOCALAPPDATA`/`USERPROFILE`/`APPDATA`/`ANDROID_HOME`
stripped from every process this `make` spawns) was a closed, fully-enumerated list scoped to
Android tooling.
**Actually:** building a unified root `Makefile` that also drives the Go-based controller hit the
same stripping for Go's toolchain env vars, in two stages: first `go test`/`go build` inside a
recipe failed with `neither GOMODCACHE nor GOPATH is set` (Go's Windows build computes a default
`GOPATH` from `%USERPROFILE%`, which is empty here); after pinning `GOPATH` explicitly, it failed
again with `build cache is required, but could not be located: GOCACHE is not defined and
%LocalAppData% is not defined`, and separately `go: creating work dir: mkdir
C:\Windows\go-build...: Access is denied` (Go's temp-dir fallback walks `TMP`/`TEMP`/`USERPROFILE`,
all empty here, and lands on `C:\Windows` via a last-resort Windows API call, which isn't
writable). Also confirmed this `make`'s own `$HOME`/`PATH` inside a recipe are **not** the
invoking interactive shell's values either (`$HOME` resolved to this `make`'s own
`/home/<user>`, not `/c/Users/<user>`; `$PATH` started with `/home/<user>/bin`, not the real
`PATH`) - a broader form of the same isolation than the original entry described.
**Workaround:** same fix as `ADB` above, extended to Go: `export GOPATH := $(firstword $(wildcard
/c/Users/*/go))`, plus explicit `export TMP`/`TEMP`/`GOCACHE` pointed at scratch directories under
that resolved `GOPATH` (created via `$(shell mkdir -p ...)` at Makefile-parse time), so nothing
Go-related depends on an env var this `make` might strip.
**Where:** `Makefile` (root), the `ifneq (,$(findstring MINGW,...))` Windows block.

### This `make`'s recipe shell eats a literal backslash before a letter (`\g` → `g`)
**Assumed:** a Makefile variable holding a literal Windows-style relative path prefix (`.\gradlew.bat`)
would reach `cmd.exe` unchanged when interpolated into a recipe line, the same way it reads in the
Makefile source.
**Actually:** the recipe line is first evaluated by `make`'s own shell (`sh`, POSIX-like) before
`cmd.exe` ever sees it, and POSIX shells treat a backslash before an ordinary character as "take
the next character literally" (dropping the backslash) - so `.\gradlew.bat` reached `cmd.exe` as
`.gradlew.bat` (no backslash, no separator between `.` and `gradlew`), which `cmd.exe` correctly
reported as not found. This is distinct from - and adds a further wrinkle on top of - the older
"bare `gradlew.bat` isn't found" entry above; the fix for that one (`.\gradlew.bat` instead of a
bare name) is exactly what tripped this new issue when moved from a one-off interactive command
into a `Makefile` recipe.
**Workaround:** double the backslash in the Makefile source (`.\\gradlew.bat`) so that after the
recipe shell's escape processing, `cmd.exe` still receives a single `\`.
**Where:** `Makefile` (root), the `GRADLEW` variable.

### This `make`'s own `$(CURDIR)` resolves through the wrong mount alias for an OneDrive-rooted repo
**Assumed:** GNU Make's built-in `$(CURDIR)` variable reflects the same working directory a
recipe's own shell would report via `pwd`.
**Actually:** for this repo specifically (its real path is under `C:\Users\<user>\OneDrive\...`),
`$(CURDIR)` resolved to `/home/<user>/OneDrive/...` - a path that doesn't exist at all in this
`make`'s own recipe shell (`mkdir -p` on it failed trying to create `/home` itself, permission
denied), even though a bare `pwd` run as a recipe command correctly printed
`/c/Users/<user>/OneDrive/...`. Root cause not fully diagnosed, but consistent with this
`make`/MSYS2 build having its own internal mount-alias table it consults for `$(CURDIR)`
specifically, separate from (and less complete than) whatever its spawned recipe shells actually
use for real filesystem paths.
**Workaround:** never use `$(CURDIR)` in this Makefile; use `$(shell pwd)` instead (confirmed
correct) for anything that needs the repo root as an absolute path (`ROOT` in the Makefile).
**Where:** `Makefile` (root), the `ROOT` variable.

### This `make`'s `command -v <tool>` can resolve through a *working-but-different* path alias too
**Assumed:** if `command -v nvm` resolves and the resulting path passes a `[ -f ... ]` existence
check inside a recipe, that same path string is safe to bake into a generated script for later use
outside `make`.
**Actually:** `command -v nvm` inside a recipe returned `/home/<user>/AppData/Roaming/nvm/nvm` -
unlike the `$(CURDIR)` case above, this path *does* resolve inside this `make`'s own recipe shells
(nvm-windows' install directory apparently being one of a small set of user-profile paths this
MSYS2 build's `/home/<user>` mount aliases to), which made it easy to mistake for a genuinely
portable path. A shim script written with this path baked in worked when run via `make`, then
failed with "No such file or directory" run directly from an ordinary interactive Git Bash prompt,
where `/home/<user>/AppData/...` isn't a valid path at all.
**Workaround:** don't trust `command -v`'s literal output for anything that needs to be portable
outside this one `make`'s own shells - construct the path independently instead. Here: nvm-windows'
root is always `%APPDATA%\nvm` by its own fixed convention, so `NVM_ROOT` is built from
`/c/Users/$(shell whoami)/AppData/Roaming/nvm` rather than `dirname` of `command -v nvm`'s output;
`command -v nvm` is still used, but only as an existence check (installed vs. not), never as a path
source.
**Where:** `Makefile` (root), the `NVM_ROOT` variable.

### Gradle's unit-test task needs `local.properties`' `sdk.dir` even when `assembleDebug` doesn't
**Assumed:** since `ANDROID_HOME` being stripped from this `make`'s recipes never broke
`assembleDebug` in practice, Gradle must be resolving the SDK location some other reliable way
that `test` would share.
**Actually:** `assembleDebug` kept succeeding only because its relevant tasks were already
`UP-TO-DATE` from a previous (IDE- or manually-configured) run and never actually needed to
re-resolve the SDK location; `./gradlew test`, run fresh after the monorepo restructuring
recreated `phone-app/` from git history (which never tracked the gitignored `local.properties`),
failed immediately with "SDK location not found" - the one thing that reliably tells Gradle where
the SDK is regardless of environment variables is `local.properties`' `sdk.dir` line (normally
auto-written by Android Studio, silently relied upon rather than actually understood).
**Workaround:** a `phone-app-local-properties` Make target unconditionally (re)writes
`phone-app/local.properties` from the same `ANDROID_SDK_ROOT_WIN`/`_POSIX` this Makefile already
resolves for `install-tools-android-sdk`, and both `build-phone` and `test-phone` depend on it -
cheap enough (one line) to just always rewrite rather than track staleness.
**Where:** `Makefile` (root), the `phone-app-local-properties` target.

### `go run <relative-path>` refuses to cross a module boundary, even to a directory with its own go.mod
**Assumed:** `go run ../some/other/dir` (a plain filesystem path, not an import path) builds and
runs whatever package lives at that path, the same way it would if that path were a subdirectory
of the calling module.
**Actually:** when the *target* directory has its own `go.mod` (i.e. it's a different module, as
every `tools/*` module here deliberately is - see the "tools/ as separate modules" convention),
`go run`/`go build` refuse outright: `directory ...\tools\dbstore outside main module or its
selected dependencies`. This is true even for a path that's a perfectly valid, buildable module on
its own - the restriction is specifically about crossing a module boundary via a bare relative
path, and it bit both `controller/internal/dbstore/queries.gen.json`'s declared generator command
(`go run ../../../tools/dbstore ...`) and `tools/mock-phone`'s own then-documented usage
(`go run ../tools/mock-phone/cmd/mockphone` from `controller/`) - the latter had apparently
never actually been run exactly as documented until this was diagnosed. (`CONTRIBUTING.md` now
documents the repo-root `go run ./tools/mock-phone/cmd/mockphone` form instead.)
**Workaround:** a `go.work` file at the repo root listing every module (`controller`,
`tools/dbstore`, `tools/go-deps`, `tools/mock-phone`) makes `go run`/`go build` workspace-aware,
resolving relative paths across any module the workspace lists rather than just the calling
module's own tree. Committed (not gitignored, unlike most projects' `go.work`) since `make
generate` genuinely depends on it existing, not just as a per-developer convenience.
**Where:** `go.work` (root); `controller/internal/dbstore/queries.gen.json`;
`tools/dbstore/README.md`, `tools/go-deps/README.md`.

### A parse-time `$(shell go ...)` call doesn't see this make's own `export`s, even though every recipe does
**Assumed:** once a Makefile variable is `export`ed, every subsequent `$(shell ...)` call in that
same Makefile sees it in its environment - recipe-time or parse-time, no difference.
**Actually:** confirmed the opposite empirically: a `$(shell go ...)` call used to compute a target's
prerequisite list (i.e. evaluated while Make is still reading the Makefile, via `$(eval $(call
...))`) fails with the by-now-familiar `neither GOMODCACHE nor GOPATH is set`, even though the very
same `export GOPATH := ...` line reliably reaches every ordinary recipe body elsewhere in this same
file (extensively verified throughout the rest of this Makefile). A minimal repro nailed it down
further: `$(info $(shell echo $$GOPATH))` placed *after* the `export GOPATH := ...` line still
prints empty. Whatever this `make` does to apply `export` to a `$(shell)` call's environment, it
isn't available yet during the file's own top-to-bottom parse pass, only once recipes start running.
**Workaround:** don't rely on the `export` for a parse-time `$(shell ...)` call - pass the already-
computed Makefile variables explicitly as an inline environment prefix instead: `$(shell
GOPATH="$(GOPATH)" TMP="$(TMP)" TEMP="$(TEMP)" GOCACHE="$(GOCACHE)" go run ...)`. The values are
already known as ordinary Make variables regardless of whether their `export` has "taken" yet, so
this sidesteps the question entirely.
**Where:** `Makefile` (root), the `gen-stamp-rule` define (used to compute each `.gen.json.stamp`
target's prerequisites via `go-deps get`).

### Piping a real command's output into `awk` inside a recipe's `$$(...)` can break, same as `head`
**Assumed:** the earlier-documented "piping a glob through `head` inside `$(shell)` intermittently
breaks" quirk (see `panopticon-prototype/QUIRKS.md`) was specific to `$(shell)` (Makefile-parse-time
execution) and to `head`.
**Actually:** hit the same failure mode (`awk: ... fatal: error reading input file '-': Broken
pipe`) from a completely ordinary *recipe* (build-time, not parse-time) piping real `adb devices`
output into `awk` via `count=$$("$(ADB)" devices | awk '...')` - neither `$(shell)` nor `head` were
involved this time, just this `make`'s pipe handling in general being unreliable under this
specific MSYS2 build.
**Workaround:** avoid the pipe entirely - redirect the producer's output to a temp file, then have
the consumer read that file instead of stdin: `"$(ADB)" devices > "$$devices_list"; count=$$(awk
'...' "$$devices_list")`. Slightly more verbose, but never touches a pipe, so there's nothing left
for this bug to trigger on.
**Where:** `Makefile` (root), the `check-adb-devices` target.
