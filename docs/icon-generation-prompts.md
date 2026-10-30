# Attenora — image asset pack & generation prompts

Everything you need to generate, what each asset is for, and ready-to-paste
prompts. **Read §1 (style guide) first** — every prompt in §3 ends with the
same style block, so keep it as a snippet.

> What you do **NOT** need to generate: all in-app symbols (≈50 icons —
> stopwatch, shield, clock, chart bar, eye, sliders…) are Material-style
> vectors drawn in code in [FgIcons.kt](../app/src/main/java/dev/opensource/attenora/ui/icons/FgIcons.kt).
> No files to make for those.

---

## 1. Brand style guide (applies to every asset)

- **Art style:** flat vector, geometric, single-weight outlines, generous
  negative space. No gradients, no photorealism, no 3D, no text in the art,
  no drop shadows.
- **Core palette (exact hex):**

  | Role | Hex |
  |---|---|
  | Lavender accent (light UI) | `#C3A9F7` |
  | Lavender deep | `#9B8AFB` |
  | Primary purple (buttons/emphasis) | `#5B3FC4` |
  | Secondary purple | `#7361D8` |
  | Dark background (app) | `#0E0E16` |
  | Dark card | `#26262F` |
  | Block-screen background | `#0E1116` |
  | Success green | `#34C77B` |
  | Alert red | `#FF6B6B` |
  | Off-white / light bg | `#F7F6FB` |

  Launcher background variants (used by Settings → Appearance icon switch):
  `#0E0E16` · `#0B1512` · `#190B0C` · `#1A1309` · `#0C141E` · `#17151C`
  (near-black, one subtle hue each — the foreground must read on all six).
- **Subject language:** an hourglass / paused-clock silhouette fused with a
  shield. Friendly, not authoritarian. Rounded corners ≥ 8 % of canvas.
- **Monochrome rule:** every mark must survive as solid-white-on-transparent.

**Shared style block (append to every prompt below):**

```
Flat vector illustration, geometric, single 6px-equivalent stroke weight,
generous negative space, rounded caps and corners, no gradients, no text,
no 3D, no shadows, palette limited to lavender #C3A9F7 / purple #5B3FC4 /
near-black #0E0E16 / white, transparent background, crisp at 48px.
```

---

## 2. Asset inventory

| # | Asset | Target resource | Size / spec | Required? |
|---|---|---|---|---|
| A | Launcher foreground — default | `res/drawable/ic_launcher_fg.xml` (or a PNG replacing it) | Adaptive canvas **108×108 dp**, art inside the **72 dp safe zone** (66 %) | ✅ exists — regenerate |
| B | Launcher foregrounds ×5 (color variants) | `ic_launcher_fg_1..5.xml` | same as A | ✅ exists — regenerate |
| C | Monochrome / themed-icon layer (Android 13+) | `res/drawable/ic_launcher_monochrome.xml` | 108×108 dp, **solid white**, same silhouette | ✅ exists — regenerate |
| D | Launcher backgrounds ×6 | `res/values/colors.xml` → `ic_launcher_background*` (already wired to `mipmap-anydpi-v26/ic_launcher*.xml`) | **flat color only — no art needed** | ✅ done, nothing to generate |
| E | Notification small icon *(currently reuses the launcher rounded icon)* | new `res/drawable/ic_stat_fg.xml` → used by `Notifier` | 24 dp silhouette, pure white, ~93 % alpha, safe-zone 20/24 | ⚠️ recommended |
| F | Block-screen hero mark | `res/drawable/block_hero.png` (new) — shown on the full-screen blocker | 512×512 transparent PNG, centered mark, must read on `#0E1116` | optional |
| G | Empty-state illustrations ×3 | new drawables for Usage Stats / Usage Limits / first-run | 256×256 transparent PNG: (1) idle bar chart with a leaf, (2) an open padlock with a check, (3) an eye with a slash | optional |
| H | Feature graphic / banner (if you ever publish) | Play Store only, not in-app | 1024×500 | optional |

