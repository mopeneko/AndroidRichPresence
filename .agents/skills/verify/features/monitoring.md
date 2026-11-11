# Source settings and playback observation

## Settings and notification access

1. Capture initial access, sharing, and saved source. Sharing defaults off on a fresh installation.
2. Tap `Morphe`. Confirm the draft changes to `app.morphe.android.youtube` while `Monitoring:` retains its saved value.
3. Tap `Save settings`. Confirm the saved label changes. Repeat with `Official YouTube`, which selects `com.google.android.youtube`.
4. Focus the package field and enter `invalid`. Confirm validation marks the field and that saving does not take effect. Enter a valid custom package, save, reopen, and confirm persistence. Validation checks syntax, not installation.
`Save settings` reports `enabled="true"` in the accessibility tree in every state, so a disabled-state assertion is worthless. Prove the gate by effect: with `invalid` in the field, tap `Save settings` and confirm `shared_prefs/presence.xml` still holds the previous package. Inspect a screenshot for the red field outline and the greyed button.
Reading those preferences needs a debuggable build (`adb -s "$VERIFY_SERIAL" shell run-as cc.lem0n.androidrichpresence cat shared_prefs/presence.xml`); `run-as` fails with `package not debuggable` on a release install, so use the layout and screenshot there instead.
Clear the field before typing: tap it, send `KEYCODE_MOVE_END`, then repeat `KEYCODE_DEL` far more times than the current text length. `input text` appends, and a swipe while the keyboard is up can land as text input, so press Back to dismiss the keyboard before any scroll and re-check the field value afterwards.
5. Restore the source to the installed test player.
6. Tap `Open notification access settings`. Android opens the notification-access list; tap `AndroidRichPresence`, enable `Allow notification access` on the detail screen, and accept the `Allow notification access for AndroidRichPresence?` consent dialog. Return with Back until MainActivity is resumed or use the parent's stack reset.
7. Confirm `Notification access is granted` and `Monitoring service connected`. Capture a layout and service dump. Revoke access, return, and confirm denied/disconnected state and cleared observation. Restore the prior permission when retaining the installation.

After text entry, dismiss the keyboard and inspect a screenshot before tapping Save. The layout can include controls covered by the keyboard.
Wait for Android's permission change to settle before returning. If the access label is stale, leave and resume again, then compare with the system's actual notification-access setting.
The Settings UI route is the one to prove once. For repeated toggles within a run, `adb -s "$VERIFY_SERIAL" shell cmd notification allow_listener cc.lem0n.androidrichpresence/cc.lem0n.androidrichpresence.youtube.YouTubeNotificationListener` and `... disallow_listener <same component>` skip the consent dialog. Record which route produced each state.
A listener that is still connected after revocation is not a failure of the grant check: revoking access stops the service, so `dumpsys activity service <component>` then answers `No services match`.

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
Pausing through `KEYCODE_MEDIA_PAUSE` can leave the MediaSession at `STOPPED` rather than `PAUSED`, which the app reports as `No video`. Pause from the player's own UI when you need the `Paused` state, and treat a stopped session as the documented no-session case instead of a failure.
If the player can produce buffering or missing titles, capture `Buffering` or `Title unavailable` respectively. Record unavailable fixtures rather than claiming coverage.
Changing the saved package must discard the previous source observation. Listener loss must clear it as well.

Source entry points: `MainActivity` settings and `StatusCard`, `PresenceStore.settings/save`, and `YouTubeNotificationListener.refreshSettings/bind/snapshot/disconnect` under `app/src/main/java/cc/lem0n/androidrichpresence/`.
