#!/usr/bin/env python3
"""Capture force-stop lifecycle evidence with Discord's own profile already open.

Prepare a matching playing source and accepted authenticated publisher activity first.
Screenshots require human review; this does not prove guaranteed server expiry.
"""
import argparse
import datetime
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time

APP = "cc.lem0n.androidrichpresence"
SERVICE = APP + "/.youtube.YouTubeNotificationListener"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--evidence-dir", required=True, type=Path)
    parser.add_argument("--source-package", default="com.google.android.youtube")
    parser.add_argument("--observe-seconds", default=60, type=int, choices=range(1, 121), metavar="1..120")
    parser.add_argument("--ready-marker", default="authenticated=true",
                        help="Exact safe readiness marker required in the service dump")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", args.source_package):
        parser.error("--source-package must be an Android package name")
    if args.source_package == APP or not args.ready_marker.strip():
        parser.error("Source must differ from publisher; readiness marker must be nonempty")
    args.evidence_dir.mkdir(parents=True, exist_ok=True)
    if (args.evidence_dir / "result.json").exists():
        parser.error("Use a fresh evidence directory to preserve previous results")
    report = dict(serial=args.serial, sourcePackage=args.source_package, publisherPackage=APP,
                  observeSeconds=args.observe_seconds, readyMarker=args.ready_marker,
                  success=False, actionSuccess=False, profileVisualReviewRequired=True,
                  scope="Force-stop action and process observations; no guaranteed server expiry or automatic profile verdict.",
                  actions=[], observations=[], screenshots=[])

    def stamp():
        return dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(), monotonic=time.monotonic())

    def run(remote, *, screenshot=False, allowed=(0,)):
        command = ["adb", "-s", args.serial]
        command += ["exec-out", "screencap", "-p"] if screenshot else ["shell", shlex.join(remote)]
        entry = dict(argv=command, **stamp())
        report["actions"].append(entry)
        try:
            result = subprocess.run(command, capture_output=True, timeout=10)
        except (subprocess.TimeoutExpired, OSError) as error:
            entry.update(error=type(error).__name__, returncode=None, finished=stamp())
            raise RuntimeError("ADB failed or timed out; see action log") from error
        entry.update(returncode=result.returncode, finished=stamp())
        if result.returncode not in allowed or result.stderr.strip():
            raise RuntimeError("ADB command failed or emitted stderr; see recorded return code")
        return result

    def shell(*remote, allowed=(0,)):
        return run(remote, allowed=allowed).stdout.decode("utf-8", errors="replace")

    def screenshot(name):
        data = run((), screenshot=True).stdout
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise RuntimeError("Screenshot output is not PNG")
        path = args.evidence_dir / (name + ".png")
        path.write_bytes(data)
        report["screenshots"].append(dict(path=str(path), **stamp()))

    def observe():
        result = run(("pidof", APP), allowed=(0, 1))
        raw = result.stdout.decode().strip()
        if result.returncode == 1 and not raw:
            absent = True
        elif result.returncode == 0 and re.fullmatch(r"\d+(?:\s+\d+)*", raw):
            absent = False
        else:
            raise RuntimeError("Unexpected pidof response; cannot establish publisher absence")
        report["observations"].append(dict(publisherAbsent=absent, pids=raw.split(), **stamp()))
        if not absent:
            raise RuntimeError("Publisher process reappeared; observation failed without stopping it again")

    try:
        dump = shell("dumpsys", "activity", "service", SERVICE)
        (args.evidence_dir / "before-service.txt").write_text(dump)
        media = shell("dumpsys", "media_session")
        lines = media.splitlines()
        blocks = []
        for index, line in enumerate(lines):
            if line.strip() != "package=" + args.source_package:
                continue
            indent = len(line) - len(line.lstrip())
            block = [line]
            for following in lines[index + 1:]:
                if following.strip() and (len(following) - len(following.lstrip()) < indent
                                          or following.lstrip().startswith("package=")):
                    break
                block.append(following)
            blocks.append("\n".join(block))
        (args.evidence_dir / "before-source-media.txt").write_text("\n\n".join(blocks))
        if not re.search(r"^\s*sourcePackage=" + re.escape(args.source_package) + r"\s*$", dump, re.M):
            raise RuntimeError("Precondition failed: service sourcePackage does not match")
        if not re.search(r"^\s*connected=true\s*$", dump, re.M) or args.ready_marker not in dump:
            raise RuntimeError("Precondition failed: connected=true and explicit readiness marker required")
        playback = re.search(r"^\s*playback=.*?Playing\(.*?video=Video\(title=(.*?), mediaId=", dump, re.M)
        accepted = re.search(r"^\s*delivery(?:Accepted)?=(.*)$", dump, re.M)
        if not playback or not accepted or not accepted.group(1).startswith("Accepted("):
            raise RuntimeError("Precondition failed: playing video and accepted delivery required")
        expected_video = "video=Video(title=" + playback.group(1) + ", mediaId="
        if expected_video not in accepted.group(1) or not accepted.group(1).endswith(", playing=true))"):
            raise RuntimeError("Precondition failed: matching playing video and accepted delivery required")
        title = playback.group(1)
        if not any(re.search(r"PlaybackState\s*\{state=(?:PLAYING\()?3\b", block)
                   and re.search(r"metadata:.*description=" + re.escape(title) + r"(?:, |\s*$)", block, re.M)
                   for block in blocks):
            raise RuntimeError("Precondition failed: selected source has no matching playing MediaSession")
        screenshot("before")
        failures = []
        for package in (APP, args.source_package):
            try:
                output = shell("am", "force-stop", package)
                if output.strip():
                    raise RuntimeError("Unexpected force-stop output")
            except RuntimeError as error:
                failures.append(str(error))
        if failures:
            raise RuntimeError("Force-stop action failed: " + "; ".join(failures))
        report["actionSuccess"] = True
        stopped = time.monotonic()
        targets = sorted({0, args.observe_seconds, *(n for n in (10, 30, 60) if n < args.observe_seconds)})
        for target in targets:
            while time.monotonic() - stopped < target:
                observe()
                time.sleep(min(1, max(0, target - (time.monotonic() - stopped))))
            observe()
            screenshot("after-" + str(target) + "s")
            observe()
        report["success"] = True
    except Exception as error:
        report["error"] = str(error)
    finally:
        report["finished"] = stamp()
        (args.evidence_dir / "result.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Capture complete; profile screenshots require visual review." if report["success"] else "FAIL: " + report["error"])
    return 0 if report["success"] else 1


if __name__ == "__main__":
    sys.exit(main())
