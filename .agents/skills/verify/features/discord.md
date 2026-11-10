# Discord linking, sharing, and recovery

## Prerequisites

Use the normal SDK build and a designated Discord test account. The configured application `1567132201588367360` needs Public Client and redirect `discord-1567132201588367360:/authorize/callback` in Discord's developer settings.
Complete browser onboarding if necessary. Enter credentials only in the official Discord interface and avoid recording secrets.
Playback delivery additionally requires notification access, a connected listener, a selected player, and current titled playback.
If an account is unavailable, attempt linking, capture the login page without credentials, and record subsequent authenticated journeys as unreachable for that prerequisite.

## Link, share, and unlink

1. On a fresh normal installation, confirm `Discord is not linked`, sharing off, and retry disabled.
2. Toggle `Share to Discord` on and off. Confirm the switch persists after reopening. Sharing alone must not authenticate an unlinked account.
3. Tap `Link Discord`, complete official consent, and confirm `Connected to Discord`. Linking is available while sharing stays off.
4. Enable sharing and run the monitoring feature's playback script with `--require-discord-accepted`. Require matching title and playback state in SDK acceptance. Inspect the actual profile in a separate Discord client.
5. Turn sharing off. Check the clear request and continued local monitoring. `Clear requested. Discord has not confirmed the removal` explicitly does not confirm remote disappearance.
6. With sharing enabled, stopped playback, session loss, missing metadata, buffering, and listener disconnection request clearing. Exercise available fixtures and inspect the remote profile separately.
7. Unlink the account linked for this run with `Unlink and delete the data on this device`. Confirm Unlinked after restart. This deletes local credentials and closes the connection, but does not revoke the remote OAuth grant.

`Retry sending to Discord` is enabled only for an authentication failure. Record the actual effect if reached, rather than assuming it reconnects. Recoverable connection failures use automatic retries. Token refresh failures can require relinking.

## Background recovery and process exit

Require a saved authenticated link, sharing enabled, connected monitoring, and current playing or paused media.
Follow the opt-in `BackgroundPresenceTest` instrumentation commands in the repository README, with `backgroundPresence=true` and `backgroundObserveSeconds=240`.
Require connection and matching SDK acceptance without launching MainActivity.

While instrumentation observes the background publisher, the following script tests recovery after a 160-second outage:

```sh
python3 scripts/verify_background.py --serial "$VERIFY_SERIAL" --evidence-dir "$VERIFY_EVIDENCE/background"
```

This changes device-wide Wi-Fi and mobile data. Use a reserved device with USB ADB and no other network use.
Inspect its result and recorded network restoration even after failure. If interrupted or ADB is lost, verify and restore the original network settings explicitly.
Connection retries continue with capped backoff; token refresh with an ambiguous outcome requires relinking rather than reuse of old credentials.

For process-exit evidence, start with matching playing media and accepted delivery, then open the Discord profile on `$VERIFY_SERIAL` before running:

```sh
python3 scripts/verify_lifecycle.py --serial "$VERIFY_SERIAL" --source-package com.google.android.youtube --evidence-dir "$VERIFY_EVIDENCE/lifecycle"
```

The script screenshots `$VERIFY_SERIAL`. A profile open only on a separate client needs separate capture.
This force-stops both publisher and player. Inspect screenshots for profile disappearance, and retain the observed duration rather than promising immediate clearing after process death.
Doctor and restore the intended app state before another drive. Pause any remaining playback and close test authorization tabs at teardown.

Source entry points: `PresenceRuntime`, `PresenceSession`, `discord/DiscordBridge.kt`, `discord_bridge.cpp`, and `BackgroundPresenceTest.kt` under `app/src/`. The three verification scripts live in the repository's `scripts/` directory.
