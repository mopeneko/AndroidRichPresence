#!/usr/bin/env python3
"""Capture background Discord SDK recovery after a temporary network outage.

Prepare authenticated, accepted Playing/Paused activity and background the publisher
first. Uses USB ADB; disabling device networking can disconnect network-based ADB.
Raw service dumps contain media titles; keep the evidence directory private.
Acceptance does not prove that a remote Discord profile displays the activity.
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
TRANSPORTS = {"wifi": "wifi_on", "data": "mobile_data"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--evidence-dir", required=True, type=Path)
    parser.add_argument("--offline-seconds", default=160, type=int)
    parser.add_argument("--recovery-seconds", default=90, type=int)
    args = parser.parse_args()
    if args.offline_seconds < 100:
        parser.error("--offline-seconds must be at least 100")
    if args.recovery_seconds < 1:
        parser.error("--recovery-seconds must be positive")
    if args.evidence_dir.exists() and (not args.evidence_dir.is_dir() or any(args.evidence_dir.iterdir())):
        parser.error("Use a fresh, empty evidence directory")
    args.evidence_dir.mkdir(parents=True, exist_ok=True)
    report = dict(
        serial=args.serial, publisherPackage=APP, offlineSeconds=args.offline_seconds,
        recoverySeconds=args.recovery_seconds, success=False, offlineAuthLossObserved=False,
        recoveryAccepted=False, networkRestored=False, publisherForegroundObserved=False,
        scope="Background recovery to matching Discord SDK acceptance; remote profile display is not verified. "
              "Publisher foreground is checked in activity dumps at each sample, not continuously.",
        actions=[], observations=[], errors=[],
    )
    initial_network = {}
    network_mutation_attempted = False

    def stamp():
        return dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(), monotonic=time.monotonic())

    def shell(*remote):
        command = ["adb", "-s", args.serial, "shell", shlex.join(remote)]
        entry = dict(argv=command, started=stamp())
        report["actions"].append(entry)
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=10)
        except (subprocess.TimeoutExpired, OSError) as error:
            entry.update(error=type(error).__name__, finished=stamp())
            raise RuntimeError("ADB failed or timed out; see action log") from error
        entry.update(returncode=result.returncode, finished=stamp())
        if result.returncode or result.stderr.strip():
            raise RuntimeError("ADB failed or emitted stderr; see action log")
        return result.stdout

    def network_state(transport):
        value = shell("settings", "get", "global", TRANSPORTS[transport]).strip()
        if value not in ("0", "1"):
            raise RuntimeError("Cannot establish " + transport + " state: expected setting 0 or 1")
        return int(value)

    def set_network(transport, state):
        output = shell("svc", transport, "enable" if state else "disable")
        if output.strip():
            raise RuntimeError("Unexpected output changing " + transport + "; state must be read before further action")

    def wait_network(transport, state):
        deadline = time.monotonic() + 15
        while True:
            current = network_state(transport)
            if current == state:
                return
            if time.monotonic() >= deadline:
                raise RuntimeError("Timed out verifying " + transport + " setting")
            time.sleep(1)

    def observe(phase):
        index = len(report["observations"])
        sample = dict(phase=phase, started=stamp())
        report["observations"].append(sample)
        activities = shell("dumpsys", "activity", "activities")
        activity_path = f"{index:04d}-{phase}-activities.txt"
        (args.evidence_dir / activity_path).write_text(activities)
        sample["activitiesPath"] = activity_path
        resumed = [line for line in activities.splitlines()
                   if re.search(r"\b(?:mResumedActivity|topResumedActivity|ResumedActivity)\s*[:=]", line)]
        if not resumed:
            raise RuntimeError("Activity dump has no resumed-activity field; cannot verify background state")
        foreground = any(APP in line for line in resumed)
        sample["publisherForeground"] = foreground
        if foreground:
            report["publisherForegroundObserved"] = True
            raise RuntimeError("Publisher Activity resumed during background verification")
        dump = shell("dumpsys", "activity", "service", SERVICE)
        dump_path = f"{index:04d}-{phase}-service.txt"
        (args.evidence_dir / dump_path).write_text(dump)
        sample.update(servicePath=dump_path, finished=stamp())
        fields = {}
        for key in ("connected", "authenticated", "auth", "playback", "delivery"):
            matches = re.findall(r"^\s*" + key + r"=(.*)$", dump, re.MULTILINE)
            if len(matches) != 1:
                raise RuntimeError("Service dump missing or ambiguous field: " + key)
            fields[key] = matches[0].strip()
        if fields["authenticated"] not in ("true", "false") or not fields["auth"]:
            raise RuntimeError("Invalid authentication state in service dump")
        if (fields["auth"] == "Ready") != (fields["authenticated"] == "true"):
            raise RuntimeError("Service dump authentication fields disagree")
        if fields["connected"] != "true":
            raise RuntimeError("Notification listener disconnected during verification")
        playback = re.fullmatch(r"(Playing|Paused)\((video=Video\(.*\))\)", fields["playback"])
        expected = None
        if playback:
            playing = "true" if playback.group(1) == "Playing" else "false"
            expected = "Accepted(presence=Presence(" + playback.group(2) + ", playing=" + playing + "))"
        matching = expected is not None and fields["delivery"] == expected
        sample.update(auth=fields["auth"], authenticated=fields["authenticated"] == "true",
                      playbackKind=playback.group(1) if playback else "Other", matchingAccepted=matching)
        return sample

    def restore_network():
        failures = []
        for transport, state in initial_network.items():
            try:
                current = network_state(transport)
                if current != state:
                    set_network(transport, state)
                wait_network(transport, state)
            except Exception as error:
                failures.append(transport + ": " + str(error))
        if failures:
            raise RuntimeError("Network restoration not verified; " + "; ".join(failures))
        report["networkRestored"] = True

    try:
        initial = observe("initial")
        if not initial["authenticated"] or not initial["matchingAccepted"]:
            raise RuntimeError("Precondition failed: authenticated=true and matching Accepted Playing/Paused required")
        initial_network = {transport: network_state(transport) for transport in TRANSPORTS}
        report["initialNetwork"] = initial_network.copy()
        if not any(initial_network.values()):
            raise RuntimeError("Precondition failed: at least one of Wi-Fi or mobile data must be enabled")
        try:
            for transport, state in initial_network.items():
                if state:
                    network_mutation_attempted = True
                    set_network(transport, 0)
            for transport in TRANSPORTS:
                wait_network(transport, 0)
            offline_started = time.monotonic()
            deadline = offline_started + args.offline_seconds
            report["offlineStarted"] = stamp()
            while True:
                sample = observe("offline")
                if not sample["authenticated"] and sample["auth"] != "Ready":
                    report["offlineAuthLossObserved"] = True
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                time.sleep(min(5, remaining))
            report["offlineObservedSeconds"] = time.monotonic() - offline_started
        finally:
            if network_mutation_attempted:
                restore_network()
        if not report["offlineAuthLossObserved"]:
            raise RuntimeError("No authentication loss observed offline; recovery cannot be established")
        report["recoveryStarted"] = stamp()
        deadline = time.monotonic() + args.recovery_seconds
        while True:
            sample = observe("recovery")
            if sample["authenticated"] and sample["matchingAccepted"]:
                report["recoveryAccepted"] = True
                break
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError("Timed out waiting for authenticated matching SDK acceptance in background")
            time.sleep(min(5, remaining))
        report["success"] = report["networkRestored"] and report["offlineAuthLossObserved"] and report["recoveryAccepted"]
    except Exception as error:
        report["errors"].append(str(error))
    except KeyboardInterrupt:
        report["errors"].append("Interrupted by operator")
    finally:
        report["finished"] = stamp()
        (args.evidence_dir / "result.json").write_text(json.dumps(report, indent=2) + "\n")
    print("PASS: background recovery accepted by Discord SDK; remote profile not verified."
          if report["success"] else "FAIL: " + "; ".join(report["errors"]))
    return 0 if report["success"] else 1


if __name__ == "__main__":
    sys.exit(main())
