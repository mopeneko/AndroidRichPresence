# Launch and app resume

Complete the parent skill's build, ownership, installation, and doctor steps first.

1. Launch AndroidRichPresence. Capture the layout and inspect a screenshot showing `YouTube Presence` without a covering dialog.
2. Confirm the normal build shows an authentication status, or the explicit monitor build shows `This is a monitor-only build` twice — once as the authentication status and once inside the longer red notice — and greys out `Link Discord`.
The accessibility tree reports every Compose button as `enabled="true"` with `clickable="false"`, disabled or not, so assert the control's effect instead: tap it and check that the state did not change, then confirm the greyed appearance in the screenshot.
3. Press Home and capture activity state proving MainActivity left the foreground.
4. Reopen and capture the layout and resumed activity. Confirm the title and saved settings remain visible.

```sh
adb -s "$VERIFY_SERIAL" shell input keyevent KEYCODE_HOME
adb -s "$VERIFY_SERIAL" shell dumpsys activity activities > "$VERIFY_EVIDENCE/home-activity.txt"
adb -s "$VERIFY_SERIAL" shell am start -W -n cc.lem0n.androidrichpresence/.MainActivity > "$VERIFY_EVIDENCE/resume.txt"
android layout --device="$VERIFY_SERIAL" -o "$VERIFY_EVIDENCE/resume-layout.json"
adb -s "$VERIFY_SERIAL" shell dumpsys activity activities > "$VERIFY_EVIDENCE/resume-activity.txt"
```

Resume refreshes the notification-permission label and re-attaches the Discord SDK host, which is what makes linking possible again after the activity paused.
Media observation is recomputed on resume only when the listener is already connected; when it is disconnected, resume calls `requestRebind` and observation is refreshed by the listener's own connect path instead.
Use the parent's stack reset if external settings remain visible. A successful `am start` alone is insufficient.
The saved-settings assertion proves only that the composition survived: the Compose `remember` snapshot is not re-read from preferences on resume, so an unsaved edit in the package field also survives Home. To prove persistence, force-stop and relaunch, then read `shared_prefs/presence.xml`.

Source entry points: `MainActivity.onCreate`, `onResume`, and `onPause` in `app/src/main/java/cc/lem0n/androidrichpresence/MainActivity.kt`.
