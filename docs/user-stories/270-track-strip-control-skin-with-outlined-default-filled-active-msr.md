---
title: "TrackStrip Control + Skin with Outlined-Default / Filled-Active M/S/R"
labels: ["enhancement", "ui", "ui-overhaul", "phase-2", "controls", "arrangement"]
---

# TrackStrip Control + Skin with Outlined-Default / Filled-Active M/S/R

## Motivation

Phase 2 of the UI Design Book §6 migration roadmap. Builds on: #260, #261, #263, #267 (LevelMeter), #265 (icons).

UI Design Book §5.3 ("Track strip (arrangement view)") and §1.5 (tile inflation) describe what's wrong today and what the replacement looks like. The current `TrackStripController` paints a track row using `.track-item` with a purple drop-shadow on hover (`styles.css:307–309`), and M/S/R as three orange/green/red buttons with hover-inverts (`styles.css:322–366`). UI Design Book §7.5 lists this exact pattern as a veto: "**Multi-coloured semantic palettes.** `M` orange, `S` green, `R` red is cute but unjustifiable: those are not three semantics, they are three UI states of one thing (track gating). Use one fill style and let position + glyph (M / S / R) carry the meaning."

The §5.3 replacement specifies:
- 28 px tall row (default density).
- Layout left → right: drag handle · index · colour swatch · name · M · S · R · level meter · meter readout · ⋯.
- States: default, hover (`-surface-3` background, no border movement, no shadow), selected (`-accent-soft` background), armed (`-danger` 2 px bar on the left edge + R filled), muted (name fades to `-text-mute` + M filled), soloed (S filled + warm).
- M/S/R outlined by default; **filled** when active. The fill colour encodes the state semantically: `-text` for M, `-warn` for S, `-danger` for R. No hover inversion.

This story creates the `TrackStrip` `Control + Skin` and migrates the existing arrangement-view track list from the ad-hoc `track-item` rendering. The same control is reused for the inspector's track header.

## Goals

- Create `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/controls/TrackStrip.java` as a `javafx.scene.control.Control` with:
  - `ObjectProperty<TrackId> trackIdProperty()` — the model identifier (uses the existing `daw-sdk` track domain).
  - `IntegerProperty trackIndexProperty()`.
  - `StringProperty trackNameProperty()`.
  - `ObjectProperty<Color> trackColorProperty()`.
  - `BooleanProperty mutedProperty()`, `soloedProperty()`, `armedProperty()`.
  - `BooleanProperty selectedProperty()`.
  - Embedded `LevelMeter getMeter()` (size-inline by default).
  - `BooleanProperty showMeterProperty()` defaults true.
  - Standard `StyleableProperty` bindings consuming `-surface-1`, `-surface-3`, `-accent-soft`, `-danger`, `-warn`, `-text`, `-text-hi`, `-text-mute`, `-line-soft`.
  - Override `getUserAgentStylesheet()` to return `TrackStrip.class.getResource("track-strip.css").toExternalForm()`.
  - Provide a fluent `Builder` (`TrackStrip.create().trackId(id).name("Drums").color(...).showMeter(true).build()`) alongside setters.
  - Expose a typed `EventType<TrackSelectionEvent> SELECTION_REQUESTED` (a subclass of `javafx.event.Event`) and a convenience `setOnSelectionRequested(EventHandler<TrackSelectionEvent>)`. Click handling inside the skin calls `fireEvent(new TrackSelectionEvent(trackId))` so the event bubbles through the scene graph and integrates with the standard event dispatch chain. Skill §12 names this as preferable to ad-hoc `Consumer<…>` callbacks because it integrates with FXML and the standard dispatch chain.
- Create `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/controls/skin/TrackStripSkin.java` extending `SkinBase<TrackStrip>` that overrides `compute{Min,Pref,Max}Height` per density variant (24 / 28 / 32 px from story 261) and `compute{Min,Pref,Max}Width` to accept the container's available width. `layoutChildren(x, y, w, h)` derives the meter width, swatch height, and toggle button size proportionally from `h` so a density change reflows without geometry surprises. Listeners on control properties are registered in the constructor and removed in `dispose()`. The skin lays out (left → right):
  - **Drag handle** (`DawgIcon.of("grip-vertical", SIZE_16)`).
  - **Index** (mono-numeric, 2-digit zero-padded).
  - **Colour swatch** (4 px wide × 16 px tall vertical bar in `trackColorProperty()`).
  - **Name** (`Label`, `-text-hi`, editable on double-click).
  - **Spacer** (HBox.setHgrow PRIORITY).
  - **M / S / R buttons** — each a small `ToggleButton` with class `.dawg-button.size-compact.icon-only.track-toggle`; outlined by default, filled when toggled. The toggled fill colour is encoded in CSS via `.track-toggle.mute:selected { -fx-background-color: -text; }`, `.solo:selected { -fx-background-color: -warn; }`, `.arm:selected { -fx-background-color: -danger; }`. Foreground text per the §3.1 `-text-on-accent` token (dark text on bright fill).
  - **Inline level meter** (`.size-inline` per story 267, 4 px × 16 px).
  - **Meter readout** (`Label`, `.numeric-caption` per story 266, e.g. `-12.4 dB`).
  - **Overflow** (`⋯` `DawgIcon`, opens a context menu).
