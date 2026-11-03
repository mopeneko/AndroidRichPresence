#!/usr/bin/env python3
"""Verify YouTube observations and optionally matching Discord SDK acceptance.

This cannot prove that a Discord profile displays the accepted activity.
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


def media_sessions(dump, package):
    sessions = []
    lines = dump.splitlines()
    for index, line in enumerate(lines):
        if not re.match(r"\s*package=" + re.escape(package) + r"\s*$", line):
            continue
        indent = len(line) - len(line.lstrip())
        block = []
        for following in lines[index + 1:]:
            if following.strip() and len(following) - len(following.lstrip()) < indent:
                break
            if re.match(r"\s*package=", following):
                break
            block.append(following)
        text = "\n".join(block)
        state = re.search(r"(?:state=PlaybackState\s*\{|playbackState=PlaybackState\s*\{)state=(?:[A-Z_]+\()?(\d+)", text)
        description = re.search(r"^\s*metadata:.*?description=(.*)$", text, re.MULTILINE)
        if state and description:
            sessions.append((int(state.group(1)), description.group(1).strip()))
    return sessions


def observed_title(dump, kind):
    playback = re.search(r"^\s*playback=(.*)$", dump, re.MULTILINE)
    if not playback or not re.search(r"\b" + kind + r"\(", playback.group(1)):
        return None
    video = playback.group(1).partition("video=Video(title=")[2]
    return video.rsplit(", mediaId=", 1)[0].strip() if ", mediaId=" in video else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--source-package", default="com.google.android.youtube")
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--video-a", default="https://www.youtube.com/watch?v=aqz-KE-bpKQ")
    parser.add_argument("--video-b", default="https://www.youtube.com/watch?v=eRsGyueVLvQ")
    parser.add_argument("--expected-title-a")
    parser.add_argument("--expected-title-b")
    parser.add_argument("--require-discord-accepted", action="store_true",
                        help="Also wait for Discord to accept the matching title and playback state")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", args.source_package):
        parser.error("--source-package must be an Android package name")
    if args.video_a == args.video_b:
        parser.error("--video-a and --video-b must differ")
    args.evidence_dir.mkdir(parents=True, exist_ok=True)
    report = {
        "serial": args.serial, "sourcePackage": args.source_package,
        "scope": ("Source observation and matching Discord SDK acceptance; profile display is not verified."
                  if args.require_discord_accepted else
                  "Source observation only; Discord SDK acceptance and profile display are not verified."),
        "startedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "success": False, "commands": [], "steps": [],
    }
    last = {"media": "", "app": ""}
    started = False

    def adb(*remote, timeout=10):
        command = ["adb", "-s", args.serial, "shell", shlex.join(remote)]
        entry = {"argv": command, "at": datetime.datetime.now(datetime.timezone.utc).isoformat()}
        report["commands"].append(entry)
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
            entry.update(returncode=result.returncode, stdout=result.stdout, stderr=result.stderr)
        except (subprocess.TimeoutExpired, OSError) as error:
            entry["error"] = str(error)
            raise RuntimeError(f"ADB command failed: {shlex.join(remote)}: {error}") from error
        if result.returncode:
            raise RuntimeError(f"ADB command failed: {shlex.join(remote)}: {result.stderr.strip()}")
        return result.stdout

    def save(name):
        for kind, dump in last.items():
            (args.evidence_dir / f"{name}-{kind}.txt").write_text(dump)

    def app_dump():
        last["app"] = adb("dumpsys", "activity", "service", SERVICE)
        return last["app"]

    def configured(dump):
        source = re.search(r"^\s*sourcePackage=(.*)$", dump, re.MULTILINE)
        connected = re.search(r"^\s*connected=(.*)$", dump, re.MULTILINE)
        return source and source.group(1).strip() == args.source_package and connected and connected.group(1).strip() == "true"

    def wait_for(name, state, kind, title=None, different=None):
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            last["media"] = adb("dumpsys", "media_session")
            dump = app_dump()
            if not configured(dump):
                raise RuntimeError("Monitor disconnected or configured sourcePackage changed during verification")
            observed = observed_title(dump, kind)
            if observed and (title is None or observed == title) and observed != different:
                if args.require_discord_accepted:
                    delivery = re.search(r"^\s*delivery=(.*)$", dump, re.MULTILINE)
                    playing = "true" if kind == "Playing" else "false"
                    if not (delivery and delivery.group(1).startswith("Accepted(")
                            and "video=Video(title=" + observed + ", mediaId=" in delivery.group(1)
                            and delivery.group(1).endswith(", playing=" + playing + "))")):
                        time.sleep(0.4)
                        continue
                for media_state, description in media_sessions(last["media"], args.source_package):
                    if media_state == state and (description == observed or description.startswith(observed + ", ")):
                        save(name)
                        report["steps"].append({"step": name, "success": True, "title": observed,
                                                "mediaState": state, "appState": kind,
                                                "discordAccepted": args.require_discord_accepted})
                        print(f"PASS {name}: {observed}", flush=True)
                        return observed
            time.sleep(0.4)
        raise RuntimeError(f"Timed out waiting for {name}: exact source {args.source_package}, "
                           f"MediaSession state {state}, app {kind}, title={title!r}, different={different!r}")

    def open_video(url):
        output = adb("am", "start", "-W", "-f", "0x14000000", "-a", "android.intent.action.VIEW", "-d", url,
                     "-p", args.source_package, timeout=20)
        if re.search(r"(?:Error:|Exception|Status:\s*(?!ok\b)\S+)", output):
            raise RuntimeError("YouTube deep link failed: " + output.strip())
        adb("input", "keyevent", "KEYCODE_MEDIA_PLAY")

    try:
        if not configured(app_dump()):
            raise RuntimeError("Precondition failed: install/configure the monitor and grant notification access; "
                               f"expected sourcePackage={args.source_package} and connected=true")
        save("precondition")
        started = True
        open_video(args.video_a)
        title_a = wait_for("video-a-playing", 3, "Playing", title=args.expected_title_a)
        adb("input", "keyevent", "KEYCODE_MEDIA_PAUSE")
        wait_for("video-a-paused", 2, "Paused", title=title_a)
        adb("input", "keyevent", "KEYCODE_MEDIA_PLAY")
        wait_for("video-a-resumed", 3, "Playing", title=title_a)
        open_video(args.video_b)
        wait_for("video-b-playing", 3, "Playing", title=args.expected_title_b, different=title_a)
        report["success"] = True
    except Exception as error:
        report["error"] = str(error)
        save("failure-last")
    finally:
        if started:
            try:
                adb("input", "keyevent", "KEYCODE_MEDIA_PAUSE")
                report["cleanup"] = "Pause key sent"
            except Exception as error:
                report["cleanup"] = str(error)
                report["success"] = False
                report.setdefault("error", "Cleanup pause failed: " + str(error))
        report["finishedAt"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        (args.evidence_dir / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    if not report["success"]:
        raise RuntimeError(report["error"])
    print("PASS source observation and matching Discord SDK acceptance. Profile display is not verified."
          if args.require_discord_accepted else
          "PASS source observation only. Discord SDK acceptance and profile display are not verified.")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
