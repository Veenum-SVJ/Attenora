# FocusGuard × Curbox features — design spec

Date: 2026-10-17 · Status: approved by user (all four features, single pass)

## Background

Curbox (curbox.app, github.com/curbox-app/curbox-android, GPL-3.0) is an open-source
Android blocker. Research (agent-reach / Exa) identified four of its features that
FocusGuard lacks. Curbox is open source, so we study its behavior/docs directly —
no APK reverse engineering. GPL: we re-implement behavior in FocusGuard's own style;
we do not paste Curbox code.

## Features

### 1. Unlock challenges on the block screen
- When the block overlay / session-grant prompt appears, the user must pass a
  challenge before extra time is granted: `math` | `quote` (type the sentence) |
  `intent` (state why you are unlocking) | `none` (stay blocked, no unlock).
- Reuses the existing, unit-tested `ChallengeEngine` (randomMath/checkMath,
  matchesQuote/normalize).
- Enforcement: `SessionPromptActivity` (session grants) and `BlockOverlayActivity`.
  Back/Home never bypasses the challenge.
- Written intents are stored (DataStore: last intent + timestamp) and shown back
  on the next attempt ("last time you wrote: …").

### 2. Delay & unlock quota
- Delay: configurable 0–60 s countdown before unlock buttons become tappable.
- Quota: maximum N unlocks per usage day (default 3, 0 = unlimited), reusing the
  existing `UsageDay` reset-hour math. Used-count + day-key stored in DataStore.
  Quota exhausted → unlock denied ("come back tomorrow").
- Pure helpers live in `Engines.kt` so they are unit-testable.

### 3. Grayscale mode
- Manifest: declare `android.permission.WRITE_SECURE_SETTINGS`.
- Settings → new "Grayscale" screen: pick apps; permission card showing
  `adb shell pm grant dev.opensource.focusguard android.permission.WRITE_SECURE_SETTINGS`
  until granted (same UX as Curbox; Shizuku out of scope — YAGNI).
- Accessibility service (already tracks foreground package): selected app in
  foreground → enable grayscale via secure settings; leaving → restore. Exact
  secure-settings keys/values taken from reading Curbox's published source/docs.
  Also restore on screen-off and on service connect so a crash cannot leave the
  display stuck monochrome.
- Grayscale is an alternative to hard blocking: app stays usable, just colorless.

### 4. Focus session timer
- Start a 15/30/60/custom focus session from the dashboard / In-App Blocking screen.
- Enforcement reuses the existing `blockAllUntil` machinery (same path as Block All).
- Live countdown on the In-App Blocking screen + quiet ongoing notification with
  remaining time (extend existing `Notifier`).
- Completed minutes recorded per usage-day → "Focused today" stat on Usage screen
  (DataStore: focus day-key + accumulated minutes; accrual finalized when a session
  ends, tolerant of process restart).
- Ending a session early requires the unlock challenge / PIN.

## Data & storage
- All new settings are DataStore preferences (SettingsRepository) — no DB migration.
- No new tables.

## Error handling
- Grayscale permission missing → feature toggle disabled with Grant card, never a crash.
- Restoring display state is idempotent (`settings put` with known values).
- Quota/day rollover uses the same usage-day key as limits, so a reset-hour change
  cannot strand the counter.

## Testing
- Unit: `EnginesTest` additions — quota/day rollover, delay clamping, focus-minute
  accumulation, intent normalization.
- Build: `./gradlew assembleDebug` + `./gradlew testDebugUnitTest`, exit codes checked.
- Emulator: grant WRITE_SECURE_SETTINGS via `pm grant`, trigger grayscale, run a
  short focus session, walk the challenge flow (math / quote / intent), screenshots.

## Verification of delivery
- Rebuilt `app/build/outputs/apk/debug/app-debug.apk` newer than all sources.
- Report which checks passed/failed honestly.