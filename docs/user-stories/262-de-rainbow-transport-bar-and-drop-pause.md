---
title: "De-Rainbow the Transport Bar and Drop the Pause Button"
labels: ["enhancement", "ui", "ui-overhaul", "phase-1", "transport"]
---

# De-Rainbow the Transport Bar and Drop the Pause Button

## Motivation

Phase 1 of the UI Design Book §6 migration roadmap. Builds on: #260 (tokens), #261 (grid).

UI Design Book §1.2 names the "rainbow toolbar" as the single most childish-feeling part of the current UI. `styles.css:138–216` gives every transport action its own coloured border: Play green, Pause cyan, Stop orange, Record red, Loop purple. The result reads as five fully-saturated equal-weight colours, which §1.1 calls "five fully saturated neon accents on a black surface" — the opposite of a pro tool's restraint.

UI Design Book §2.1 spells out the rule: "One accent at a time. The UI may use up to one accent on screen at any moment to direct the eye. (Record is the classic exception — record red is allowed to coexist because 'armed' is a separate semantic.)" Translated to transport: every button shares one structural style, only Record carries `danger`, and the active *state* (playing, looping) is communicated by a *pressed/active* `accent` fill — never by a per-button hue.

UI Design Book §5.1 also drops the Pause button entirely. Every standard DAW uses Play-toggles-pause; the second button is dead weight and is the cyan member of the rainbow. The current FXML wires Pause as a discrete button (`main-view.fxml:27` — `<Button fx:id="pauseButton" text="Pause" onAction="#onPause" styleClass="transport-button, pause-button"/>`); removing it requires the controller to make Play emit a pause event when transport is already playing.

This story consumes the tokens from story 260 and the grid from story 261 to deliver a transport bar that is one row of structurally identical buttons, where the only colour is `danger` on Record and `accent` on the currently-pressed-toggle (Play during playback, Loop when looping).

## Goals

- Remove `pauseButton` from `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/main-view.fxml` and remove `onPause()` from `MainController`. Update `TransportController` so the existing Play action is a toggle: pressing Play while playing pauses transport; pressing Play while stopped/paused resumes. Update `KeyBindingManager` so the Pause shortcut (if distinct) is removed or remapped to Play.
- Collapse the per-button transport styles (`.play-button`, `.pause-button`, `.stop-button`, `.record-button`, `.loop-button`) in `styles.css` into a single `.transport-button` style. Per-button hues, per-button borders, per-button hover glows are removed. Hover is a `surface-3` background swap per UI Design Book §7.3 (no border swap, no glow). The only retained per-button class is `.record-button` (kept for the Record-only `danger` fill) and `.loop-button` (kept only for the active-state `accent` toggle).
- Define two transport states in CSS, driven by `pseudoclass` not by per-button class:
  - `.transport-button:pressed-state` / `:armed` → `accent` background with `text-on-accent` foreground (used by Play while playing, Loop while looping).
  - `.transport-button.record-button:armed` → `danger` background with `text-on-accent` foreground.
- Wire the pseudo-class in `TransportController` (or expose a `BooleanProperty armed` on each transport button via a small `TransportButton` Java class — `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/controls/TransportButton.java`) so CSS state changes happen when the engine state changes. This is preparatory for the full `Control + Skin` migration in Phase 2 but does not require it yet — a plain `Button` with a CSS pseudo-class is sufficient.
- All transport buttons share one row height (36 px transport row from story 261's `-row-transport`), one corner radius (`-radius-1`), one padding token (`-spacing-xs -spacing-sm`). No bespoke per-button widths.
- Tests:
  - `MainViewFxmlSpacingTest` (extended) asserts there is no `pauseButton` fx:id and no `pause-button` style class in `main-view.fxml`.
  - `TransportControllerTest`: Play-while-stopped starts playback; Play-while-playing pauses; Play-while-paused resumes. The existing test suite already covers basic Play/Stop — extend with the toggle case.
  - `TransportStyleTest`: parse `styles.css`, assert exactly one declaration of `.transport-button` background-color (excluding the two `.record-button:armed` and `:armed` pseudo-class overrides). Assert there is **no** `-fx-border-color` differing per button class.
  - Headless visual test (if story 208 has landed): snapshot the transport bar in Stopped / Playing / Paused / Recording / Looping states; assert only one button is coloured at a time except in Recording + Looping where both the Record and Loop buttons are coloured (acceptable per the §2.1 record-exception clause).
- Update the user-facing changelog entry: "Pause button removed — Play now toggles pause, matching every other DAW. Keyboard shortcut unchanged."

## Non-Goals

- Replacing icons (the §2.4 "icon-or-label not both" cleanup is part of story 265).
- Switching to a Control + Skin transport bar (Phase 2, follow-on stories).
- Changing the transport row height beyond what the 4 px grid permits (already fixed at 36 px in story 261).
- Changing the time display, BPM label, metronome toggle, or ripple toggle visuals — those are addressed by stories 266 (mono numerics) and 276 (dialog/toggle cleanup).
- Adding the "subtime" bars/beats tier under the SMPTE time display (mentioned in §5.1) — defer to a follow-up.

## Technical Notes

- The Loop button stays *named* in CSS only because the active loop region overlay also paints `accent` and needs the same source-of-truth class to flip on. Consider in review whether `.loop-button:armed` is cleaner as `.transport-button:armed` plus a controller-level marker.
- After this story lands, `styles.css` lines 138–216 (the rainbow block) should be roughly 30–40 lines shorter; the diff is the headline visual of Phase 1.
- Reference: UI Design Book §1.2, §2.1, §5.1, §7.2 (per-element hue veto — implicit AC).
