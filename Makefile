# Panopticon repo tasks: build/install/run/test the phone-app (Android) and controller
# (Go/Wails) from one place. Each project also has its own README with more detail;
# this Makefile is just the task runner.
#
# Help is generated from the "## description" comments on each target below (see `help:`) -
# add a target, add its "## ..." comment, done. Nothing to keep in sync by hand.

.PHONY: help build build-phone build-controller \
        install install-phone install-controller \
        run-phone run-controller e2e \
        test test-phone test-controller \
        clean clean-phone clean-controller \
        device-info check-adb-devices \
        install-tools install-tools-nvm-node install-tools-wails install-tools-vite \
        install-tools-shims install-tools-android-sdk \
        phone-app-local-properties \
        generate

.DEFAULT_GOAL := help

PHONE_PACKAGE := com.panopticon.phoneapp
# Unset by default - only passed to adb at all when the caller sets it (make <target>
# ADB_SERIAL=<serial>). With exactly one device connected, adb targets it with no flag needed;
# with more than one, check-adb-devices (a prerequisite of every adb-targeting rule below) catches
# the ambiguity up front and tells the caller to set this, rather than letting adb itself fail on
# whichever command happens to run first.
ADB_SERIAL_FLAG = $(if $(ADB_SERIAL),-s $(ADB_SERIAL))

# --- Private per-project tool isolation (see `make install-tools`) ---------------------------
# ROOT/.local holds every tool this Makefile can install without touching anything machine-wide
# (except the Android SDK - see install-tools-android-sdk for why that one's the exception).
# LOCAL_BIN is prepended to PATH for every recipe below (see the `export PATH` line further down),
# so once install-tools has run, plain `npm`/`npx`/`node`/`vite`/`wails` commands anywhere in this
# Makefile transparently resolve to the private, .nvmrc-pinned copies instead of whatever (if
# anything) is on the developer's machine-wide PATH.
# Deliberately $(shell pwd), not Make's own built-in $(CURDIR): this MSYS2 make's CURDIR resolves
# through some other, wrong mount alias (observed reporting /home/<user>/... for a directory that
# only exists under /c/Users/<user>/...) - a new, more surprising instance of the same "don't trust
# this make's own idea of paths/env" theme as the other Windows-tooling quirks. See docs/QUIRKS.md.
ROOT := $(shell pwd)
LOCAL_BIN := $(ROOT)/.local/bin
LOCAL_GOBIN := $(ROOT)/.local/go/bin
LOCAL_NPM_PREFIX := $(ROOT)/.local
NODE_VERSION := $(shell cat .nvmrc 2>/dev/null)
CMDLINE_TOOLS_BUILD := 15859902

