# FocusGuard v1.1 — Stayfree-style rebuild (approved design)

Date: 2026-10-16 · Status: approved (20 structured decisions + 16 reference screenshots)

## 0. Hard constraints
- **100% offline**: no `INTERNET` permission, no accounts/sync/ads/telemetry.
- **APK ≤ 20 MB** (hard Gradle gate `checkApkSize`; v1.0 shipped at 1.95 MB).
- **Battery-safe**: no polling services, no exact alarms; inexact `AlarmManager.setWindow` only;
  accessibility traversal bounded + throttled; UsageStats reads are checkpointed deltas.
- **Custom icon set**: hand-drawn outline icons, no Material icon set.
- **Compose-only animations**, no new runtime dependencies (JSON via `org.json`).
- English-only strings in v1.

## 1. Visual language (from screenshots)
- Palette: background `#0E0E16`, card `#26262F` (20dp radius), lavender accent `#C3A9F7`,
  secondary accent `#9B8AFB`, white primary / gray secondary text. Dark default with
  **System / Dark / Light** override (Settings → Appearance).
- Top bar: pill search field ("Search in FocusGuard") with ☰ hamburger → **modal drawer**.
- Drawer: Home, Usage Limits, In-App Blocking, Usage Notifications, ──, Categories, Settings;
  footer shows **version 1.1.0**. (Stayfree's Level / Global Stats / Friends / Sign-in excluded — online only.)
- Bottom bar: exactly **3 two-line tabs** — Usage Stats · Usage Limits · In-App Blocking;
  active tab = lavender rounded-square icon chip.

## 2. Screens

### 2.1 Usage Stats (Home)
- Setup banner (repeat onboarding checks), date row: ‹ stepper › + period chips
  **Today / Yesterday / 7d / 14d / 28d / Custom** (Material3 `DateRangePicker`).
- Category filter dropdown + metric dropdown (Usage Time; notifications counts later).
- Chart card: **hand-drawn Canvas**, **bar / line toggle**; per-hour buckets for single days,
  per-day buckets for ranges; value labels on bars; legend Mobile App/Web split simplified to
  Apps vs Websites; "Total Usage: X →" footer.
- App list: icon, name, duration, share %, progress bar; tap → app detail (mini history + set limit).
- **Usage Patterns** tip cards (rule-based, dismissible).

### 2.2 Usage Limits
- Header + **Edit** toggle; collapsible sections with counts:
  **Limited Daily Usage / Variable Session Limits / Scheduled Blocks / Permanently Blocked**.
- Daily rows: icon, name, status ("Limit reached" / "1h 30m left"), progress bar, block glyph, switch.
- Session rows: switch only; when the app opens and no grant is active, **"How long?"** prompt
  (15m / 30m / 1h / until tomorrow) → grants a session window.
- Scheduled rows: window + days summary; permanent rows: always-on.
- **Block Now** card (15m / 30m / 1h / until midnight) at top.
- Expanding FAB → **Add Usage Limit** and **Block Keywords**.
- Add Limit flow: target picker (Apps / Websites / Categories multi-select) → type
  (Daily Usage Limit / Block on a schedule / Block permanently / Variable session limits) →
  optional **Limit Name** → Cancel / Save.
- Editing/deleting limits and toggling blocking is gated by the configured challenge (§5).

### 2.3 In-App Blocking
- Description header; expandable per-app cards with icon + chevron.
- Expanded: **Last 7 Days of Usage donut** (hand-drawn, element legend + %) then element toggles:
  - YouTube: shorts, video search, picture-in-picture, comments
  - Instagram: stories, reels, explore tab
  - Facebook: explore, stories · Snapchat: spotlight, stories · X: explore, search
  - TikTok: search, comments · WhatsApp: status, channels, Meta AI · Messenger: Meta AI
- Engine: **smart hide + auto-close** — accessibility service subscribed only to opted-in
  packages (cheap runtime filter), hide node where OS allows, else Back-navigation.
  UI shows honest caveat "may stop working when the target app updates" + per-app **test** button.

### 2.4 Categories (smart auto-categories)
- Category Management list: color dot, name, counts (Apps/Websites), special rows
  (Hidden Apps, Not Specified), starter domain packs (Adult / Gambling) bundled offline,
  **Add Category** FAB; rename/recolor/recolor members manually.
- Offline classifier (package + label heuristics) files apps into Social, Video, Games,
  Messaging, News, Shopping, Productivity, Tools; manual assignment always wins.
- Hidden Apps screen: searchable, excluded from totals and Usage Stats, remove via ✕.

### 2.5 Usage Notifications
Toggles + pickers: Usage Assistant (ongoing: current app + today's total),
Total Time Message every 10 min (updated on foreground transitions, no timer),
Daily reminder (time), Weekly summary (day + time), Morning Routine (time),
Night Owl bedtime reminder (time), **5 minutes left** warning (computed on app switch),
Block-hit alert, Schedule start/end alerts, **Block Notifications** (mute blocked apps —
requires optional NotificationListener, only cancels notifications from blocked apps).

### 2.6 Settings (hierarchical)
- **Lock Usage Limits**: sheet radio — No Protection / PIN Protection / Require a Challenge;
  challenge = Awareness Text (type a randomized quote) or Math, optional custom text, preview.
- **Usage Time Tracking**: daily reset time (hour, default midnight), first day of week
  (Mon/Sun), Ignore Website Visits, **Countdown for Usage Limits** overlay (drawn as
  accessibility overlay — no extra permission).
- **Appearance**: theme System/Dark/Light + 6 custom launcher icon variants.
- **Privacy & Data**: JSON backup/restore (localStorage file via SAF), CSV export, wipe all data.
- **Troubleshooting**: permission/service status, usage checkpoint, reconcile now, block-event log.
- **About**: version 1.1.0, privacy statement, support, credits + OSS licenses (room, compose…).
- Challenges gate: limit edits, in-app blocking toggles, PIN removal, wipe.

## 3. Data model (Room v2)
- Keep: `daily_usage`, `focus_schedules`, `blocked_sites`, `meta`.
- New: `usage_limits` (typed: DAILY / SESSION / SCHEDULE / PERMANENT; target APP / WEBSITE / CATEGORY),
  `categories`, `category_members`, `hidden_items`, `element_rules`, `element_usage`,
  `session_grants`, `block_events`. `app_limits` removed (destructive migration; export CSV first).
- Settings (DataStore): theme, week start, reset hour, challenge mode/text/math, all notification
  toggles + times, ignore websites, countdown on, launcher icon.

## 4. Enforcement
- Typed evaluation: DAILY (app + category rollup), PERMANENT, schedule windows (overnight OK),
  session grants, block-now, keywords (web), allowlist always wins.
- New accessibility service **ElementBlockService** (window-state + content events, package-filtered)
  = element detectors + countdown overlay.
- New **MuteListenerService** (notification listener) = block notifications while blocked.
- Block hits append to `block_events` and fire optional alert notification.

## 5. Verification gates
`testDebugUnitTest` (engines: typed limits, classifier, challenges, reset/week math, URL, schedules),
`lintDebug` 0 errors, `checkApkSize` ≤ 20 MB, `aapt dump badging` (no INTERNET, v1.1.0),
`apksigner verify`, then MIT commit → public GitHub repo **FocusGuard**.
