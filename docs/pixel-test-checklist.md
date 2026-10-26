# Pixel test checklist

First run of Attenora on stock Android. Everything here was verified on a
HyperOS (Redmi) phone and on the emulator, so this pass is about the parts
stock Android does differently — mainly accessibility and the block overlay.

Device: __________  Android: ______  Build: 1.2.3 (versionCode 6)

## 1. Install (no gate)

- [ ] `adb install app-release.apk` succeeds on the first try.
      HyperOS needed the "Apps from unverified developers" toggle; stock
      Android has no such gate, so a failure here is a real failure.
- [ ] Launcher icon appears, correct A-mark, no broken mipmap.
- [ ] First launch shows onboarding, not a crash.

## 2. Permissions

- [ ] Usage access: the step turns from a button into "Usage access granted"
      without leaving the app twice.
- [ ] Notifications: dialog appears, allow, step ticks.
- [ ] Accessibility: **the expected friction point.** On Android 13+ the
      service is hidden until Settings > Apps > Attenora > ⋮ >
      "Allow restricted settings". Confirm the onboarding copy survives
      that detour and ticks on return.
- [ ] After granting, `adb shell dumpsys accessibility` lists
      `Service[label=Attenora]` under **Bound services** (not
      "Crashed services").

## 3. Blocking — the core claim

- [ ] Set a 1-minute limit on Calculator (Usage Limits > + > Apps).
- [ ] Use it past the limit. Expected: cover appears within ~1s, names the
      app and the reason, "Go home" returns to the launcher.
- [ ] Press recents/back into the app: cover stays, no flicker to the real
      app. This is the HyperOS failure mode (`startActivity` dropped);
      stock Android may not need the accessibility overlay at all, so
      confirm which path ran via `adb logcat -s FGD` (`cover` lines).
- [ ] Kill and reopen the app after the limit: still covered.

## 4. Website blocking

- [ ] Chrome: add a domain to Website Blocking, open it. Cover names the
      domain.
      Note: Chrome is a **disabled stub** in this build. If it does not
      block, record whether the browser was recognised at all
      (`web host` / `web HIT` lines) before blaming the rule.
- [ ] A second browser (Brave/Quetta/Firefox) blocks the same domain.

## 5. Reels

- [ ] YouTube Shorts: 3 swipes = 3, counter fades ~2.6s after the last one.
- [ ] Instagram Reels: 3 swipes = 3; opening a DM thread and swiping there
      counts 0.
- [ ] TikTok (if installed): feed counts, inbox counts 0.
- [ ] Counter is not touchable and does not block the video controls.

## 6. Brand + settings

- [ ] Settings > Theme: six swatches, tap Mint — app tint changes *and* the
      home-screen icon changes (check the launcher, not just the app).
- [ ] Tap Teal to return to default.
- [ ] Weekly summary day picker opens and keeps the choice after a restart.
- [ ] Light / Dark / System all render; dark stays true black.

## 7. Nothing leaves the device

- [ ] `adb shell dumpsys package dev.opensource.attenora | findstr INTERNET`
      returns nothing.
- [ ] Airplane mode on: app still opens, stats still load, blocking still
      works.

## Result

Record pass/fail per section and paste any `FGD` logcat lines that look
wrong into `test-report/pixel/`. A failure in 2 or 3 blocks the release;
cosmetic issues get filed for 1.2.3.
