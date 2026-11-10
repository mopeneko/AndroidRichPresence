# AndroidRichPresence

Share the YouTube video you are watching as Discord Rich Presence from an Android phone.

The app reads the video title and play state from the media session of a YouTube app you select. When you link a Discord account and turn sharing on, it publishes that state through the Discord Social SDK. You can keep sharing off and still check what the app detects, or link Discord without publishing anything.

Japanese notes, including what was measured on a real device, are in [README.ja.md](README.ja.md).

## Status

This is a personal project, published as source. It is not on Google Play, has no installer, and ships no Discord SDK binaries. Expect rough edges and breaking changes.

None of this is affiliated with, endorsed by, or supported by Discord, Google, or YouTube. You are responsible for the terms of every service and player app you connect it to.

## Requirements

- JDK 25. The Gradle wrapper pins the Gradle version and downloads the toolchain.
- Android SDK platform 37, with `minSdk` 24 and `targetSdk` 37.
- Android NDK `28.2.13676358` and CMake `3.22.1`, used by the native Discord bridge.
- The Discord Social SDK for Android, version `1.10.19337`, as `app/libs/discord_partner_sdk.aar`.

The SDK is not in this repository and its license does not allow redistribution here. Get the AAR from Discord, accept the [Discord Social SDK Terms](https://support-dev.discord.com/hc/en-us/articles/30225844245271-Discord-Social-SDK-Terms), and place it at the path above. `app/libs/*.aar` is gitignored so an unpacked AAR cannot be committed by accident.

## Build

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. The build enables every ABI the AAR provides.

Without the AAR the default build fails on purpose. A monitor-only build needs neither the SDK nor the NDK, and never authenticates or sends anything:

```sh
./gradlew -PpresenceMonitorOnly=true :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The two variants share `app/src/main` and differ only in `app/src/discord` and `app/src/monitorOnly`. There is no automatic fallback from one to the other.

## Build a release APK

Release builds are signed only when `keystore.properties` exists in the repository root. Without it, `:app:assembleRelease` produces `app-release-unsigned.apk` and Android will not install it.

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Create the key once and keep it. Android identifies an app by its signing key, so a new key means users must uninstall before they can update:

```sh
keytool -genkeypair -keystore release.jks -alias androidrichpresence \
  -keyalg RSA -keysize 4096 -validity 10000
```

```sh
./gradlew :app:assembleRelease
python3 scripts/check_release_apk.py app/build/outputs/apk/release/app-release.apk
```

`keystore.properties` and `*.jks` are gitignored. Never commit either one, and back up the keystore outside the repository. Losing it means you can never update an installed copy.

The release build enables R8 and keeps `packageScope` at `**`. Restricting it to `androidx`, `kotlin`, or `kotlinx` produces an APK that crashes at startup with `IllegalAccessError` from `InitializationProvider`, because those classes get split from the app classes that extend them.

## Install a release

The APK is attached to [the latest release](https://github.com/mopeneko/AndroidRichPresence/releases/latest). Download `androidrichpresence-<version>.apk` and confirm it against the digest printed in the release notes before you install:

```sh
sha256sum androidrichpresence-<version>.apk
```

## Published releases

Releases carry the APK as an asset and are immutable. GitHub locks the tag and the asset once published, and refuses to reuse the tag name even after deletion. Each release also gets an attestation that binds the tag, the commit, and the asset digests.

Builds are signed by the certificate with SHA-256 fingerprint `29:BB:B3:CC:45:74:51:57:E8:B0:08:D2:A9:A6:2A:D4:D4:EA:E3:CD:7D:45:A7:9E:E5:77:35:84:42:73:EE:36`. A different fingerprint means the file is not from this project. Sideloaded builds are not distributed through Google Play and do not update themselves.

## Use

1. Open the app and tap 通知へのアクセス設定を開く to grant notification access.
2. Enter the package name of the YouTube app you use, or pick a preset, then save.
3. Tap Discordと連携 and approve the request in Discord's own consent screen.
4. Turn sharing on and start a video in the selected app.
5. Check the result in a Discord client, on your own profile.

Pausing keeps the title and switches the state to paused. Stopping playback, losing the session, a missing title, buffering, losing the notification listener, or turning sharing off all request that the presence be cleared. The app never guesses a video URL.

連携を解除 deletes this device's stored credentials and closes the connection. It does not revoke the OAuth grant. Revoke that in Discord's authorized apps settings.

## Connect your own Discord application

The bundled application ID `1567132201588367360` and its redirect URI belong to the maintainer's Discord application. Do not ship a build that uses them. Register your own application in the [Discord Developer Portal](https://discord.com/developers/applications), enable **Public Client** in its OAuth2 settings, and add this redirect URI with your own ID in place of the digits:

```text
discord-1567132201588367360:/authorize/callback
```

Then change the ID in all three places, so the native client, the manifest scheme, and the stored-credential binding agree:

| File | Value to change |
| --- | --- |
| `app/src/main/java/cc/lem0n/androidrichpresence/presence/PresenceModels.kt` | `DEFAULT_APPLICATION_ID` |
| `app/src/discord/cpp/discord_bridge.cpp` | `applicationId` and `redirect` |
| `app/src/discord/AndroidManifest.xml` | `android:scheme` |

The app has no field for a client secret, a bot token, or a user token, and it never asks for one. Stored credentials are bound to the application ID, so changing the ID requires linking again.

## Privacy

Android grants notification access to the whole notification stream. This app reads only the media session of the package you selected: title, media ID, and play state. It does not parse notification bodies.

While sharing is on and an authenticated connection is up, the video title and the play or pause state go to Discord. No other data leaves the device.

Access and refresh tokens are encrypted with AES/GCM under a key in the Android Keystore. A random IV, the application ID, and a format version are used as associated data. The file lives in `noBackupFilesDir`, which is excluded from backup. Tokens never appear in the UI, in service diagnostics, or in logs. Strings copied inside the SDK itself cannot be fully wiped on this side.

## How it behaves

One owner in the process serializes authentication and publishing. The notification listener only watches media, so rotating the screen or reconnecting the listener does not rebuild the auth owner. Updates run one at a time and coalesce waiting changes onto the latest state. An update that gets no answer in 15 seconds discards the whole SDK client and rebuilds the authenticated connection. Retries run at 1, 2, 4, 8, 16, and 32 seconds, then at most once every 60 seconds. Frequent playback changes do not reset that backoff.

`ClearRichPresence()` has no acknowledgement. "表示の消去を要求しました" means the request was sent, not that Discord removed the activity. Force-stopping the app or letting Android kill it gives no guarantee of an immediate clear. [README.ja.md](README.ja.md) records what was measured on a device and which conditions stay unproven.

## Verification scripts

`scripts/` holds three Python 3 programs that drive a device over adb and save their evidence. They need a connected device and an installed debug build. Each prints its own prerequisites.

| Script | What it checks |
| --- | --- |
| `verify_youtube.py` | Play, pause, resume, and a title change in the selected player |
| `verify_lifecycle.py` | Presence after force-stopping both apps, with the Discord profile already open |
| `verify_background.py` | Reconnection after a 160-second device network outage |

The evidence directories contain video titles, and `verify_background.py` turns device networking off for a while. Review the output before you share it, and use a device you are willing to disturb.

Unit tests cover delivery gating before authentication, storage failures, token rotation, coalescing, clear ordering, stale responses, the update timeout, recovery from a long outage, deferred refresh while offline, and unlinking. The Keystore instrumentation test uses fabricated credentials only.

## Contributing

Open an issue before a large change. Run `:app:testDebugUnitTest` and `:app:lintDebug` for both variants before you send a patch.

## License

MIT. See [LICENSE](LICENSE).
