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
        device-info

.DEFAULT_GOAL := help

ADB_SERIAL ?= 1C281FDF6005H0
PHONE_PACKAGE := com.panopticon.phoneapp

# On Windows, route gradlew through the real cmd.exe rather than invoking it directly from this
# MSYS2 shell - see docs/QUIRKS.md ("gradlew.bat invoked bare through cmd.exe //c isn't found")
# and panopticon-prototype/QUIRKS.md ("gradlew/npm misbehave when launched directly from this
# MSYS2 shell") for why. Note the explicit "./gradlew.bat" (not a bare "gradlew.bat") - this
# project's own QUIRKS.md found the bare form isn't recognized even with the right cwd.
ifneq (,$(findstring MINGW,$(shell uname -s))$(findstring MSYS,$(shell uname -s))$(findstring CYGWIN,$(shell uname -s)))
CMDEXE := /c/Windows/System32/cmd.exe //c
GRADLEW := $(CMDEXE) .\\gradlew.bat
CONTROLLER_BIN := controller/build/bin/panopticon-controller.exe
# The Android SDK installer commonly leaves platform-tools (adb) off PATH even when it's present -
# fall back to its default Windows install location before giving up. Reading that location via
# $LOCALAPPDATA doesn't work under this MSYS2 make (env vars get stripped from spawned processes -
# see panopticon-prototype/QUIRKS.md), so this globs the filesystem directly instead.
ifeq (,$(shell command -v adb 2>/dev/null))
ADB := $(firstword $(shell ls -1 /c/Users/*/AppData/Local/Android/Sdk/platform-tools/adb.exe /c/Users/*/AppData/Local/Android/sdk/platform-tools/adb.exe 2>/dev/null))
endif
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
CONTROLLER_BIN := controller/build/bin/panopticon-controller
endif
ADB := $(if $(ADB),$(ADB),adb)

help: ## Show this help
	@echo "Panopticon repo tasks:"
	@echo ""
	@awk 'BEGIN { FS = ":.*##" } /^[a-zA-Z0-9_-]+:.*##/ { gsub(/^ /, "", $$2); printf "  make %-18s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@echo ""
	@echo "  Override the phone-app target device: make run-phone ADB_SERIAL=<serial>"
	@echo "  (see 'adb devices -l' if you have more than one attached)."

## Build

build: build-phone build-controller ## Build both apps

build-phone: ## Build the phone-app debug APK (./gradlew assembleDebug)
	@command -v java >/dev/null 2>&1 || { echo "java not found on PATH - install a JDK (17 recommended)." >&2; exit 1; }
	cd phone-app && $(GRADLEW) assembleDebug

build-controller: ## Build the controller production binary (wails build)
	@command -v wails >/dev/null 2>&1 || { echo "wails not found on PATH - install: go install github.com/wailsapp/wails/v2/cmd/wails@latest (then ensure \$$(go env GOPATH)/bin is on PATH)" >&2; exit 1; }
	cd controller && wails build

## Install

install: install-phone install-controller ## Build and install/prepare both apps

install-phone: build-phone ## Build + install the debug APK onto ADB_SERIAL (default: this repo's test Pixel 6)
	@command -v "$(ADB)" >/dev/null 2>&1 || [ -f "$(ADB)" ] || { \
		echo "adb not found on PATH or at the usual Android SDK location - install Android SDK platform-tools." >&2; exit 1; }
	@devices_output="$$("$(ADB)" devices)"; \
	case "$$devices_output" in \
		*"$(ADB_SERIAL)"*"device"*) ;; \
		*) echo "$(ADB_SERIAL) not visible to adb (offline/disconnected?). If it just reconnected, try:" >&2; \
		   echo "  \"$(ADB)\" kill-server && \"$(ADB)\" start-server" >&2; \
		   echo "Currently visible to adb:" >&2; echo "$$devices_output" >&2; exit 1 ;; \
	esac
	"$(ADB)" -s $(ADB_SERIAL) install -r phone-app/app/build/outputs/apk/debug/app-debug.apk

install-controller: build-controller ## Alias for build-controller (no separate OS install step in this slice)
	@echo "==> Controller binary ready at $(CONTROLLER_BIN)"
	@echo "    No OS-level install step yet in this vertical slice - run it directly, or 'make run-controller'."

## Run

run-phone: install-phone ## Install + launch phone-app on ADB_SERIAL, granting camera/notification perms
	@echo "==> Granting camera + notification permissions (idempotent if already granted)"
	-"$(ADB)" -s $(ADB_SERIAL) shell pm grant $(PHONE_PACKAGE) android.permission.CAMERA
	-"$(ADB)" -s $(ADB_SERIAL) shell pm grant $(PHONE_PACKAGE) android.permission.POST_NOTIFICATIONS
	@echo "==> Launching $(PHONE_PACKAGE)"
	"$(ADB)" -s $(ADB_SERIAL) shell am start -n $(PHONE_PACKAGE)/.MainActivity
	@echo "==> To reach the HTTP API from this machine: adb -s $(ADB_SERIAL) forward tcp:8080 tcp:8080"
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

test-phone: ## Run phone-app JVM unit tests (./gradlew test)
	@command -v java >/dev/null 2>&1 || { echo "java not found on PATH - install a JDK (17 recommended)." >&2; exit 1; }
	cd phone-app && $(GRADLEW) test

test-controller: ## Run controller Go tests (go test ./...)
	cd controller && go test ./...

## Misc

clean: clean-phone clean-controller ## Remove build output from both apps

clean-phone: ## Remove phone-app build output (./gradlew clean)
	cd phone-app && $(GRADLEW) clean

clean-controller: ## Remove controller build output (build/bin, frontend/dist)
	rm -rf controller/build/bin controller/frontend/dist

device-info: ## Print the target device's manufacturer/model/Android version (sanity check)
	@command -v "$(ADB)" >/dev/null 2>&1 || [ -f "$(ADB)" ] || { \
		echo "adb not found on PATH or at the usual Android SDK location." >&2; exit 1; }
	"$(ADB)" -s $(ADB_SERIAL) shell getprop ro.product.manufacturer
	"$(ADB)" -s $(ADB_SERIAL) shell getprop ro.product.model
	"$(ADB)" -s $(ADB_SERIAL) shell getprop ro.build.version.release
