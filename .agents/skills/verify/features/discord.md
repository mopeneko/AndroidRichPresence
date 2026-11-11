# Discord linking, sharing, and recovery

## Prerequisites

Use the normal SDK build and a designated Discord test account. The configured application `1567132201588367360` needs Public Client and redirect `discord-1567132201588367360:/authorize/callback` in Discord's developer settings.
Complete browser onboarding if necessary. Enter credentials only in the official Discord interface and avoid recording secrets.
On a device whose Chrome has never run, `Link Discord` can open Chrome's own first-run screen before the Discord page. Tap `Stay signed out`, then dismiss Chrome's `Viewing full screen` hint with `Got it`. Only then does the Discord login page appear; a screenshot of the first-run screen is not evidence about linking.
When the Discord app is installed and already signed in, the handshake opens Discord's own `Authorize` screen instead of a browser page: scroll until the consent list ends and tap the `Authorize` button at the bottom. The screen lists the granted scopes (`Update your activity status on Discord` among them) and is the place to confirm which account is being linked; record the account name, never the credentials.
The link handshake needs no Discord account to observe: the tap moves the status to `Checking the Discord link` (`auth=Authorizing`) and sends the OAuth URL onward. An abandoned authorization ends in `Authentication could not finish. Link Discord again.` (`auth=Failed(issue=Timeout)`) after 180 seconds, which proves the deadline without credentials.
Playback delivery additionally requires notification access, a connected listener, a selected player, and current titled playback.
If an account is unavailable, attempt linking, capture the login page without credentials, and record subsequent authenticated journeys as unreachable for that prerequisite.

## Link, share, and unlink

1. On a fresh normal installation, confirm `Discord is not linked`, sharing off, and retry disabled.
2. Toggle `Share to Discord` on and off. Confirm the switch persists after reopening. Sharing alone must not authenticate an unlinked account.
3. Tap `Link Discord`, complete official consent, and confirm `Connected to Discord`. Linking is available while sharing stays off.
4. Enable sharing and run the monitoring feature's playback script with `--require-discord-accepted`. Require matching title and playback state in SDK acceptance. Then open the Discord profile on the device and confirm the same title and play state under `Watching AndroidRichPresence`.
5. Turn sharing off. Check the clear request and continued local monitoring. `Clear requested. Discord has not confirmed the removal` explicitly does not confirm remote disappearance.
The request does take effect: on the verified device the Discord profile dropped the activity within seconds of the switch going off while local playback kept being observed. Capture the profile before and after to show it.
6. With sharing enabled, stopped playback, session loss, missing metadata, buffering, and listener disconnection request clearing. Exercise available fixtures and confirm the profile on the device.
7. Unlink the account linked for this run with `Unlink and delete the data on this device`. Confirm Unlinked after restart. This deletes local credentials, closes the connection, and also issues a clear request while the client is still open, so a delivery line about an unconfirmed clear can remain until the next state change. The remote profile did show the activity gone after unlink on the verified device. It does not revoke the remote OAuth grant, so revoke it in Discord's authorized-apps settings if the run should not leave the grant in place.

`Retry sending to Discord` is enabled only for `Auth.Failed`, which includes the storage-only `UnlinkFailed` issue, not just authentication failures. Its guard requires a saved token and an open-ended session, and every path that produces `Auth.Failed` has already destroyed the token, so the button is a proven no-op: tapping it leaves the state unchanged. `Link Discord` is the only recovery. Record what it does rather than assuming it reconnects. Recoverable connection failures use automatic retries. Token refresh failures can require relinking.

## Background recovery and process exit

Require a saved authenticated link, sharing enabled, connected monitoring, and current playing or paused media.
Follow the opt-in `BackgroundPresenceTest` instrumentation commands in `README.ja.md` ("画面を開かない復旧を検証する"), with `backgroundPresence=true` and `backgroundObserveSeconds=240`. The English `README.md` documents the three Python scripts only and has no instrumentation command.
Without `backgroundPresence=true` the test skips rather than passes. `backgroundObserveSeconds` defaults to `0`, and a value outside `0..600` fails the test instead of skipping it.
The runner needs `androidx.tracing`, which R8 strips from the release APK: driving the test against a release install crashes with `NoClassDefFoundError: Failed resolution of: Landroidx/tracing/Trace;` and reports `INSTRUMENTATION_RESULT: shortMsg=Process crashed`. Install the debug APK for this test. `assembleDebugAndroidTest` produces a debug-signed test APK that cannot install beside a release-signed app, so either run both as debug or re-sign the test APK with the release key before installing it.
Require connection and matching SDK acceptance without launching MainActivity.

