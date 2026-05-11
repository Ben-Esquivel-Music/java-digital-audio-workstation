---
title: "Density Setting: Compact, Comfortable, Touch"
labels: ["enhancement", "ui", "ui-overhaul", "phase-3", "design-system", "accessibility"]
---

# Density Setting: Compact, Comfortable, Touch

## Motivation

Phase 3 of the UI Design Book §6 migration roadmap. Builds on: #260, #261 (row-height tokens), #270 (TrackStrip), #271 (MixerChannelStrip), #272 (Inspector), and every other Phase 2 control that consumes a row-height token.

UI Design Book §3.7 ("Density modes") specifies three user-selectable density profiles:

| Mode | Row height | Default for |
|---|---|---|
| Compact | 24 px | Mixer with many channels, browser lists |
| Comfortable | 28 px | Track list, dialogs (default) |
| Touch | 32 px | Tablet / hybrid laptops, live use |

§3.7 is also explicit: "Motion, type scale, and elevation are unchanged across density. Only padding and row height change." This is a small story — the row-height tokens already exist (story 261's `-row-compact`, `-row-default`, `-row-touch`). What's missing is a `DensityMode` user setting and the CSS class that swaps which row-height token a control uses.

## Goals

- Add `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/density/DensityMode.java`:
  - `enum DensityMode { COMPACT, COMFORTABLE, TOUCH }` with helper accessors for the underlying row-height token name.
  - `ObjectProperty<DensityMode> activeDensityProperty()`.
  - `applyTo(Scene scene)` — adds / removes a top-level style class (`.density-compact`, `.density-comfortable`, `.density-touch`) on the scene's root.
  - Persists the choice in the existing preferences store.
- Add CSS rules in `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/styles.css` keyed off the density class on the root:
  - `.density-compact .dawg-button.size-default { -fx-padding: -spacing-xs -spacing-sm; }` (collapses to row-compact, 24 px).
  - `.density-comfortable .dawg-button.size-default { -fx-padding: -spacing-xs -spacing-md; }` (28 px — already the baseline).
  - `.density-touch .dawg-button.size-default { -fx-padding: -spacing-sm -spacing-md; }` (32 px).
  - Mirror rules for `.track-strip` row height (story 270), `.mixer-channel-strip` width (story 271 — Compact = 72 px, Comfortable = 88 px), `.browser-list-row` row height (story 275), `.inspector-section-row` (story 272).
- Wire the setting into Preferences → Appearance → Density (uses dialog chrome from story 276; same panel as Theme from story 277). Three radio buttons.
- The active density is restored at startup from the persisted preference. Default is Comfortable.
- Verify the §3.7 invariant: "Motion, type scale, and elevation are unchanged across density." Specifically:
  - Type tokens (story 260) do not depend on density.
  - Elevation tokens (story 263) do not depend on density.
  - Motion durations (story 279) do not depend on density.
- Tests:
  - `DensityClassAppliedTest`: switch density to Compact, assert the root scene has style class `density-compact` (and not `density-comfortable` / `density-touch`).
  - `DensityRowHeightTest`: build a `TrackStrip` (story 270), measure layout height in each density mode (24 / 28 / 32 ± 1 px).
  - `DensityMixerWidthTest`: build a `MixerChannelStrip` (story 271), assert width is 72 px in Compact and 88 px in Comfortable.
  - `DensityInvariantTest`: switch density across all three values, assert the resolved `-fx-font-size` of `.body` text and the resolved elevation effects are *identical* — only padding/height tokens change.
  - `DensityPersistenceTest`: set density to Touch, simulate restart, assert deserialised mode is Touch.

## Non-Goals

- Density-per-panel overrides (e.g. "use Compact in the mixer, Comfortable elsewhere") — §3.7 is explicit: density is global. The §5.4 ASCII shows the mixer at Compact only as a *recommendation*; the mode itself stays global.
- Custom density values (`row-height = 30`) — three discrete modes only.
- Auto-density based on screen DPI or input device — out of scope; user chooses explicitly. Story 280 (Performance Stage) is the closest the design book gets to a "mode-driven density" and is its own story.
- Animating between densities — instantaneous swap per §3.5's "selection is instantaneous".

## Technical Notes

- The single root-scope style class (`.density-compact` etc.) is the cleanest way to thread density through every selector without introducing a Java-side switcher in every control's skin. JavaFX's CSS supports nested descendant selectors fine.
- **Spacing tokens consumed via `SpacingTokens.java` resolver.** JavaFX CSS does not type-check numeric lookups the way it does colour lookups (story 261). The density-keyed padding rules write to `-fx-padding` using the `SpacingTokens` java-side constants for any place where the lookup form is not honoured by the renderer. Verify each density × control combination resolves to the expected pixel padding; the `MainViewFxmlSpacingTest` from story 261 catches lookup-resolution failures.
- The "Comfortable defaults" should match the Phase 1 / 2 stories' baseline values — adding the density class should *not* change anything when density is Comfortable. Verify via the existing snapshot suite.
- Density display names ("Compact", "Comfortable", "Touch") and the Preferences section label come from the existing `Messages.properties` resource bundle. Skill §14.
- Reference: UI Design Book §3.7.
