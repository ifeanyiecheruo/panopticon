# Android foreground service + HTTP server

Device context and the "Reconfirmed" / "Carried forward" convention: see [`README.md`](README.md).

## Foreground service

### `startForeground`'s 3-arg (foregroundServiceType) overload doesn't exist before API 29
**Assumed:** calling the 3-argument `startForeground(id, notification, foregroundServiceType)`
overload is safe on any API level as long as the *type value itself* is only supplied on API 29+
(`if (SDK_INT >= Q) FOREGROUND_SERVICE_TYPE_CAMERA else 0`) - i.e. that the guard only needed to
protect the *value*, not the *method call*.
**Actually:** confirmed via a real crash on a BLU G5 (Android 9, API 28): the 3-arg overload
doesn't exist at all in the `Service` class before API 29 (Q) - calling it unconditionally throws
`java.lang.NoSuchMethodError: No virtual method startForeground(ILandroid/app/Notification;I)V`
immediately on `onStartCommand`, and since the service is `START_STICKY`, Android just kept
restarting and re-crashing it in a loop.
**Workaround:** branch on the overload itself, not just the argument value - call the 2-arg
`startForeground(id, notification)` below API 29, and only use the 3-arg
`foregroundServiceType`-carrying overload on API 29+.
(Same class of ART-verification trap as the `NoSuchFieldError`-behind-a-guard entry in
[`calibration-zoom.md`](calibration-zoom.md) - a method/field reference resolved when the method
is verified, not when the guarded line runs.)
**Where:** `phone-app/app/src/main/kotlin/com/panopticon/phoneapp/service/PanopticonService.kt`
(`onStartCommand`).

### A partial wake lock doesn't keep Wi-Fi up: the BLU G5 drops off the network while dozing
**Assumed:** the service's partial wake lock (held for the camera pipeline) plus the system's "keep
Wi-Fi on during sleep" setting (`wifi_sleep_policy` = 2, never) would keep the phone reachable over
Wi-Fi with the screen off.
**Actually:** on the BLU G5 (Android 9, API 28), with the screen off and the device dozing, Wi-Fi
disconnected entirely (`Supplicant state: DISCONNECTED`, no IPv4 on `wlan0`) and stayed down until
the screen was woken. After waking it re-associated with the same address but sometimes with no
default route: the app's HTTP server was up (it answered through `adb forward`) but neither the PC
nor the phone could ping the other. The controller logged `phone unreachable` throughout.
Toggling Wi-Fi (`adb shell svc wifi disable` / `enable`) restored the route.
**Workaround:** the service also holds a `WifiManager.WifiLock` for its whole lifetime, alongside
the wake lock. `WIFI_MODE_FULL_HIGH_PERF`, not `FULL_LOW_LATENCY`, which only takes effect in the
foreground with the screen on. HIGH_PERF is deprecated from API 34.
**Where:** `phone-app/app/src/main/kotlin/com/panopticon/phoneapp/service/PanopticonService.kt`
(`acquireWifiLock`).

## HTTP server

### Bind-retry-on-crash-loop - implemented, not stress-tested via an induced crash
**Old prototype's claim:** a crashed process's socket isn't released fast enough for Android's
service auto-restart, causing a repeating `BindException` crash loop without a generous retry
budget.
**What we did:** `PanopticonHttpServer.start()` retries `embeddedServer(...).start()` on
`BindException` up to 20 attempts / ~30s total, same budget as the old prototype.
**Not tested:** we didn't deliberately crash the service mid-session to confirm the retry budget
is actually sufficient on this device - the server bound successfully on its very first attempt
every time we started/restarted the app during this project. Treat as "implemented defensively,
carried forward," not "reconfirmed."
**Where:** `phone-app/app/src/main/kotlin/com/panopticon/phoneapp/http/PanopticonHttpServer.kt`.

### End-to-end verified on real hardware (not a quirk, a confirmation)
Via `adb forward tcp:8080 tcp:8080` + `curl` against the running Pixel 6: bearer-token auth gate
(401 on missing/invalid token, every route but `POST /api/pair`), invite redemption
(`POST /api/pair?invite=...`), `GET /api/device`, `/api/build-info`, `/api/status`, `/api/config`,
`/api/mode` (both directions, including the recording pipeline correctly stopping/restarting
around a `live`->`record` round trip), `GET /api/clips` (delta list), full-file and byte-`Range`
downloads of `/api/clips/:filename/file` (`206 Partial Content` with a correct `Content-Range`),
`/api/clips/:filename/thumbnail`, `DELETE /api/clips/:filename` (with a following `404`), and
self-unpair via `DELETE /api/pair` (with a following `401` on the now-revoked token). All matched
`docs/design/http-api.md`'s documented shapes and status codes.
