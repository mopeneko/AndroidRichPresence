---
name: verify
description: Build and drive AndroidRichPresence on an Android emulator and capture greeting and app-resume behavior. Use for device UI verification after changes.
---

# Verify AndroidRichPresence

Run from the repository root. Read [the feature map](features/README.md).
The app currently displays `Hello Android!`. It has no Discord login, presence service, or settings.

## Launch

Use the installed Android CLI and adb. Consult the android-cli skill if commands change.
The wrapper selects Gradle 9.6.0 and a Java 25 daemon. Android SDK platform 37 and Gradle dependencies are required.

```sh
./gradlew :app:assembleDebug --console=plain
android emulator list
adb devices -l
```

Choose an unused emulator. This workstation has `Pixel_10a`.
If stopped, run `android emulator start Pixel_10a` and record that this run started it.
Read its serial from `adb devices -l`, then set and export `VERIFY_SERIAL` to that serial.
Do not double-drive a shared emulator. Do not assume its serial is always `emulator-5554`.

```sh
VERIFY_EVIDENCE=$(mktemp -d /tmp/android-rich-presence-proof.XXXXXX)
export VERIFY_EVIDENCE
adb -s "$VERIFY_SERIAL" shell pm path cc.lem0n.androidrichpresence
```

Record emulator and package ownership in `$VERIFY_EVIDENCE/ownership.txt`.
If the app exists, establish that it is a disposable verification installation before replacing it.
No credentials, seed data, ports, or migrations are required.

```sh
adb -s "$VERIFY_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$VERIFY_SERIAL" shell am start -W -n cc.lem0n.androidrichpresence/.MainActivity > "$VERIFY_EVIDENCE/launch.txt"
android layout --device="$VERIFY_SERIAL" -o "$VERIFY_EVIDENCE/launch-layout.json"
```

Ready means launch reports success and a visible `Hello Android!` appears in the layout.
If capture precedes rendering, wait briefly and capture again. Do not change expectations to hide a failure.

## Doctor

Run these read-only checks when anything looks wrong.

```sh
adb -s "$VERIFY_SERIAL" get-state
adb -s "$VERIFY_SERIAL" shell getprop sys.boot_completed
adb -s "$VERIFY_SERIAL" shell dumpsys package cc.lem0n.androidrichpresence
adb -s "$VERIFY_SERIAL" shell dumpsys activity activities
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Expect `device`, boot completion `1`, and MainActivity resumed after launch.
The template version is versionCode 1 and versionName 1.0. Version alone does not prove build identity.
Retain the APK digest, successful install output, `git rev-parse HEAD`, and `git diff` with the evidence.

## Drive

Follow [greeting and resume](features/greeting.md). The stable handle is literal text `Hello Android!`.
There are no buttons or editable fields. Exercise the real activity through Android actions, not internal Compose setters.

## Evidence

Keep action logs, launch output, before and after layouts, activity state, APK identity, and a screenshot in `$VERIFY_EVIDENCE`.

```sh
android screen capture --device="$VERIFY_SERIAL" -o "$VERIFY_EVIDENCE/greeting.png"
```

Visually inspect the PNG. Confirm the greeting is visible without a covering dialog.
Check the action and resulting state together. This template changes no app data and has no external side effects.
Add side-effect checks when features appear. Mocks do not prove delivery to Discord.

## Cleanup

Force-stop only this package on the reserved emulator with `adb -s "$VERIFY_SERIAL" shell am force-stop cc.lem0n.androidrichpresence`.
If this run installed an absent package, run `adb -s "$VERIFY_SERIAL" uninstall cc.lem0n.androidrichpresence`.
Preserve pre-existing installations and their data.
If this run started Pixel_10a, run `android emulator stop Pixel_10a`. Leave previously running devices alone.
Never clear app data or kill processes by name as generic cleanup.
Confirm that launch output, both layouts, and the screenshot survive in `$VERIFY_EVIDENCE`.
Evidence under `/tmp` may disappear on reboot. Copy it elsewhere if long-term retention is needed.

## Maintenance

Use `/maintain-verification-skill` to keep the feature map aligned with the app.
