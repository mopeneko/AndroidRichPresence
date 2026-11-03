# Greeting and app resume

## Sub-features

- Launch the greeting screen.
- Return to the screen after leaving the app.

## How to get to it (user POV)

Open AndroidRichPresence from the launcher. Read `Hello Android!`.
Press Home, then reopen AndroidRichPresence.

## Driving it with Android CLI and adb

Complete the parent skill's build, ownership, installation, and launch steps first.
Confirm the launch layout contains a visible `Hello Android!` element.

```sh
printf 'Press HOME\n' >> "$VERIFY_EVIDENCE/actions.txt"
adb -s "$VERIFY_SERIAL" shell input keyevent KEYCODE_HOME
adb -s "$VERIFY_SERIAL" shell dumpsys activity activities > "$VERIFY_EVIDENCE/home-activity.txt"
printf 'Reopen AndroidRichPresence\n' >> "$VERIFY_EVIDENCE/actions.txt"
adb -s "$VERIFY_SERIAL" shell am start -W -n cc.lem0n.androidrichpresence/.MainActivity > "$VERIFY_EVIDENCE/resume.txt"
android layout --device="$VERIFY_SERIAL" -o "$VERIFY_EVIDENCE/resume-layout.json"
adb -s "$VERIFY_SERIAL" shell dumpsys activity activities > "$VERIFY_EVIDENCE/resume-activity.txt"
```

Confirm Home actually left MainActivity. Pass when the resume layout again shows `Hello Android!` and MainActivity is resumed.
Capture and inspect the final screenshot as described in the parent skill.

## Gotchas

`GreetingPreview` is an IDE preview and does not prove behavior on a device.
There is no account or presence control. Arithmetic unit tests and package-name instrumentation do not prove this journey.
An absent device, failed build, or missing greeting blocks the journey. Report the exact step.
