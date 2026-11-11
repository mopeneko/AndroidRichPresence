# AndroidRichPresence feature map

| Feature | Journeys | Proof |
| --- | --- | --- |
| [Launch and resume](launch.md) | Launch and return from Home | Visible `YouTube Presence`, build-specific authentication status, and resumed MainActivity. |
| [Monitoring](monitoring.md) | Source presets, custom validation, persistence, notification access, playback | Saved target, permission and listener state, paired MediaSession and observed playback. |
| [Discord](discord.md) | Link, sharing, clear, unlink, background recovery, process exit | Auth state, matching SDK acceptance, and separately observed remote profile. |

Record coverage per journey. A monitor-only build cannot prove Discord behavior. Authenticated and background checks require a designated test account; media checks require a usable selected player. Record the route attempted and missing prerequisite for unreachable journeys.

A run needs one drivable device that holds this checkout's build. Three prerequisites decide that, and each one fails in a way that looks like an app bug:

- **Debug build and release install collide.** `install -r` answers `INSTALL_FAILED_UPDATE_INCOMPATIBLE` when the keys differ. Keep the retained install and use a separate AVD.
- **A locked device is not drivable.** `deviceLocked=1` routes taps to the keyguard; layout dumps then show `keyguard_*` nodes while `am start` still reports `Status: ok`.
- **A shared emulator serial belongs to whoever is driving it.** Check the foreground activity before the first tap.

`run-as` works only on debuggable installs. Release installs still verify through layouts, screenshots, and `dumpsys`.
