# phone-app build/install/run tasks.
#
# Conventions carried forward from ../panopticon-prototype/Makefile (see its own comments and
# ../../docs/QUIRKS.md "Development tooling (Windows)" for why): gradlew is routed through the
# real cmd.exe rather than invoked directly from this MSYS2 shell, and adb falls back to a
# filesystem glob if it's not on PATH. One addition specific to this project (see this project's
# own docs/QUIRKS.md): gradlew must be invoked as "./gradlew.bat", not a bare "gradlew.bat", even
# from inside cmd.exe with the right cwd - a bare name was reliably "not recognized" here.

.PHONY: help build install run test clean device-info

ADB_SERIAL ?= 1C281FDF6005H0
PACKAGE := com.panopticon.phoneapp

ifneq (,$(findstring MINGW,$(shell uname -s))$(findstring MSYS,$(shell uname -s))$(findstring CYGWIN,$(shell uname -s)))
CMDEXE := /c/Windows/System32/cmd.exe //c
GRADLEW := $(CMDEXE) .\gradlew.bat
# The Android SDK installer commonly leaves platform-tools (adb) off PATH even when it's present -
# fall back to its default Windows install location before giving up. Reading that location via
# $LOCALAPPDATA doesn't work under this MSYS2 make (env vars get stripped from spawned processes -
# see ../panopticon-prototype/QUIRKS.md), so this globs the filesystem directly instead.
ifeq (,$(shell command -v adb 2>/dev/null))
ADB := $(firstword $(shell ls -1 /c/Users/*/AppData/Local/Android/Sdk/platform-tools/adb.exe /c/Users/*/AppData/Local/Android/sdk/platform-tools/adb.exe 2>/dev/null))
endif
else
GRADLEW := ./gradlew
endif
ADB := $(if $(ADB),$(ADB),adb)

help:
	@echo "phone-app tasks:"
	@echo "  make build          Build the debug APK (./gradlew assembleDebug)"
	@echo "  make install        Build + install the debug APK onto ADB_SERIAL (default $(ADB_SERIAL))"
	@echo "  make run            install + launch MainActivity + grant camera/notification perms"
	@echo "  make test           Run JVM unit tests (./gradlew test)"
	@echo "  make device-info    Print the target device's model/Android version (sanity check)"
	@echo "  make clean          Remove build output"
	@echo ""
	@echo "  Override the target device: make install ADB_SERIAL=<serial>"
	@echo "  (see 'adb devices -l' if you have more than one attached)."

device-info:
	@command -v "$(ADB)" >/dev/null 2>&1 || [ -f "$(ADB)" ] || { \
		echo "adb not found on PATH or at the usual Android SDK location." >&2; exit 1; }
	"$(ADB)" -s $(ADB_SERIAL) shell getprop ro.product.manufacturer
	"$(ADB)" -s $(ADB_SERIAL) shell getprop ro.product.model
	"$(ADB)" -s $(ADB_SERIAL) shell getprop ro.build.version.release

build:
	@command -v java >/dev/null 2>&1 || { echo "java not found on PATH - install a JDK (17 recommended)." >&2; exit 1; }
	$(GRADLEW) assembleDebug

install: build
	@command -v "$(ADB)" >/dev/null 2>&1 || [ -f "$(ADB)" ] || { \
		echo "adb not found on PATH or at the usual Android SDK location - install Android SDK platform-tools." >&2; exit 1; }
	@devices_output="$$("$(ADB)" devices)"; \
	case "$$devices_output" in \
		*"$(ADB_SERIAL)"*"device"*) ;; \
		*) echo "$(ADB_SERIAL) not visible to adb (offline/disconnected?). If it just reconnected, try:" >&2; \
		   echo "  \"$(ADB)\" kill-server && \"$(ADB)\" start-server" >&2; \
		   echo "Currently visible to adb:" >&2; echo "$$devices_output" >&2; exit 1 ;; \
	esac
	"$(ADB)" -s $(ADB_SERIAL) install -r app/build/outputs/apk/debug/app-debug.apk

run: install
	@echo "==> Granting camera + notification permissions (idempotent if already granted)"
	-"$(ADB)" -s $(ADB_SERIAL) shell pm grant $(PACKAGE) android.permission.CAMERA
	-"$(ADB)" -s $(ADB_SERIAL) shell pm grant $(PACKAGE) android.permission.POST_NOTIFICATIONS
	@echo "==> Launching $(PACKAGE)"
	"$(ADB)" -s $(ADB_SERIAL) shell am start -n $(PACKAGE)/.MainActivity
	@echo "==> To reach the HTTP API from this machine: adb -s $(ADB_SERIAL) forward tcp:8080 tcp:8080"
	@echo "    then curl http://127.0.0.1:8080/api/device (401 without a bearer token - pair first"
	@echo "    from the app's Connect tab, or POST /api/pair with a code generated there)."

test:
	@command -v java >/dev/null 2>&1 || { echo "java not found on PATH - install a JDK (17 recommended)." >&2; exit 1; }
	$(GRADLEW) test

clean:
	$(GRADLEW) clean