# On Windows, route gradlew through the real cmd.exe rather than invoking it directly from this
# MSYS2 shell - see docs/QUIRKS.md ("gradlew.bat invoked bare through cmd.exe //c isn't found")
# and panopticon-prototype/QUIRKS.md ("gradlew/npm misbehave when launched directly from this
# MSYS2 shell") for why. Note the explicit "./gradlew.bat" (not a bare "gradlew.bat") - this
# project's own QUIRKS.md found the bare form isn't recognized even with the right cwd.
ifneq (,$(findstring MINGW,$(shell uname -s))$(findstring MSYS,$(shell uname -s))$(findstring CYGWIN,$(shell uname -s)))
CMDEXE := /c/Windows/System32/cmd.exe //c
GRADLEW := $(CMDEXE) .\\gradlew.bat
CONTROLLER_BIN := controller/installer-src/bin/panopticon-controller.exe
# The Android SDK installer commonly leaves platform-tools (adb) off PATH even when it's present -
# fall back to its default Windows install location before giving up. Reading that location via
# $LOCALAPPDATA doesn't work under this MSYS2 make (env vars get stripped from spawned processes -
# see panopticon-prototype/QUIRKS.md), so this globs the filesystem directly instead.
ifeq (,$(shell command -v adb 2>/dev/null))
ADB := $(firstword $(shell ls -1 /c/Users/*/AppData/Local/Android/Sdk/platform-tools/adb.exe /c/Users/*/AppData/Local/Android/sdk/platform-tools/adb.exe 2>/dev/null))
endif
# Where a not-yet-installed Android SDK would go (see install-tools-android-sdk). Derived from an
# already-found ADB when there is one (two directories up from platform-tools/adb.exe), otherwise
# the standard per-user install location - built from `whoami`, not $LOCALAPPDATA/$USERPROFILE,
# since those are exactly the vars this same make strips (see above and docs/QUIRKS.md).
ANDROID_SDK_ROOT_POSIX := $(if $(ADB),$(shell dirname "$$(dirname "$(ADB)")"),/c/Users/$(shell whoami)/AppData/Local/Android/Sdk)
ANDROID_SDK_ROOT_WIN := $(subst /c/,C:/,$(ANDROID_SDK_ROOT_POSIX))
CMDLINE_TOOLS_URL := https://dl.google.com/android/repository/commandlinetools-win-$(CMDLINE_TOOLS_BUILD)_latest.zip
SDKMANAGER_INVOKE := $(CMDEXE) "$(ANDROID_SDK_ROOT_WIN)/cmdline-tools/latest/bin/sdkmanager.bat"
# nvm-windows (github.com/coreybutler/nvm-windows) keeps every installed Node version in its own
# self-contained `v<version>/` directory directly under nvm's own root (same directory nvm.exe
# itself lives in) - e.g. .../nvm/v22.18.0/node.exe, node_modules/npm/bin/npm-cli.js, etc. That
# means this project's pinned version can be used directly from its own versioned directory
# without ever calling `nvm use` (which would repoint the machine-wide `C:\Program Files\nodejs`
# symlink nvm-windows uses for whatever is "globally active" - exactly the cross-project impact
# this setup is meant to avoid, and it needs admin elevation besides). See install-tools-nvm-node.
#
# Built from `whoami` + nvm-windows' fixed %APPDATA%\nvm convention, NOT `dirname "$(command -v
# nvm)"`: this MSYS2's own PATH resolves `nvm` through a /home/<user>/... alias for AppData
# specifically (some other alias than the /c/Users/<user>/... one this Makefile uses everywhere
# else) - it happens to still be a valid path *inside this one make's own recipe shells*, but a
# shim script written with that path baked in breaks the moment it's run from any other shell
# (confirmed: works via `make ...`, fails with "No such file or directory" run directly from an
# ordinary Git Bash prompt). Constructing the path ourselves sidesteps the alias entirely.
NVM_ROOT := /c/Users/$(shell whoami)/AppData/Roaming/nvm
NODE_DIR := $(NVM_ROOT)/v$(NODE_VERSION)
# This same make also strips USERPROFILE/TMP/TEMP, which the Windows `go` toolchain needs to
# compute a default GOPATH and a scratch dir for its build work - confirmed here as a new instance
# of the env-var-stripping quirk above: without them, `go test`/`go build` inside a recipe first
# fail with "neither GOMODCACHE nor GOPATH is set", and once GOPATH is fixed, fail again trying to
# create a work dir under C:\Windows (go's last-resort temp-dir fallback once TMP/TEMP/USERPROFILE
# are all empty), which isn't writable. Same fix as ADB: glob/derive real paths instead of trusting
# the env vars, and point TMP/TEMP at a scratch dir under GOPATH that this Makefile controls and
# creates. See docs/QUIRKS.md.
export GOPATH := $(firstword $(wildcard /c/Users/*/go))
export TMP := $(GOPATH)/panopticon-make-tmp
export TEMP := $(TMP)
export GOCACHE := $(GOPATH)/panopticon-make-gocache
$(shell mkdir -p "$(TMP)" "$(GOCACHE)")
else
GRADLEW := ./gradlew
CONTROLLER_BIN := controller/installer-src/bin/panopticon-controller
# Best-effort POSIX equivalents of the Windows block above (standard nvm.sh + Linux Android SDK
# conventions) - this project is developed on Windows, so these paths are unexercised/untested
# here; adjust CMDLINE_TOOLS_URL for macOS (commandlinetools-mac-*) if needed.
ANDROID_SDK_ROOT_POSIX := $(if $(ADB),$(shell dirname "$$(dirname "$(ADB)")"),$(HOME)/Android/Sdk)
SDKMANAGER_INVOKE := "$(ANDROID_SDK_ROOT_POSIX)/cmdline-tools/latest/bin/sdkmanager"
CMDLINE_TOOLS_URL := https://dl.google.com/android/repository/commandlinetools-linux-$(CMDLINE_TOOLS_BUILD)_latest.zip
NVM_ROOT := $(HOME)/.nvm/versions/node
NODE_DIR := $(NVM_ROOT)/v$(NODE_VERSION)
endif
ADB := $(if $(ADB),$(ADB),adb)
# LOCAL_BIN first on PATH for every recipe below, on both branches - see the ROOT/LOCAL_BIN
# comment near the top. Harmless before `make install-tools` has ever been run (the directory is
# just empty, so nothing resolves from it and PATH lookups fall through to the rest of PATH as
# before).
export PATH := $(LOCAL_BIN):$(PATH)

# Invoked directly (not via the .local/bin/npm shim, which may not exist yet) so install-tools-vite
# doesn't depend on install-tools-shims having already run.
NPM_CLI := "$(NODE_DIR)/node.exe" "$(NODE_DIR)/node_modules/npm/bin/npm-cli.js"

help: ## Show this help
	@echo "Panopticon repo tasks:"
	@echo ""
	@awk 'BEGIN { FS = ":.*##" } /^[a-zA-Z0-9_-]+:.*##/ { gsub(/^ /, "", $$2); printf "  make %-18s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@echo ""
	@echo "  Target a specific device (needed only if more than one is connected):"
	@echo "  make run-phone ADB_SERIAL=<serial> (see 'adb devices -l' for available serials)."

## Tooling

install-tools: install-tools-nvm-node install-tools-wails install-tools-vite install-tools-shims install-tools-android-sdk ## Install every tool dependency (node/npm, wails, vite privately under .local/; Android SDK machine-wide)
	@echo ""
	@echo "==> Tools ready. .local/bin is prepended to PATH for every other 'make' rule automatically -"
	@echo "    nothing to source/activate. Safe to re-run any time .nvmrc or a tool version changes."

install-tools-nvm-node:
	@command -v nvm >/dev/null 2>&1 || { \
		echo "nvm not found on PATH - install it, then re-run 'make install-tools':" >&2; \
		echo "  winget install CoreyButler.NVMforWindows" >&2; \
		echo "  (or: https://github.com/coreybutler/nvm-windows/releases)" >&2; \
		echo "nvm itself is left as a machine-wide install, like the Android SDK below - it's the" >&2; \
		echo "tool this Makefile uses to fetch the private, .nvmrc-pinned node this project runs." >&2; \
		exit 1; \
	}
	@[ -n "$(NODE_VERSION)" ] || { echo ".nvmrc missing or empty at repo root." >&2; exit 1; }
	@if [ -f "$(NODE_DIR)/node.exe" ] || [ -f "$(NODE_DIR)/bin/node" ]; then \
		echo "==> node $(NODE_VERSION) already installed via nvm at $(NODE_DIR)"; \
	else \
		echo "==> Installing node $(NODE_VERSION) via nvm into its own user-writable versioned"; \
		echo "    directory (not the machine-wide 'active' version - no elevation needed)"; \
		nvm install $(NODE_VERSION); \
	fi
	@[ -f "$(NODE_DIR)/node.exe" ] || [ -f "$(NODE_DIR)/bin/node" ] || { \
		echo "node not found at $(NODE_DIR) after 'nvm install $(NODE_VERSION)' - check 'nvm list'." >&2; exit 1; }
	@mkdir -p "$(LOCAL_BIN)"

install-tools-wails:
	@mkdir -p "$(LOCAL_GOBIN)"
	@echo "==> Installing wails CLI into $(LOCAL_GOBIN) (its own GOPATH under .local/go - doesn't"
	@echo "    touch \$$(go env GOPATH) or anything wails-related on this machine outside this repo)"
	GOPATH="$(ROOT)/.local/go" GOBIN="$(LOCAL_GOBIN)" go install github.com/wailsapp/wails/v2/cmd/wails@latest

install-tools-vite: install-tools-nvm-node
	@echo "==> Installing vite into $(LOCAL_NPM_PREFIX)/node_modules"
	$(NPM_CLI) install --prefix "$(LOCAL_NPM_PREFIX)" vite --no-save --no-fund --no-audit

install-tools-shims: install-tools-nvm-node
	@mkdir -p "$(LOCAL_BIN)"
	@printf '#!/bin/sh\nexec "$(NODE_DIR)/node.exe" "$$@"\n' > "$(LOCAL_BIN)/node"
	@printf '#!/bin/sh\nexec "$(NODE_DIR)/node.exe" "$(NODE_DIR)/node_modules/npm/bin/npm-cli.js" "$$@"\n' > "$(LOCAL_BIN)/npm"
	@printf '#!/bin/sh\nexec "$(NODE_DIR)/node.exe" "$(NODE_DIR)/node_modules/npm/bin/npx-cli.js" "$$@"\n' > "$(LOCAL_BIN)/npx"
	@printf '#!/bin/sh\nexec "$(NODE_DIR)/node.exe" "$(LOCAL_NPM_PREFIX)/node_modules/vite/bin/vite.js" "$$@"\n' > "$(LOCAL_BIN)/vite"
	@printf '#!/bin/sh\nexec "$(LOCAL_GOBIN)/wails.exe" "$$@"\n' > "$(LOCAL_BIN)/wails"
	@chmod +x "$(LOCAL_BIN)/node" "$(LOCAL_BIN)/npm" "$(LOCAL_BIN)/npx" "$(LOCAL_BIN)/vite" "$(LOCAL_BIN)/wails"
	@echo "==> Wrote node/npm/npx/vite/wails shims into $(LOCAL_BIN)"

# The one tool install-tools leaves at its normal machine-wide location instead of privatizing
# under .local/ - the SDK (platform-tools + one platform + one build-tools revision) runs well
# past a gigabyte, and every other project on the machine already expects to find it at the
# standard location, so duplicating it per-project would be pure waste for no isolation benefit
# (unlike node/go tools, nothing here is version-sensitive enough per-project to need it private).
install-tools-android-sdk:
	@if command -v "$(ADB)" >/dev/null 2>&1 || [ -f "$(ADB)" ]; then \
		echo "==> Android SDK already present ($(ADB)) - skipping (machine-wide install, not"; \
		echo "    privatized under .local/ - see the Makefile comment above this target for why)."; \
		exit 0; \
	fi; \
	echo "==> No Android SDK found - installing platform-tools + platforms;android-34 +"; \
	echo "    build-tools;34.0.0 to $(ANDROID_SDK_ROOT_POSIX) (machine-wide, shared with any"; \
	echo "    other Android project, same as a manual Android Studio/sdkmanager install)."; \
	mkdir -p "$(ANDROID_SDK_ROOT_POSIX)/cmdline-tools" "$(ROOT)/.local/tmp" || exit 1; \
	command -v curl >/dev/null 2>&1 || { echo "curl not found - can't download the SDK." >&2; exit 1; }; \
	command -v unzip >/dev/null 2>&1 || { echo "unzip not found - can't extract the SDK." >&2; exit 1; }; \
	curl -fsSL -o "$(ROOT)/.local/tmp/cmdline-tools.zip" "$(CMDLINE_TOOLS_URL)" || exit 1; \
	rm -rf "$(ROOT)/.local/tmp/extract"; \
	unzip -q "$(ROOT)/.local/tmp/cmdline-tools.zip" -d "$(ROOT)/.local/tmp/extract" || exit 1; \
	rm -rf "$(ANDROID_SDK_ROOT_POSIX)/cmdline-tools/latest"; \
	mv "$(ROOT)/.local/tmp/extract/cmdline-tools" "$(ANDROID_SDK_ROOT_POSIX)/cmdline-tools/latest"; \
	rm -rf "$(ROOT)/.local/tmp"; \
	yes | $(SDKMANAGER_INVOKE) --licenses >/dev/null; \
	$(SDKMANAGER_INVOKE) "platform-tools" "platforms;android-34" "build-tools;34.0.0"; \
	echo "==> Android SDK installed at $(ANDROID_SDK_ROOT_POSIX)."

# Prerequisite of every target below that runs an adb command against a specific device
# (install-phone, run-phone, device-info). With ADB_SERIAL unset, adb happily targets "whichever
# device" as long as exactly one is connected - but silently misbehaves (or just refuses) the
# moment a second device (or emulator) shows up, which is exactly the situation this project's own
# dev machine is in (see docs/QUIRKS.md's Pixel 6 + second-device notes). Catching that here, with
# a clear "set ADB_SERIAL" message, beats letting whichever adb command runs first fail confusingly.
check-adb-devices:
	@command -v "$(ADB)" >/dev/null 2>&1 || [ -f "$(ADB)" ] || { \
		echo "adb not found on PATH or at the usual Android SDK location." >&2; exit 1; }
	@if [ -z "$(ADB_SERIAL)" ]; then \
		devices_list="$(ROOT)/.local/tmp/adb-devices.txt"; \
		mkdir -p "$(ROOT)/.local/tmp"; \
		"$(ADB)" devices > "$$devices_list" 2>/dev/null; \
		count=$$(awk 'NR>1 && $$2=="device" {c++} END{print c+0}' "$$devices_list"); \
		rm -f "$$devices_list"; \
		if [ "$$count" -gt 1 ]; then \
			echo "More than one device is visible to adb - set ADB_SERIAL to target one:" >&2; \
			echo >&2; \
			"$(ADB)" devices -l >&2; \
			echo >&2; \
			echo "  make <target> ADB_SERIAL=<serial>" >&2; \
			exit 1; \
		fi; \
	fi

## Generate

# One .stamp per *.gen.json sidecar found anywhere under controller/ - e.g.
# controller/internal/dbstore/queries.gen.json, which drives sqlc+goose codegen for the SQLite
# store (see tools/dbstore, tools/go-deps, and that directory's own db.dbstore.json/README).
# Ported from ../morsel's Makefile (see its own comments for the fuller original) - go-deps get
# expands each sidecar's declared inputs into real file paths, so the .stamp file's prerequisite
# list is always accurate and `make generate` only reruns a generator whose actual inputs changed.
_GEN_JSON_FILES := $(shell find controller -name '*.gen.json' 2>/dev/null)
_GEN_STAMP_FILES := $(addsuffix .stamp,$(_GEN_JSON_FILES))

generate: $(_GEN_STAMP_FILES) ## Regenerate sqlc/goose-derived code (stamp-tracked; only reruns what changed)

# GOPATH/TMP/TEMP/GOCACHE are passed explicitly (not relied on via `export` above) because this
# $(shell ...) call runs at Makefile-parse time, before this make actually applies its own
# `export` lines to $(shell)'s environment (confirmed empirically - the same `export GOPATH` that
# reliably reaches every *recipe* shell below is invisible to a parse-time $(shell go ...) call).
# One more entry in the same "don't trust this make's env handling" family - see docs/QUIRKS.md.
define gen-stamp-rule
$(1): $(shell GOPATH="$(GOPATH)" TMP="$(TMP)" TEMP="$(TEMP)" GOCACHE="$(GOCACHE)" go run ./tools/go-deps get $(1:.stamp=))
	go run ./tools/go-deps gen $(1:.stamp=)

endef
$(foreach s,$(_GEN_STAMP_FILES),$(eval $(call gen-stamp-rule,$(s))))

## Build

build: build-phone build-controller ## Build both apps

build-phone: phone-app-local-properties ## Build the phone-app debug APK (./gradlew assembleDebug)
	@command -v java >/dev/null 2>&1 || { echo "java not found on PATH - install a JDK (17 recommended)." >&2; exit 1; }
	cd phone-app && $(GRADLEW) assembleDebug

# Gradle needs to be told the SDK location one way or another; the standard mechanism is this
# file (auto-written by Android Studio, normally gitignored, so a fresh checkout never has it).
# Relying on the ANDROID_HOME env var instead doesn't work under this make (env vars get stripped
# from spawned processes - see docs/QUIRKS.md), so this always regenerates it from the same
# ANDROID_SDK_ROOT_* this Makefile already resolves for install-tools-android-sdk. Cheap enough
# (one line) to just always rewrite rather than guard on staleness.
phone-app-local-properties:
	@mkdir -p phone-app
ifneq ($(ANDROID_SDK_ROOT_WIN),)
	@printf 'sdk.dir=%s\n' "$(ANDROID_SDK_ROOT_WIN)" > phone-app/local.properties
else
	@printf 'sdk.dir=%s\n' "$(ANDROID_SDK_ROOT_POSIX)" > phone-app/local.properties
endif

build-controller: generate ## Build the controller production binary (wails build)
	@command -v wails >/dev/null 2>&1 || { echo "wails not found on PATH - run 'make install-tools' (installs it privately under .local/), or: go install github.com/wailsapp/wails/v2/cmd/wails@latest" >&2; exit 1; }
	cd controller && wails build

## Install

install: install-phone install-controller ## Build and install/prepare both apps

install-phone: build-phone check-adb-devices ## Build + install the debug APK onto ADB_SERIAL (or the sole connected device)
	@if [ -n "$(ADB_SERIAL)" ]; then \
		devices_output="$$("$(ADB)" devices)"; \
		case "$$devices_output" in \
			*"$(ADB_SERIAL)"*"device"*) ;; \
			*) echo "$(ADB_SERIAL) not visible to adb (offline/disconnected?). If it just reconnected, try:" >&2; \
			   echo "  \"$(ADB)\" kill-server && \"$(ADB)\" start-server" >&2; \
			   echo "Currently visible to adb:" >&2; echo "$$devices_output" >&2; exit 1 ;; \
		esac; \
	fi
	"$(ADB)" $(ADB_SERIAL_FLAG) install -r phone-app/app/build/outputs/apk/debug/app-debug.apk

install-controller: build-controller ## Alias for build-controller (no separate OS install step in this slice)
	@echo "==> Controller binary ready at $(CONTROLLER_BIN)"
	@echo "    No OS-level install step yet in this vertical slice - run it directly, or 'make run-controller'."

## Run

run-phone: install-phone check-adb-devices ## Install + launch phone-app on ADB_SERIAL (or the sole connected device), granting camera/notification perms
	@echo "==> Granting camera + notification permissions (idempotent if already granted)"
	-"$(ADB)" $(ADB_SERIAL_FLAG) shell pm grant $(PHONE_PACKAGE) android.permission.CAMERA
	-"$(ADB)" $(ADB_SERIAL_FLAG) shell pm grant $(PHONE_PACKAGE) android.permission.POST_NOTIFICATIONS
	@echo "==> Launching $(PHONE_PACKAGE)"
	"$(ADB)" $(ADB_SERIAL_FLAG) shell am start -n $(PHONE_PACKAGE)/.MainActivity
	@echo "==> To reach the HTTP API from this machine: adb $(ADB_SERIAL_FLAG) forward tcp:8080 tcp:8080"
	@echo "    then curl http://127.0.0.1:8080/api/device (401 without a bearer token - pair first"
	@echo "    from the app's Connect tab, or POST /api/pair with a code generated there)."

run-controller: build-controller ## Launch the controller binary (tray icon; keeps running in background)
	@[ -f "$(CONTROLLER_BIN)" ] || { echo "$(CONTROLLER_BIN) not found after build - see build-controller output." >&2; exit 1; }
	@# Redirected to a log file, not inherited - otherwise this recipe (and `make` itself, and any
	@# pipe reading its output) blocks until the controller process exits, since a background `&`
	@# job still holds an inherited stdout/stderr pipe open.
	"$(CONTROLLER_BIN)" > controller/run-controller.log 2>&1 &
	@echo "==> Launched controller (tray icon should appear); it keeps running in the background."
	@echo "    stdout/stderr -> controller/run-controller.log. Quit it from the tray menu when done."

e2e: run-phone run-controller ## run-phone + run-controller together for a full end-to-end session

## Test

test: test-phone test-controller ## Run both test suites

test-phone: phone-app-local-properties ## Run phone-app JVM unit tests (./gradlew test)
	@command -v java >/dev/null 2>&1 || { echo "java not found on PATH - install a JDK (17 recommended)." >&2; exit 1; }
	cd phone-app && $(GRADLEW) test

test-controller: build-controller ## Run controller Go tests (go test ./...)
	@# Depends on build-controller because main.go's `//go:embed all:frontend/dist` makes `go test
	@# ./...`'s run of the top-level (main) package fail with "setup failed" unless frontend/dist
	@# already exists on disk - the actual test packages under internal/ are unaffected either way,
	@# but "go test ./..." reports a package-level FAIL (and make treats it as an overall failure)
	@# for the main package regardless.
	cd controller && go test ./...

## Misc

clean: clean-phone clean-controller ## Remove build output from both apps

clean-phone: ## Remove phone-app build output (./gradlew clean)
	cd phone-app && $(GRADLEW) clean

clean-controller: ## Remove controller build output (build/bin, frontend/dist)
	rm -rf controller/installer-src/bin controller/frontend/dist

device-info: check-adb-devices ## Print the target device's manufacturer/model/Android version (sanity check)
	"$(ADB)" $(ADB_SERIAL_FLAG) shell getprop ro.product.manufacturer
	"$(ADB)" $(ADB_SERIAL_FLAG) shell getprop ro.product.model
	"$(ADB)" $(ADB_SERIAL_FLAG) shell getprop ro.build.version.release
