#!/usr/bin/env python3
"""Poll a paired phone's /api/camera/health and append each snapshot to a JSONL log.

Reads the phone's base URL and bearer token straight out of the controller's SQLite DB, so
there is nothing to configure. Run it for the length of the soak, then read the last line (or
feed the file to the summary at the bottom).

    python collect-health.py --mark                 # open the window
    python collect-health.py --interval 30          # poll until Ctrl-C
    python collect-health.py --verdict              # did the guards hold?
    python collect-health.py --window evening --mark

There are no path or phone arguments. --window picks one of a fixed set of file pairs under
panopticon-data/health/ (already gitignored), and the controller DB path is a constant - so
nothing from the command line is ever joined into a path or into the URL that gets fetched.

The controller keeps that DB open with WAL, so it is copied before reading rather than opened
in place.
"""
import argparse
import datetime as dt
import json
import os
import re
import shutil
import sqlite3
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

# The controller's DB, relative to this file's home in tools/.
DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "panopticon-data", "panopticon.db")

# Where this script's own files live. Under the controller's runtime data dir, which is already
# gitignored, so soak logs never land in a commit.
DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "panopticon-data", "health")

# The only schemes a paired phone's base URL may use. The URL is read out of the controller's
# SQLite DB rather than typed here, so it is not trusted blindly: a DB that had been tampered
# with (or simply pointed at the wrong file) could otherwise steer urlopen at file:// and read
# an arbitrary local path back into the log, or at a scheme with side effects.
ALLOWED_SCHEMES = ("http", "https")

# Mirrors dbstore.GroupingGapMs: segments this close together are one clip, not two.
GROUPING_GAP_MS = 500


# The complete set of files this script will ever touch, one pair per evaluation window.
# A label from the command line is only ever used as a *key* into this table - it is never joined
# into a path, so the string that reaches open() is a literal defined here. That is deliberate:
# validating a user-supplied filename and then concatenating it is the pattern that keeps getting
# this wrong, and there is no reason to accept arbitrary names for a tool with two output files.
WINDOWS = {
    "default": {"samples": "health.jsonl", "marker": "eval-marker.json"},
    "evening": {"samples": "health-evening.jsonl", "marker": "eval-marker-evening.json"},
    "control": {"samples": "health-control.jsonl", "marker": "eval-marker-control.json"},
    "wakelock": {"samples": "health-wakelock.jsonl", "marker": "eval-marker-wakelock.json"},
}


def _data_file(label, which, must_exist):
    """The constant path for [which] file of the [label] window."""
    window = WINDOWS.get(label)
    if window is None:
        raise SystemExit("unknown window %r; expected one of: %s"
                         % (label, ", ".join(sorted(WINDOWS))))
    os.makedirs(DATA_DIR, exist_ok=True)
    path = os.path.join(DATA_DIR, window[which])
    if must_exist and not os.path.isfile(path):
        raise SystemExit("no %s file for window %r yet (%s)" % (which, label, path))
    return path


def phone_rows():
    """The paired phones, read from a snapshot of the controller's DB.

    The DB path is fixed rather than a flag: it is always the controller's, next to this repo,
    and there is no second one worth pointing at.
    """
    db_path = os.path.normpath(DB)
    if not os.path.isfile(db_path):
        raise SystemExit("controller DB not found: %s" % db_path)
    d = tempfile.mkdtemp()
    for ext in ("", "-wal", "-shm"):
        side = db_path + ext
        # Only the DB's own sidecars, and only if they really are files.
        if ext and not os.path.isfile(side):
            continue
        shutil.copy(side, os.path.join(d, "panopticon.db" + ext))
    con = sqlite3.connect(os.path.join(d, "panopticon.db"))
    return list(con.execute("select id, name, base_url, token from phones order by last_seen_ms desc"))


def fetch(base_url, token, path="/api/camera/health", timeout=15):
    url = base_url.rstrip("/") + path
    parts = urllib.parse.urlsplit(url)
    if parts.scheme not in ALLOWED_SCHEMES or not parts.netloc:
        raise SystemExit("refusing to fetch %r: expected an %s URL for the phone"
                         % (url, "/".join(ALLOWED_SCHEMES)))
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(req, timeout=timeout) as r:  # noqa: S310 - scheme checked above
        return json.loads(r.read().decode())