- State styling per §5.3:
  - `:hover`: row background swaps to `-surface-3`. No shadow, no border swap (per §7.1, §7.3).
  - `:selected` pseudo-class: row background `-accent-soft`.
  - `armedProperty() == true`: a 2 px `-danger` vertical bar on the left edge of the row (drawn as a `Rectangle` inside the skin, not as a border on the row so layout doesn't shift).
  - `mutedProperty() == true`: name `-text-fill: -text-mute` and meter shows `-∞`.
- Migrate `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/TrackStripController.java` (and the FXML/list-cell factory it drives) to use `TrackStrip` instead of the current `.track-item` hand-rolled rows. The arrangement view's `ListView<Track>` cell factory becomes `cell -> new TrackStripCell(new TrackStrip())`, with the cell binding the `TrackStrip` properties to the track model. The existing `Track` model is unchanged.
- Define `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/controls/track-strip.css` with the styling rules above.
- Tests:
  - `TrackStripStateTest`: instantiate, set `mutedProperty = true`, assert `.mute` toggle gains `:selected`, name's text-fill resolves to `-text-mute`. Repeat for solo and arm.
  - `TrackStripArmEdgeBarTest`: set `armedProperty = true`, assert a child `Rectangle` of width 2 with fill `-danger` is present at the left edge.
  - `TrackStripHoverTest`: drive the `:hover` pseudo-class directly via `strip.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), true)` then `applyCss()` (synthetic `MOUSE_ENTERED` events do not reliably flip `:hover` in JavaFX's headless harness — `:hover` tracks real cursor location). Assert background resolves to `-surface-3`; flip back to `false` and assert it returns to `-surface-1`. Assert `getEffect() == null` throughout (per the §7.1 veto).
  - `TrackStripMSRColorTest`: assert the three M/S/R toggles when selected resolve to `-text` / `-warn` / `-danger` respectively, *not* the legacy `-orange` / `-green` / `-red`.
  - `TrackStripKeyboardTest`: focus the strip, press `M` / `S` / `R`, assert the corresponding property toggles (matches the existing arrangement-view shortcut conventions; see `KeyBindingManager`).
  - `TrackStripDisposeTest`: swap skin via `setSkin(null)`; assert all listeners registered by the original skin are unregistered from the control's properties (verify by mutating `mutedProperty`, `armedProperty`, `selectedProperty` and confirming no skin-side reaction).
  - `TrackStripBuilderTest`: build via the fluent builder, verify resulting properties match.

## Non-Goals

- Migrating the mixer view's channel strip — that is story 271 (`MixerChannelStrip` is its own control).
- Implementing track group / folder visuals (story 144).
- Drag-and-drop reordering interactions — already covered by story 054; this story preserves the existing reorder behaviour via the new `grip-vertical` handle.
- Adding automation-lane fold-out from within the strip — automation lanes are a separate surface (story 059).
- Live "armed-track flashing" effect — the bar is static; pulsing is decoration (§2.1 restraint).

## Technical Notes

- The "left-edge danger bar" is a `Rectangle` child of the skin, not a `-fx-border-color` on the row, because per UI Design Book §7.3 changing border width causes JavaFX layout reflow. A child Rectangle is positioned absolutely inside the skin's layoutChildren and doesn't perturb the row's intrinsic height.
- **ListView cell reuse.** The arrangement view's `ListView<Track>` cell factory builds **one** `TrackStrip` per cell and recycles it via JavaFX's standard cell virtualization — it does *not* `new TrackStrip()` per item. In `updateItem(Track t, boolean empty)`, the cell unbinds the previous track's properties from the recycled strip (`strip.getMeter().peakDbProperty().unbind()`, `strip.mutedProperty().unbind()`, etc.) and rebinds to the new track. Only the ~20–30 visible strips exist in the scene graph at any time, so only that many `AnimationTimer`s (one per embedded meter) run. At 200 tracks, this is the difference between 200 timers and ~30. Document this contract on `TrackStripCell` so future refactors don't regress.
- The colour swatch is editable via right-click → "Change colour" → a colour-picker popover; the popover styling is a follow-on (uses dialog/popover styles from story 276).
- All new user-facing strings on the strip (overflow menu items, default track name placeholder, peak-readout `dB` unit) come from the existing `Messages.properties` resource bundle; do not hard-code them in code or FXML (skill §14).
- This control is consumed by story 280 (Performance Stage) at `size-performance` — establish a size variant in CSS even though no consumer uses it yet, so story 280 can simply add a stylesheet rule rather than a code change.
- Reference: UI Design Book §1.5, §2.5, §5.3, §7.1, §7.3, §7.5 (vetoes — implicit AC).
