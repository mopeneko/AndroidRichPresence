---
name: verify
description: Build and drive AndroidRichPresence primarily on the authorized physical Android device to verify launch, source settings, notification access, playback, and Discord presence. Use for device UI verification after changes.
---

# Verify AndroidRichPresence

Run from the repository root. Read [the feature map](features/README.md).
The app displays `YouTube Presence`, monitors a selected YouTube app, and can share playback with a linked Discord account.
Screen text follows the device locale, with English as the default and Japanese in `values-ja`. The labels below are the English defaults. To check the Japanese screen, run `adb shell cmd locale set-app-locales cc.lem0n.androidrichpresence --locales ja-JP` and restart the app; `cmd locale get-app-locales` confirms the per-app locale took effect. The app declares no `localeConfig`, but the command works on the verified API 37 device. Reset it with `--locales en-US` when done, or the next run reads Japanese labels.

## Launch

Use the installed Android CLI and adb. Consult the android-cli skill if commands change.
The wrapper selects Gradle 9.6.0 and a Java 25 daemon. Android SDK platform 37 and Gradle dependencies are required.
The normal build also requires `app/libs/discord_partner_sdk.aar`, NDK `28.2.13676358`, and CMake `3.22.1`.
If the AAR is unavailable, explicitly choose `-PpresenceMonitorOnly=true` for the build below and record that choice.
A monitor-only build cannot verify Discord authorization or delivery. Never substitute it silently.

```sh
./gradlew :app:assembleDebug --console=plain
adb devices -l
```

Prefer a physical device the owner has authorized, especially for Discord journeys that depend on existing authentication.
Read its serial from `adb devices -l`, then set and export `VERIFY_SERIAL` to it.
Use the existing installation and account state first. Do not treat an untested emulator login as evidence that real-device authentication is unavailable.
For isolated checks without Discord authentication, `Pixel_10a` is an optional fallback.
List emulators with `android emulator list`. If starting one, record ownership and read its actual serial from `adb devices -l`.
Do not double-drive a shared device or assume an emulator serial.
If `Pixel_10a` is already running under another operator, create your own instead of attaching to it: `android emulator create` writes the AVD, and a fresh data partition gives the clean defaults these checks need.
The `android emulator start` command takes no port or headless flags. Start the instance directly when you need those, and use the serial `adb devices -l` reports:

```sh
"$ANDROID_HOME/emulator/emulator" -avd <name> -port 5556 -no-window -no-audio -no-snapshot -read-only &
adb -s emulator-5556 wait-for-device
```

```sh
VERIFY_EVIDENCE=$(mktemp -d /tmp/android-rich-presence-proof.XXXXXX)
export VERIFY_EVIDENCE
adb -s "$VERIFY_SERIAL" shell pm path cc.lem0n.androidrichpresence
```

Record device and package ownership in `$VERIFY_EVIDENCE/ownership.txt`.
Preserve the physical device's existing installation, credentials, notification access, and source settings.
For an existing installation, record its APK identity and build provenance. Do not claim it matches the checkout based on version alone.
When the task calls for installing a new build, use a normal SDK build and `install -r` to retain app data.
If an update fails, report it rather than uninstalling or clearing data. Never replace the authenticated installation with a monitor-only build.
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the installed copy was signed with a different key, which is normal when a device carries a release build and you built a debug one. Do not uninstall to get past it on a retained device; start a fresh isolated AVD for the debug build instead, and record which device holds which build.
Compare the installed APK digest with the local one before driving:

```sh
adb -s "$VERIFY_SERIAL" pull "$(adb -s "$VERIFY_SERIAL" shell pm path cc.lem0n.androidrichpresence | sed 's/package://' | tr -d '\r')" "$VERIFY_EVIDENCE/installed.apk"
sha256sum "$VERIFY_EVIDENCE/installed.apk" app/build/outputs/apk/debug/app-debug.apk
```
Use an absent package on an isolated emulator for checks that require fresh defaults. Do not copy account credentials between devices.
Record initial notification access, source package, and sharing state. Discord journeys require a designated test account.

```sh
# Only when installing the build is part of this verification task:
adb -s "$VERIFY_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$VERIFY_SERIAL" shell am start -W -n cc.lem0n.androidrichpresence/.MainActivity > "$VERIFY_EVIDENCE/launch.txt"
android layout --device="$VERIFY_SERIAL" -o "$VERIFY_EVIDENCE/launch-layout.json"
```

Ready means launch reports success, MainActivity is resumed, and a visible `YouTube Presence` appears in the layout.
If capture precedes rendering, wait briefly and capture again. Do not change expectations to hide a failure.
Check the actual foreground activity. `Status: ok` can still bring an existing Settings activity to the front.
After returning from external settings, press Back until MainActivity is visible. If the task remains stuck, reset its stack:

