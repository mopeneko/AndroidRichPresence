# Source settings and playback observation

## Settings and notification access

1. Capture initial access, sharing, and saved source. Sharing defaults off on a fresh installation.
2. Tap `Morphe`. Confirm the draft changes to `app.morphe.android.youtube` while `Monitoring:` retains its saved value.
3. Tap `Save settings`. Confirm the saved label changes. Repeat with `Official YouTube`, which selects `com.google.android.youtube`.
4. Focus the package field and enter `invalid`. Confirm validation marks the field and disables saving. Enter a valid custom package, save, reopen, and confirm persistence. Validation checks syntax, not installation.
5. Restore the source to the installed test player.
6. Open `Open notification access settings`, select AndroidRichPresence, enable access, and accept Android's consent dialog. Return using Back or the parent's stack reset.
7. Confirm `Notification access is granted` and `Monitoring service connected`. Capture a layout and service dump. Revoke access, return, and confirm denied/disconnected state and cleared observation. Restore the prior permission when retaining the installation.

After text entry, dismiss the keyboard and inspect a screenshot before tapping Save. The layout can include controls covered by the keyboard.
Wait for Android's permission change to settle before returning. If the access label is stale, leave and resume again, then compare with the system's actual notification-access setting.

```sh
adb -s "$VERIFY_SERIAL" shell dumpsys activity service cc.lem0n.androidrichpresence/.youtube.YouTubeNotificationListener > "$VERIFY_EVIDENCE/service.txt"
```

## Playback

Require notification access, connected monitoring, matching saved source, working network, and completed onboarding in a player that can play two distinct videos.
Sharing can remain off. A monitor-only build suffices.

```sh
python3 scripts/verify_youtube.py --serial "$VERIFY_SERIAL" --source-package com.google.android.youtube --evidence-dir "$VERIFY_EVIDENCE/playback"
```

For Morphe, change both the app setting and `--source-package` to `app.morphe.android.youtube`.
Use a fresh evidence directory. The script opens two videos, checks playing, paused, resumed, and changed-title observations against the real MediaSession, and sends Pause during cleanup.
Inspect `result.json` and paired media/service dumps. Return to the app and inspect the visible title and playback state.
If a drive fails, run doctor, inspect the player's UI for onboarding or playback restrictions, and clean up started playback before retrying.

Also inspect stopped or absent sessions as `No video` and `Stopped or not detected`.
If the player can produce buffering or missing titles, capture `Buffering` or `Title unavailable` respectively. Record unavailable fixtures rather than claiming coverage.
Changing the saved package must discard the previous source observation. Listener loss must clear it as well.

Source entry points: `MainActivity` settings and `StatusCard`, `PresenceStore.settings/save`, and `YouTubeNotificationListener.refreshSettings/bind/snapshot/disconnect` under `app/src/main/java/cc/lem0n/androidrichpresence/`.