def summarize(label):
    """Print the last snapshot's headline numbers — what the soak is actually for."""
    last = None
    with open(_data_file(label, "samples", must_exist=True), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                last = json.loads(line)
    if not last:
        print("no samples yet for window", label)
        return
    snap = last["health"]
    print("sampled at", dt.datetime.fromtimestamp(last["atMs"] / 1000))
    print("process uptime %.1f min" % (snap["processUptimeMs"] / 60000.0))
    for key, cam in snap.get("cameras", {}).items():
        print("\n===", key, "===")
        print("  runs=%s ended=%s  meanUp=%.1fs  min=%.1fs max=%.1fs  current=%.1fs" % (
            cam["runs"], cam["runsEnded"], cam["meanUpMs"] / 1000.0,
            cam["upMsMin"] / 1000.0, cam["upMsMax"] / 1000.0, cam["currentUpMs"] / 1000.0))
        for reason, v in sorted(cam.get("meanUpMsByEndReason", {}).items()):
            print("    %-18s n=%-4s meanUp=%.1fs" % (reason, v["count"], v["meanUpMs"] / 1000.0))
        g = cam.get("gauges", {})
        print("  -- at the last failure --")
        for k in ("lastFailure.reason", "lastFailure.cameraFrameAgeMs",
                  "lastFailure.captureResultAgeMs", "lastFailure.encodedOutputAgeMs",
                  "lastFailure.measuredFps", "lastFailure.thermalLevel",
                  "lastFailure.thermalSeverity", "lastFailure.thermalHeadroom",
                  "lastFailure.thermalPeakThisRun"):
            if k in g:
                print("    %-34s %s" % (k.split(".", 1)[1], g[k]))
        c = cam.get("counters", {})
        print("  -- capture-level faults --")
        for k in ("captureFailed", "captureBufferLost", "captureSequenceAborted"):
            print("    %-24s %s" % (k, c.get(k, 0)))
        print("  -- motion gate --")
        for k in ("motion.framesAnalysed", "motion.framesSuppressed",
                  "motion.disturbances", "motion.disturbancesExpired"):
            if k in g:
                print("    %-24s %s" % (k.split(".", 1)[1], g[k]))
        thermal = [e for e in cam.get("recent", []) if e["event"] == "thermalChanged"]
        if thermal:
            print("  -- thermal changes (most recent last) --")
            for e in thermal[-8:]:
                print("    %s  %s" % (dt.datetime.fromtimestamp(e["atMs"] / 1000).strftime("%H:%M:%S"),
                                      e.get("detail", "")))


def mark(label):
    """Open an evaluation window: remember the wall clock and the counters as they stand now.

    The counters are cumulative for the life of the app process, so a window that began after the
    app started has to subtract a baseline or it inherits every restart that came before it -
    including, in the first attempt at this, a run's worth of mode switches caused by the
    controller's live preview stealing the camera.
    """
    pid, name, base, token = _phone()
    health = fetch(base, token)
    cam = next((v for k, v in health.get("cameras", {}).items() if k.startswith("record:")), None)
    segs = fetch(base, token, "/api/segments?since=0")["segments"]
    marker = {
        "startedAtMs": int(time.time() * 1000),
        "phone": pid,
        "baselineRuns": cam["runs"] if cam else 0,
        "baselineSegments": len(segs),
    }
    path = _data_file(label, "marker", must_exist=False)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(marker, f, indent=2)
    print("evaluation window opened at %s for %s (%s)"
          % (dt.datetime.fromtimestamp(marker["startedAtMs"] / 1000).strftime("%H:%M:%S"), name, pid))
    print("  baseline runs=%s  segments on phone=%s" % (marker["baselineRuns"], marker["baselineSegments"]))
    print("  marker -> %s" % path)


def verdict(label):
    """Did the false-clip guards hold?

    The pipeline still stalls and restarts — that is a separate, unfixed problem. What this asks
    is narrower and independent of *why* it stalls: when a restart happens, does it manufacture a
    clip? Before the guards, essentially every restart did.

    The discriminator is pre-roll, the same one that identified the bug. `segmentFileName()`
    stamps wall-clock at muxer-open, so `filename time − createdAtMs` recovers the pre-roll the
    ring actually supplied. A genuine motion event opens with ~3s of history behind it; a clip
    triggered by a just-restarted pipeline has an empty ring and opens with ~0. So a near-zero
    pre-roll clip is a restart artefact, and that count is what has to be zero — real motion can
    come and go during the run without muddying the result.
    """
    with open(_data_file(label, "marker", must_exist=True), encoding="utf-8") as f:
        marker = json.load(f)
    pid, name, base, token = _phone(marker.get("phone"))

    started = marker["startedAtMs"]
    segs = [s for s in fetch(base, token, "/api/segments?since=%d" % started)["segments"]]
    health = fetch(base, token)
    cam = next((v for k, v in health.get("cameras", {}).items() if k.startswith("record:")), None)

    def preroll_s(seg):
        m = re.search(r"clip_(\d{8})_(\d{6})_", seg["filename"])
        if not m:
            return None
        ts = dt.datetime.strptime(m.group(1) + m.group(2), "%Y%m%d%H%M%S").timestamp() * 1000
        return (ts - seg["createdAtMs"]) / 1000.0

    # Only the *first* segment of each clip carries a meaningful pre-roll. A clip that runs past
    # rotationIntervalMs is rolled by rollMuxer(), which stamps a fresh filename but anchors the
    # new segment at the current frame rather than priming from the ring — so every continuation
    # segment reads as ~0 pre-roll by construction, and scoring raw segments would report a long
    # genuine motion event as a string of false triggers. Group them the way the controller does
    # (contiguous within GROUPING_GAP_MS) and score the opener.
    segs.sort(key=lambda s: s["createdAtMs"])
    firsts, prev_end = [], None
    for s in segs:
        if prev_end is None or (s["createdAtMs"] - prev_end) > GROUPING_GAP_MS:
            firsts.append(s)
        prev_end = s.get("endMs") or (s["createdAtMs"] + s.get("durationMs", 0))
    scored = [(s, preroll_s(s)) for s in firsts]
    false_trigger = [s for s, p in scored if p is not None and p < 2.0]
    genuine = [s for s, p in scored if p is not None and p >= 2.0]

    mins = (time.time() * 1000 - started) / 60000.0
    print("window: %.1f min since %s" % (mins, dt.datetime.fromtimestamp(started / 1000).strftime("%H:%M:%S")))
    if not cam:
        print("no record-mode camera in health — is the phone in RECORD?")
        return
    # The counters are process-lifetime. If the app died and came back inside the window - which
    # a thermal shutdown will do - they restarted from zero, the baseline is meaningless, and
    # subtracting it silently reports "no restarts" for a window that was full of them. Detect it
    # by the app's own uptime being younger than the window, and say so loudly rather than
    # reporting a clean number that is a lie.
    app_up_min = health.get("processUptimeMs", 0) / 60000.0
    baseline_runs = marker.get("baselineRuns", 1)
    reset = app_up_min < mins - 0.5 or cam["runs"] < baseline_runs
    if reset:
        print("\n  *** the phone app restarted %.1f min into this window (uptime %.1f min). ***"
              % (mins - app_up_min, app_up_min))
        print("      Counters reset with it, so everything below covers only since then -")
        print("      not the full window. Check the samples log for what came before.")
        baseline_runs = 1
    restarts = max(0, cam["runs"] - baseline_runs)
    print("\n-- did the pipeline still stall? (the thing NOT fixed) --")
    print("  runs=%d  restarts=%d  (%.1f/hr)" % (cam["runs"], restarts, restarts / (mins / 60.0) if mins else 0))
    for r, v in sorted(cam.get("meanUpMsByEndReason", {}).items()):
        print("    %-16s n=%-3s meanUp=%.0fs" % (r, v["count"], v["meanUpMs"] / 1000.0))

    print("\n-- did those restarts manufacture clips? (the thing fixed) --")
    print("  segments recorded:      %d  (in %d clip(s))" % (len(segs), len(firsts)))
    print("  ...with ~0 pre-roll:    %d   <== restart artefacts; must be 0" % len(false_trigger))
    print("  ...with full pre-roll:  %d   (genuine motion, fine)" % len(genuine))
    for s, p in scored:
        print("      %-34s preroll=%5.2fs  %s" % (s["filename"], p if p is not None else -1,
                                                  "FALSE TRIGGER" if p is not None and p < 2.0 else "genuine"))

    g = cam.get("gauges", {})
    print("\n-- were the guards actually engaging? --")
    for k, label in (("motion.framesAnalysed", "frames analysed"),
                     ("motion.framesSuppressed", "frames suppressed"),
                     ("motion.disturbances", "disturbances begun"),
                     ("motion.disturbancesExpired", "disturbances EXPIRED (want 0)"),
                     ("zoom.readable", "zoom results readable"),
                     ("zoom.unreadable", "zoom results unreadable")):
        print("  %-32s %s" % (label, g.get(k, "n/a")))

    # Read thermal LIVE from /api/status, not from the gauges. The gauges are only written while
    # a pipeline is running, so in STANDBY they freeze at their last value and a stale "critical"
    # is indistinguishable from a live one - which is exactly how a fully cooled phone once got
    # reported here as still hot.
    print("\n-- thermal (live from /api/status) --")
    live = fetch(base, token, "/api/status").get("thermal", {})
    print("  %-32s %s (severity %s)" % ("level", live.get("level", "n/a"), live.get("severity", "?")))
    print("  %-32s %s" % ("headroom", live.get("headroom", "n/a")))
    print("  %-32s %s" % ("peakSeverityThisRun", g.get("thermal.peakSeverityThisRun", "n/a")))
    stamped = g.get("thermal.sampledAtMs")
    if stamped:
        age = (time.time() * 1000 - float(stamped)) / 1000.0
        note = "  <== STALE, pipeline not running" if age > 60 else ""
        print("  %-32s %s, %.0fs ago%s" % ("last pipeline sample", g.get("thermal.level", "?"), age, note))
    c = cam.get("counters", {})
    print("  %-32s %s" % ("captureBufferLost", c.get("captureBufferLost", 0)))
    print("  %-32s %s" % ("captureFailed", c.get("captureFailed", 0)))

    print("\nVERDICT: ", end="")
    if restarts == 0:
        print("INCONCLUSIVE — no restart has happened yet, so the guards were never exercised.")
    elif false_trigger:
        print("FAILED — %d restart(s) still produced a clip." % len(false_trigger))
    else:
        print("HOLDING — %d restart(s), zero clips manufactured." % restarts)


def _phone(prefer_id=None):
    """The phone to talk to.

    There is no --phone flag: the id would come from argv and flow into the base URL that reaches
    urlopen. [prefer_id] comes from a marker file this script itself wrote, so a window keeps
    reporting on the phone it was opened against even if another becomes more recently seen.
    """
    rows = phone_rows()
    if not rows:
        raise SystemExit("no paired phones in " + os.path.normpath(DB))
    if prefer_id:
        for r in rows:
            if r[0] == prefer_id:
                return r
        raise SystemExit("phone %s from the marker is no longer paired" % prefer_id)
    return rows[0]


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--window", default="default", choices=sorted(WINDOWS),
                    help="which evaluation window's files to use")
    ap.add_argument("--interval", type=int, default=60)
    ap.add_argument("--mark", action="store_true", help="open the window and write its marker")
    ap.add_argument("--verdict", action="store_true", help="did the false-clip guards hold?")
    ap.add_argument("--summarize", action="store_true", help="headline numbers from the last sample")
    args = ap.parse_args()

    if args.mark:
        mark(args.window)
        return
    if args.verdict:
        verdict(args.window)
        return
    if args.summarize:
        summarize(args.window)
        return

    pid, name, base, token = _phone()
    out_path = _data_file(args.window, "samples", must_exist=False)
    print("polling %s (%s) at %s every %ds -> %s" % (name, pid, base, args.interval, out_path))
    print("Ctrl-C to stop; then:  python %s --window %s --summarize"
          % (os.path.basename(__file__), args.window))

    n = 0
    while True:
        try:
            snap = fetch(base, token)
            with open(out_path, "a", encoding="utf-8") as f:
                f.write(json.dumps({"atMs": int(time.time() * 1000), "health": snap}) + "\n")
            n += 1
            cams = snap.get("cameras", {})
            rec = next((v for k, v in cams.items() if k.startswith("record:")), None)
            if rec:
                print("[%s] #%d runs=%s meanUp=%.0fs thermal=%s" % (
                    dt.datetime.now().strftime("%H:%M:%S"), n, rec["runs"],
                    rec["meanUpMs"] / 1000.0,
                    rec.get("gauges", {}).get("thermal.level", "n/a")))
            else:
                print("[%s] #%d no record-mode camera yet (is the phone in RECORD?)" % (
                    dt.datetime.now().strftime("%H:%M:%S"), n))
        except urllib.error.HTTPError as e:
            print("HTTP %s — %s" % (e.code, "endpoint missing? install the new APK" if e.code == 404 else e.reason))
        except Exception as e:  # noqa: BLE001 - a soak script should outlive a blip
            print("poll failed:", e)
        time.sleep(args.interval)


if __name__ == "__main__":
    main()