```sh
adb -s "$VERIFY_SERIAL" shell am start -W --activity-clear-top -n cc.lem0n.androidrichpresence/.MainActivity
```

Capture the layout again before further input. Derive tap coordinates from the current layout, never a previous run.

## Doctor

Run these read-only checks before the first drive and after any failed or surprising drive.

```sh
adb -s "$VERIFY_SERIAL" get-state
adb -s "$VERIFY_SERIAL" shell getprop sys.boot_completed
adb -s "$VERIFY_SERIAL" shell dumpsys package cc.lem0n.androidrichpresence
adb -s "$VERIFY_SERIAL" shell dumpsys activity activities
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Expect `device`, boot completion `1`, and MainActivity resumed after launch.
This checkout builds versionCode 3 and versionName 1.2. Read the installed values instead of trusting this line, and re-read them if the app is released again. Version alone does not prove build identity.
Confirm the package is not disabled: `adb -s "$VERIFY_SERIAL" shell pm list packages -d`. A disabled package makes `am start` fail with `Error type 3 ... does not exist` even though `pm path` resolves. Re-enable it with `pm enable` and record that you did.
A physical device with a PIN or pattern keyguard cannot be driven even when `dumpsys` reports it awake: taps land on the lock screen and layout dumps show `keyguard_*` nodes. Check `adb -s "$VERIFY_SERIAL" shell dumpsys trust | grep -o 'deviceLocked=[01]'`; do not attempt to unlock a device you were not asked to unlock. Use `dumpsys` service state for whatever those journeys can still prove, and move UI driving to an isolated emulator.
If another operator may be driving the same serial, check it before the first tap and use a different device rather than interleaving input.
Retain the APK digest, successful install output when applicable, `git rev-parse HEAD`, and `git diff` with the evidence.
For an existing installation, use `pm path` to locate its APK and record its digest separately from the local build.

## Drive

Follow every journey in [the feature map](features/README.md). The stable launch handle is literal text `YouTube Presence`.
Drive the real activity through Android actions. Scroll to reach controls below the screen.
Derive each tap coordinate from a layout dump taken immediately before it. Coordinates shift as the keyboard appears, content changes, or a stale scroll offset persists; a tap on a remembered position can open app settings or type into the wrong field. After any unexpected screen, dump the layout and confirm the actual foreground activity before continuing.
The accessibility tree does not report Compose `enabled` state: every Compose button shows `enabled="true"` and `clickable="false"` whether or not it is visually greyed out. Prove gating by effect and by screenshot, never by those attributes.
For each journey, record observed results or the attempted route and concrete missing prerequisite.
An unreachable authenticated flow does not prove delivery or background recovery.

## Evidence

Keep action logs, launch output, before and after layouts, activity state, APK identity, and a screenshot in `$VERIFY_EVIDENCE`.

```sh
android screen capture --device="$VERIFY_SERIAL" -o "$VERIFY_EVIDENCE/launch.png"
```

Visually inspect the PNG. Confirm `YouTube Presence` is visible without a covering dialog.
Check the action and resulting state together. Settings persist and authorized sharing changes a Discord profile.
Service diagnostics expose video titles. Review evidence before sharing it and avoid capturing credentials or authorization codes.
SDK acceptance does not prove profile display. Inspect a separate Discord client to prove the remote result.
The repository's `scripts/verify_youtube.py`, `verify_lifecycle.py`, and `verify_background.py` supply optional deeper checks.
Read their prerequisites in the feature map before running them. This skill does not own those scripts.

## Cleanup

Pause any media started by the run and close its authorization tab. Restore changed source, sharing, and notification access on retained installations.
Restore the per-app locale to `en-US` if a run set `ja-JP`; it survives process restarts and would mislabel the next run's assertions.
Unlink only a test account linked by this run. Unlinking deletes local credentials but does not revoke Discord's OAuth grant.
On a disposable emulator installation, force-stop this package with `adb -s "$VERIFY_SERIAL" shell am force-stop cc.lem0n.androidrichpresence`.
On the physical device, preserve services and playback that were running before verification. Do not force-stop them as routine cleanup.
If this run installed an absent package, run `adb -s "$VERIFY_SERIAL" uninstall cc.lem0n.androidrichpresence`.
Preserve pre-existing installations and their data.
If this run started Pixel_10a, run `android emulator stop Pixel_10a`. Leave previously running devices alone.
Never clear app data or kill processes by name as generic cleanup.
Confirm that launch output, before and after layouts, screenshots, and any script results survive in `$VERIFY_EVIDENCE`.
Evidence under `/tmp` may disappear on reboot. Copy it elsewhere if long-term retention is needed.

## Maintenance

Use `/maintain-verification-skill` to keep the feature map aligned with the app.
