---
title: "Performance Stage View: Oversized Skinned Controls for Live Use"
labels: ["enhancement", "ui", "ui-overhaul", "phase-4", "performance-stage", "accessibility"]
---

# Performance Stage View: Oversized Skinned Controls for Live Use

## Motivation

Phase 4 of the UI Design Book §6 migration roadmap. Builds on: #267 (LevelMeter `size-performance`), #269 (Fader `size-performance`), #270 (TrackStrip — performance size variant placeholder), every other Phase 2 control with a size variant.

UI Design Book §2.5 ("Custom Control + Skin for non-trivial widgets") promises: "a 'concept B' theme is a stylesheet swap, not a rewrite". Concept E ("Performance Stage", §4) is the *biggest* payoff of that promise. When the user is performing — live, on stage, on a touch device, or with the screen 1.5 m away — they cannot read 11 px text. Performance Stage is a *mode*, not a separate app, that swaps the standard layout for a giant-control cockpit:

- **64 px tall** transport buttons.
- **48 px** monospaced clock.
- Track strips become "tiles" — one per track, large mute / solo / cue buttons.
- Cue buttons trigger clip launch (Ableton-Session-style) without leaving the view.
- Meters get a dedicated band across the top: stereo bus, LUFS, true peak.
- Settings, browser, inspector are hidden — accessed via a single `☰` menu that expands to a translucent overlay.

§4 marks this as Medium-risk, High-wow, Medium implementation cost — "best built as a *view* on top of the same controls — every fader is the same `Control` underneath, just skinned at a larger size. This is exactly the Control/Skin payoff promised by §2.5."

The §3.7 Touch density is *not* the same as Performance Stage — Touch is 32 px row height; Performance Stage is 64 px transport buttons and 18 px track names. PS is a distinct *view*, not a density.

## Goals

- Create `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/views/PerformanceStageView.java` — a top-level view replacing the standard `BorderPane` content when activated. Layout from the §4 Concept E mockup:
  - **Top band**: stereo bus meter (left), LUFS / true peak / PLR readouts (centre), all using `size-performance` from stories 267 / 269.
  - **Centre band**: oversized monospaced clock — 48 px, mono, the time display from story 266 at a new `.numeric-display-stage` (48 px) variant.
  - **Transport row**: 64 px tall buttons (`dawg-button.size-stage` — a new size variant added to the unified button system from story 264 specifically for this view). PLAY · STOP · REC · LOOP, no icons (text only per §1.4 / story 265).
  - **Track tile grid**: track tiles, each a `TrackStrip.size-performance` (size variant placeholder added in story 270, now consumed). Each tile shows: index, name (18 px), inline meter, M / S / R buttons (28 px touch-size), CUE button (linked to clip launch — see Non-Goals).
  - **Floating `☰` hamburger** at the bottom-right that opens a translucent overlay with: switch to Standard view, Audio Settings, Project / File menu — i.e., the things that *can't* be on the stage but might be needed.
- Add the size variants needed by this view to the controls' stylesheets:
  - `dawg-button.size-stage` in story 264's button CSS — 64 px tall, `-spacing-md -spacing-lg` padding, font 18 px weight 600.
  - `.numeric-display-stage` in story 266's typography rules — 48 px mono weight 500.
  - `LevelMeter.size-performance` in story 267's meter CSS — 24 × 320 px with dB tick marks.
  - `Fader.size-performance` in story 269's fader CSS — proportional larger.
  - `TrackStrip.size-performance` in story 270's track-strip CSS — 80 px row height, 18 px name, larger meter.
- View activation: from the existing View menu (story 077), add "Performance Stage" as a new view alongside Arrangement / Mixer / Editor / Mastering. Keyboard shortcut `F11` (or whatever does not collide with full-screen — defer to `KeyBindingManager`).
- View deactivation: explicit "Exit Performance Stage" button in the floating `☰` overlay, plus `Esc` keypress.
- Transition: per §3.5, view switches are 180 ms `EASE_OUT`. Honour the Reduce Motion flag (story 279).
- Tests:
  - `PerformanceStageActivationTest`: activate Performance Stage, assert the scene root replaces the standard `BorderPane` content with `PerformanceStageView`. Assert the previous arrangement view is unloaded (or hidden, depending on lifecycle decisions).
  - `PerformanceStageSizingTest`: render at default resolution, assert transport button height is 64 ± 2 px, clock font is 48 px, track tile row height is 80 ± 2 px.
  - `PerformanceStageMeterSizeTest`: assert master meter is `size-performance` (24 × 320 px).
  - `PerformanceStageDeactivationTest`: press `Esc`, assert view returns to the previously active standard view.
  - `PerformanceStageThemeTest`: switch to Atelier theme (story 277), assert Performance Stage re-themes — Atelier light surfaces, navy accent — without code changes.

## Non-Goals

- Implementing clip launch / "session view" (Ableton-Session-style). The CUE button is wired to a placeholder that fires a typed `CueLaunchRequestedEvent` (a `javafx.event.Event` subclass with `EventType<CueLaunchRequestedEvent> CUE_LAUNCH_REQUESTED`) via `Node#fireEvent`, so it bubbles through the scene graph and a future audio-engine consumer can listen with `addEventHandler`. The *audio engine implementation* of session-style clip launch is a separate story (not yet filed). This story stops at "the UI exists; the button fires the event" — but the event is properly typed per skill §12, not an ad-hoc callback or string-keyed bus.
- Adding a Performance Stage-specific theme — the existing themes apply. (If the user wants high-contrast on stage, that's already a theme/density choice.)
- Multi-monitor span — Performance Stage occupies the active window. Multi-monitor / docking is story 282.
- Hardware controller mapping for stage cue buttons — story 152.
- Per-tile waveform thumbnails in the stage tiles — defer; the design book mockup shows no waveforms in stage tiles.
- Bluetooth / wireless transport-control interaction — out of scope.

## Technical Notes

- The §2.5 payoff is literal here — every control in this view is the *same* `Control` instance that the standard view uses, with a different size-variant style class. There is no parallel "performance" widget tree. This is the test of whether Phases 1–3 actually built the right foundation.
- The Performance Stage's bigger control sizes will push the layout-margin tokens (`-spacing-xl` 24, `-spacing-xxl` 32) to their useful limits. If §3.3's spacing scale proves insufficient (e.g., needing 48 px gaps), extend the scale rather than hardcoding values inline — keep the grid-only invariant from story 261.
- Long-horizon. Defer until Phases 1 and 2 ship.
- Transport button labels (PLAY / STOP / REC / LOOP) and the "Exit Performance Stage" overlay item come from the existing `Messages.properties` resource bundle — even though they look like fixed acronyms, treat them as i18n keys so localized builds can render culturally appropriate equivalents (skill §14).
- Reference: UI Design Book §2.5, §3.7 (density vs view distinction), §4 Concept E.
