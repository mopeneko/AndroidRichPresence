# AndroidRichPresence feature map

| Feature | Journeys | Proof |
| --- | --- | --- |
| [Launch and resume](launch.md) | Launch and return from Home | Visible `YouTube Presence`, build-specific authentication status, and resumed MainActivity. |
| [Monitoring](monitoring.md) | Source presets, custom validation, persistence, notification access, playback | Saved target, permission and listener state, paired MediaSession and observed playback. |
| [Discord](discord.md) | Link, sharing, clear, unlink, background recovery, process exit | Auth state, matching SDK acceptance, and separately observed remote profile. |

Record coverage per journey. A monitor-only build cannot prove Discord behavior. Authenticated and background checks require a designated test account; media checks require a usable selected player. Record the route attempted and missing prerequisite for unreachable journeys.