**Note on sizes:** `minSdk = 26`, so adaptive icons cover *every* device —
**no legacy density PNG mipmaps (mdpi…xxxhdpi) are required**. Generate at
1024×1024 px for the foregrounds and let the build keep them as vector/PNG
drawables; the adaptive XML already wraps them.

---

## 3. Copy-paste prompts

### A/B — Launcher foreground (repeat once per color variant)

```
Adaptive Android launcher icon foreground for an app called "Attenora", a
screen-time and app-blocking focus app. Centered hourglass silhouette whose
top half doubles as a shield outline, drawn as one continuous geometric form,
thick rounded strokes, perfectly centered, designed to sit inside a circle
mask with clear margin (art occupies only the middle 66% of a square canvas).
Foreground color lavender #C3A9F7 with a single #5B3FC4 accent stroke, on a
fully transparent background, square 1024×1024.
```
*(Variants 1–5: repeat with foreground colors `#C9E7DC`, `#F7D9C4`,
`#C4DCF7`, `#F7C4D2`, `#E7E0C4` — they sit on the five tinted backgrounds.)*

### C — Monochrome (Android 13 themed icon)

```
Android 13 monochrome themed-icon layer of a hourglass-shield mark: the exact
same silhouette as the launcher foreground but rendered ONLY in solid white
#FFFFFF, all fills and strokes pure white, no accent color, no gradient, no
outline variation, transparent background, square 1024×1024, perfectly
centered within the middle 66% of the canvas.
```

### E — Notification small icon

```
Android status-bar notification small icon: hourglass-shield mark as a single
solid white silhouette, no strokes thinner than 2dp at 24dp size, fully filled
white with transparency around it, no color, no detail beyond what stays legible
at 24×24 px, square 512×512, centered.
```

### F — Block-screen hero

```
Small hero illustration for a dark screen-background #0E1116 that says "time's
up, come back later": an hourglass gently lying on its side with a few lavender
#C3A9F7 grains still falling, one thin shield outline behind it, calm and
encouraging rather than alarming, flat vector, wide empty margins so it can sit
above a headline, 512×512.
```

### G1 — Empty state: Usage Stats

```
Empty-state illustration: a minimal three-bar chart where the tallest bar is
replaced by a small growing leaf, meaning "no screen time yet — good", flat
vector, lavender #C3A9F7 strokes on transparent background, friendly rounded
shapes, 512×512.
```

### G2 — Empty state: Usage Limits

```
Empty-state illustration: an open padlock with a small check mark floating
beside it, meaning "no limits set yet", flat vector, lavender #C3A9F7 and
purple #5B3FC4 only, transparent background, 512×512.
```

### G3 — Empty state: Hidden apps

```
Empty-state illustration: a calm eye crossed by a single soft diagonal line
with three small dashes drifting away, meaning "nothing is hidden yet", flat
vector, lavender #C3A9F7 strokes, transparent background, 512×512.
```

### H — Play Store feature graphic

```
1024×500 Play Store feature graphic: dark #0E0E16 background with a subtle
lavender gradient sweep from the left, the hourglass-shield mark on the left
third, generous empty space on the right for localized text, flat vector style,
no text, no devices, no screenshots.
```

---

## 4. How each asset is wired in (for hand-off)

- **A/B/C** → `res/drawable/ic_launcher_fg*.xml` + `ic_launcher_monochrome.xml`;
  already referenced by `res/mipmap-anydpi-v26/ic_launcher*.xml` and the six
  `activity-alias` entries in the manifest (the color picker in
  Settings → Appearance switches aliases, which is why launcher restart
  happens on change).
- **D** → colors only; nothing to generate.
- **E** → new drawable + one-line change in
  [Notifier.kt](../app/src/main/java/dev/opensource/attenora/enforcement/Notifier.kt#L53)
  (`setSmallIcon(R.mipmap.ic_launcher)` → `R.drawable.ic_stat_fg`).
- **F/G** → drop PNGs under `app/src/main/res/drawable/` and reference with
  `painterResource`. I can wire them the moment the files exist, e.g.:

```kotlin
Image(painterResource(R.drawable.block_hero), null)
```