While instrumentation observes the background publisher, the following script tests recovery after a 160-second outage:

```sh
python3 scripts/verify_background.py --serial "$VERIFY_SERIAL" --evidence-dir "$VERIFY_EVIDENCE/background"
```

This changes device-wide Wi-Fi and mobile data. Use a reserved device with USB ADB and no other network use.
`svc data` does nothing when the SIM is not ready, so `mobile_data` stays `1` and the script fails with `Timed out verifying data setting`. Check with `adb -s "$VERIFY_SERIAL" shell getprop gsm.sim.state`; on a `NOT_READY` device zero the setting yourself and restore it after:

```sh
adb -s "$VERIFY_SERIAL" shell settings put global mobile_data 0
# ... run the script ...
adb -s "$VERIFY_SERIAL" shell settings put global mobile_data 1
```

The script also fails with `Publisher Activity resumed during background verification` when MainActivity is in front. Bring the Discord app or the launcher forward first, and let `auth=Ready` with matching `delivery=Accepted` settle after the app was restarted; a `Connecting` sample fails the precondition with an error that names authentication rather than timing.
Inspect its result and recorded network restoration even after failure. If interrupted or ADB is lost, verify and restore the original network settings explicitly.
Connection retries continue with capped backoff; token refresh with an ambiguous outcome requires relinking rather than reuse of old credentials.

For process-exit evidence, start with matching playing media and accepted delivery, then open the Discord profile on `$VERIFY_SERIAL` before running:

```sh
python3 scripts/verify_lifecycle.py --serial "$VERIFY_SERIAL" --source-package app.morphe.android.youtube --evidence-dir "$VERIFY_EVIDENCE/lifecycle"
```

The script screenshots `$VERIFY_SERIAL`. A profile open only on a separate client needs separate capture.
This force-stops both publisher and player. Inspect screenshots for profile disappearance, and retain the observed duration rather than promising immediate clearing after process death.

The script requires the publisher to stay dead, which Android defeats while the notification listener is bound. The listener is bound by the system (`c:android` in `dumpsys activity services`), so `am force-stop` leaves `stopped=false` and the process restarts within about two seconds; the script then fails with `Publisher process reappeared`. Revoke access first, force-stop, observe, then restore access:

```sh
adb -s "$VERIFY_SERIAL" shell cmd notification disallow_listener cc.lem0n.androidrichpresence/cc.lem0n.androidrichpresence.youtube.YouTubeNotificationListener
adb -s "$VERIFY_SERIAL" shell am force-stop cc.lem0n.androidrichpresence
adb -s "$VERIFY_SERIAL" shell pidof cc.lem0n.androidrichpresence
adb -s "$VERIFY_SERIAL" exec-out screencap -p > "$VERIFY_EVIDENCE/exit-10s.png"
```

With access revoked, `stopped=true` sticks and the process stays absent. Measured on a Pixel 7 with the profile open: the activity was gone within 10 seconds. That measured path also removes the listener, so it does not prove the app's own shutdown behaviour; say so in the report. Restore access afterwards so later journeys have a connected listener.

Doctor and restore the intended app state before another drive. Pause any remaining playback and close test authorization tabs at teardown.

Source entry points: `PresenceRuntime` and `PresenceSession` in `app/src/main/java/cc/lem0n/androidrichpresence/presence/`, `DiscordBridge.kt` in `app/src/discord/java/cc/lem0n/androidrichpresence/discord/` (a second stub `DiscordBridge.kt` in `app/src/monitorOnly/java/` is selected for monitor-only builds and never carries Discord journeys), `discord_bridge.cpp` in `app/src/discord/cpp/`, and `BackgroundPresenceTest.kt` in `app/src/androidTest/java/cc/lem0n/androidrichpresence/presence/`. The three verification scripts live in the repository's `scripts/` directory.
