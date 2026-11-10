# Launch and app resume

Complete the parent skill's build, ownership, installation, and doctor steps first.

1. Launch AndroidRichPresence. Capture the layout and inspect a screenshot showing `YouTube Presence` without a covering dialog.
2. Confirm the normal build shows an authentication status, or the explicit monitor build shows `This is a monitor-only build` and disables linking.
3. Press Home and capture activity state proving MainActivity left the foreground.
4. Reopen and capture the layout and resumed activity. Confirm the title and saved settings remain visible.

```sh
adb -s "$VERIFY_SERIAL" shell input keyevent KEYCODE_HOME
adb -s "$VERIFY_SERIAL" shell dumpsys activity activities > "$VERIFY_EVIDENCE/home-activity.txt"
adb -s "$VERIFY_SERIAL" shell am start -W -n cc.lem0n.androidrichpresence/.MainActivity > "$VERIFY_EVIDENCE/resume.txt"
android layout --device="$VERIFY_SERIAL" -o "$VERIFY_EVIDENCE/resume-layout.json"
adb -s "$VERIFY_SERIAL" shell dumpsys activity activities > "$VERIFY_EVIDENCE/resume-activity.txt"
```

Resume refreshes notification permission and media observation. It also requests listener reconnection if access is granted but monitoring is disconnected.
Use the parent's stack reset if external settings remain visible. A successful `am start` alone is insufficient.

Source entry points: `MainActivity.onCreate`, `onResume`, and `onPause` in `app/src/main/java/cc/lem0n/androidrichpresence/MainActivity.kt`.
