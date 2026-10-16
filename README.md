# FocusGuard

A lightweight, fully offline, open-source screen-time tracker and app blocker for
Android — inspired by Stayfree, but built so that **nothing lingers in the
background**: no polling loops, no persistent services, no exact alarms, no
network access at all, and **no data that survives an uninstall**.

## Features (all free, all local)

- **Screen-time tracking** — per-app daily usage with a dashboard, top-app list
  and a 7-day bar chart, powered by Android's own `UsageStatsManager`.
- **App limits** — a per-app daily limit with a blocking screen when it's used up.
- **Block now** — temporary (15m / 30m / 1h / until midnight) block-everything sessions.
- **Focus & Sleep schedules** — recurring time windows (including overnight
  ones like 23:00–07:00) that block everything or just your limited apps.
- **Website blocking** *(optional)* — checks your browser's address bar against
  a local blocklist and shows the blocking screen on a hit.
- **PIN + biometric lock** — protects limits and settings (PBKDF2-hashed PIN).
- **CSV export** — your last 90 days of usage, via the system file picker.
- **Daily reminder** *(optional)* — a notification with your total screen time.

Deliberately **not** included: accounts, cloud sync, leaderboards, ads,
analytics, or any other online feature.

## How it stays out of your battery

| Concern | Design |
|---|---|
| Usage counting | Batched `UsageStatsManager.queryEvents` reads since a persisted checkpoint — at most once every 30 s while you switch apps, plus on screen open. Never polling. |
| Real-time blocking | One opt-in accessibility service that listens **only** to `typeWindowStateChanged` (which app came forward). No screen-content access. |
| Website blocking | A second, optional service that reads browser address bars only, throttled, with a hard node cap. |
| Schedules | Enforced lazily at the next app switch; inexact alarms (`setWindow`) only for start notifications. |
| Midnight reset | Computed from the date on the data rows when they are read. There is no midnight alarm. |
| Reinstall | `allowBackup=false`, no transfer rules, no account, app-private storage only → uninstall removes 100 % of it. |

## Permissions (and why)

- `PACKAGE_USAGE_STATS` — count per-app usage (user-granted in Settings).
- Accessibility — the app monitor (foreground app only) and the optional
  website blocker (browser addresses only). Both disclosed in onboarding.
- `POST_NOTIFICATIONS` — optional daily reminder and schedule-start alerts.
- `RECEIVE_BOOT_COMPLETED` — re-arm schedule alarms after a reboot.
- `QUERY_ALL_PACKAGES` — list your apps for the limits screen (sideloaded app).

## Building from source

Requires JDK 17 and the Android SDK (platform 35, build-tools 35).

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # engine unit tests
./gradlew checkApkSize           # release build + hard 20 MB size gate
```

The signed release APK lands in `app/build/outputs/apk/release/`.

For reproducible sideload builds the repo carries a **test-only** keystore;
never publish an app signed with a committed key.

## Installing on your phone

Copy `app-release.apk` to the phone and tap it (allow "install from unknown
sources"). Then follow onboarding:

1. Grant **Usage access**.
2. Enable **FocusGuard → App monitor** in Settings → Accessibility.
3. Optionally enable **FocusGuard → Website blocking** if you use site blocking.

### Xiaomi / HyperOS notes

MIUI/HyperOS aggressively kills background components. If blocking stops
working: Settings → Apps → FocusGuard → Battery saver → **No restrictions**,
and enable **Autostart**. This only keeps the accessibility service alive —
FocusGuard itself still has nothing running in the background.

## Privacy

FocusGuard has no INTERNET permission. It cannot phone home even if it wanted
to. All data lives in app-private storage and dies with the app.

## License

MIT — see [LICENSE](LICENSE).
