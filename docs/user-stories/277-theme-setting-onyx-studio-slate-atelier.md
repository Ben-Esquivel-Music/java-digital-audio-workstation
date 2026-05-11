---
title: "Theme Setting: Onyx Refined, Studio Slate, and Atelier"
labels: ["enhancement", "ui", "ui-overhaul", "phase-3", "theme", "design-system"]
---

# Theme Setting: Onyx Refined, Studio Slate, and Atelier

## Motivation

Phase 3 of the UI Design Book §6 migration roadmap. Builds on: #260 (token contract), and consumes the entire Phase 2 component library so that swapping a theme requires *no* structural CSS changes.

UI Design Book §3.1 specifies three palettes — A "Onyx Refined" (default, set by story 260), B "Studio Slate" (warm-orange single accent, near-monochrome console aesthetic), and C "Atelier" (light, navy accent, daylight-studio target). §6 (migration roadmap) is explicit that Phase 3 validates the Phase 1 token system: "Each theme is a stylesheet that overrides *only* token values. No structural CSS rules need to change."

This story is the proof-of-design: Palettes B and C ship as user-selectable themes, applied by loading a *single* stylesheet that re-declares the lookup colours from story 260. If the Phase 1 / Phase 2 work is correct, every control re-themes for free.

The user-visible target: open Preferences → Appearance → Theme, pick one of the three options, the whole UI updates without restart. Coordinates with story 194 (WCAG-Accessible Color Themes) — story 194 covers contrast validation; this story covers the token-driven theming mechanism. Treat 194 as *adjacent* (do not duplicate); this story focuses on the mechanic, 194 on the contrast guarantees.

## Goals

- Add two new theme stylesheets:
  - `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/themes/studio-slate.css` — overrides every token declared in the Palette A block (story 260) with the Palette B values from UI Design Book §3.1's table:
    - `-surface-bg: #101115; -surface-1: #181A20; -surface-2: #22252D; -surface-3: #2C2F38;`
    - `-line-soft: #262932; -line-strong: #34384A; -focus-ring: #FF7A45;`
    - `-text-hi: #F0F1F4; -text: #B0B5C0; -text-mute: #727784;`
    - `-text-on-accent: #15161B;` (explicitly declared — near-black, reads on the orange `-accent`; do not let it inherit from Palette A so each theme stylesheet is self-contained.)
    - `-accent: #FF7A45; -accent-soft: rgba(255, 122, 69, 0.14);`
    - `-ok: #74C28A; -warn: #E0B764; -danger: #E26060;`
    - Meter ramp from §3.1.
  - `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/themes/atelier.css` — Palette C light theme:
    - `-surface-bg: #F4F4F0; -surface-1: #FFFFFF; -surface-2: #EDEDE7; -surface-3: #E3E2DA;`
    - `-line-soft: #DEDDD4; -line-strong: #C4C2B7; -focus-ring: #244B8C;`
    - `-text-hi: #15161B; -text: #3D404A; -text-mute: #73767F;`
    - `-text-on-accent: #FFFFFF;`
    - `-accent: #244B8C; -accent-soft: rgba(36, 75, 140, 0.14);`
    - `-ok: #3FA76A; -warn: #B57A1B; -danger: #B5273B;`
- Add `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/theme/ThemeManager.java` — a tiny class with:
  - `enum Theme { ONYX_REFINED, STUDIO_SLATE, ATELIER }`
  - `ObjectProperty<Theme> activeThemeProperty()`
  - `applyTo(Scene scene)` — adds the appropriate stylesheet to `scene.getStylesheets()`, removing any prior theme stylesheet first. The `styles.css` (Palette A baseline) always remains loaded; the theme sheet is layered on top via JavaFX's lookup-colour resolution.
  - Persists the user's choice in the existing preferences store.
- Wire `ThemeManager` into a new Preferences section "Appearance → Theme" with the three options. Reuse the dialog chrome from story 276.
- The active theme is restored at startup from the persisted preference.
- Update the existing `DarkThemeHelper` (`daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/DarkThemeHelper.java`) to delegate to `ThemeManager`. If its responsibilities are narrow enough (only "apply the dark stylesheet"), deprecate the helper and replace callers with `ThemeManager.applyTo(scene)`. Leave the file as a thin shim with `@Deprecated` annotations for one release cycle.
- Acceptance criteria — every control / panel from Phases 1 and 2 must re-theme for free:
  - Transport bar (story 262), Buttons (story 264), Icons (story 265 — they inherit `-text-hi`), Mono numerics (story 266), LevelMeter (story 267), Knob (story 268), Fader (story 269), TrackStrip (story 270), MixerChannelStrip (story 271), Inspector (story 272), Notification toast (story 273), Status bar (story 274), Browser (story 275), Dialogs (story 276).
  - If *any* of those controls fail to re-theme — i.e., they have a hardcoded colour somewhere — the story is incomplete. The TokenValidationTest from story 260 should already catch this, but extend it to assert "post-load with Atelier theme applied, no resolved colour resolves to the literal `#000000` background (Palette A default) — every panel renders the Atelier `#F4F4F0` warm white".
- Tests:
  - `ThemeSwitchSmokeTest`: load `main-view.fxml` with `styles.css`, apply `studio-slate.css`, walk the scene graph, assert each region's resolved `-accent` lookup is `#FF7A45` and not `#7C8CFF`.
  - `ThemeLightnessTest`: apply `atelier.css`, assert the root pane's background is light (`> 240` for each RGB channel). Useful to catch a contributor missing a `surface-bg` token override in the new sheet.
  - `ThemePersistenceTest`: set theme to Studio Slate, save preferences, simulate restart (rebuild `ThemeManager`), assert active theme deserialises back to Studio Slate.
  - Add `LegacyHardcodedColorAuditTest` that walks the UI module's Java sources for `Color.web(...)` and `Color.rgb(...)` calls outside the `themes/` directory; assert zero matches (controls must consume tokens via CSS, not hardcode colour in Java).

## Non-Goals

- Adding more palettes (high-contrast, sepia, etc.) — out of scope.
- Real-time theme switching with animation — themes swap instantly per §3.5's "Hover / press / selection are instantaneous".
- Per-panel theme overrides — themes are global.
- WCAG contrast validation — that is story 194's domain; this story's tokens are *designed* per the design book but not formally validated. If 194 has landed by the time this story is implemented, run its checker against each theme and adjust if any pair fails.
- Light/dark *automatic* switching based on OS theme — out of scope; the user chooses explicitly.
- Replacing `Color.web("#…")` calls everywhere — only outside `themes/` per `LegacyHardcodedColorAuditTest`. Some plugin views may keep their hex; the audit tolerates a sentinel `@HardcodedColorAllowed` annotation with a TODO.

## Technical Notes

- The token override mechanism is JavaFX's lookup-colour cascade: a child stylesheet that re-declares a lookup colour overrides the parent's. `styles.css` declares the lookups; theme sheets re-declare them. No `@import` or pre-processing is needed.
- `Text-on-accent` differs per palette — for Onyx Refined it's `#0B0B0E` (near-black), for Studio Slate it stays near-black, for Atelier it becomes `#FFFFFF` (white on navy). Verify this token is consumed correctly by every primary button across all themes.
- This story is the user-visible payoff of *all* the Phase 1 / Phase 2 work — three themes, one mechanic, every control re-themes for free.
- Theme display names ("Onyx Refined", "Studio Slate", "Atelier") and the Preferences section label come from the existing `Messages.properties` resource bundle. Skill §14.
- Reference: UI Design Book §3.1, §6.
