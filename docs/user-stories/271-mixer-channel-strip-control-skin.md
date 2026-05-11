---
title: "MixerChannelStrip Control + Skin with Inserts, Sends, Pan, and Fader"
labels: ["enhancement", "ui", "ui-overhaul", "phase-2", "controls", "mixer"]
---

# MixerChannelStrip Control + Skin with Inserts, Sends, Pan, and Fader

## Motivation

Phase 2 of the UI Design Book §6 migration roadmap. Builds on: #260, #261, #263, #264 (buttons), #265 (icons), #266 (mono numerics), #267 (LevelMeter), #268 (Knob), #269 (Fader), #270 (TrackStrip M/S/R pattern).

UI Design Book §5.4 ("Mixer channel strip") specifies the full signal-chain control for one channel: I/O, inserts list, sends list, pan knob, fader (with integrated meter), M/S/R buttons, name. Today this is hand-rolled across several files and `styles.css:457–520`. The current `.mixer-channel` is 70 px wide and styled as a card with a border. §5.4 says: keep the width, drop the visible border in favour of column gutters at `-surface-bg`. And replace the purple circular fader thumb (already addressed by story 269's `Fader`).

This story brings the channel strip up to the same standard as the track strip (story 270) — a proper `Control + Skin + StyleableProperty` triplet that composes the Phase 2 controls (Fader, Knob, LevelMeter) and the unified button system (story 264) into the complete §5.4 layout.

## Goals

- Create `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/controls/MixerChannelStrip.java` as a `javafx.scene.control.Control` with:
  - `ObjectProperty<ChannelId> channelIdProperty()`.
  - `StringProperty channelNameProperty()`, `StringProperty inputLabelProperty()`, `StringProperty outputLabelProperty()`.
  - `ObservableList<InsertSlotModel> insertsProperty()`, `ObservableList<SendSlotModel> sendsProperty()`. (Slot models are PR-introduced records in `daw-sdk`/mixer or reused from existing types — pick whichever is cleaner.)
  - `DoubleProperty panProperty()` — bound to a `Knob` in the skin, bipolar.
  - `DoubleProperty faderDbProperty()` — bound to a `Fader` in the skin.
  - `BooleanProperty mutedProperty()`, `soloedProperty()`, `armedProperty()` — bound to the same outlined-default / filled-active toggle pattern from story 270.
  - `ObjectProperty<ChannelType> channelTypeProperty()` — `AUDIO`, `MIDI`, `BUS`, `MASTER`. (JavaFX has no `EnumProperty`; use `SimpleObjectProperty<ChannelType>`.) The strip's name colour and which controls are visible depend on type (the `MASTER` has no M/S/R; the `BUS` has no input selector).
  - Override `getUserAgentStylesheet()` to return `MixerChannelStrip.class.getResource("mixer-channel-strip.css").toExternalForm()`.
  - Provide a fluent `Builder` for the 12-property construction surface. Direct constructors and setters remain available.
  - Expose typed `EventType<InsertSelectedEvent> INSERT_SELECTED` and `EventType<SendSelectedEvent> SEND_SELECTED` (subclasses of `javafx.event.Event`) plus `setOnInsertSelected` / `setOnSendSelected` convenience methods. Slot click handlers in the skin call `fireEvent(new InsertSelectedEvent(insertId))`; events bubble through the scene graph and the Inspector (story 272) consumes them via the standard dispatch chain. Skill §12 — preferred over ad-hoc callbacks.
- Create `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/controls/skin/MixerChannelStripSkin.java` extending `SkinBase<MixerChannelStrip>` that overrides `compute{Min,Pref,Max}Width` per density variant (72 / 88 px) and `compute{Min,Pref,Max}Height` to fill the available vertical space. `layoutChildren(x, y, w, h)` derives insert-slot row height, fader column height, pan-knob diameter, and meter width proportionally from the assigned width and height — no fixed pixel offsets keyed to a single default. Listeners registered in the constructor are removed in `dispose()`. Lays out top → bottom per the §5.4 mockup:
  - Input/output labels (small mono caption per story 266).
  - Insert list — 4 visible slots + a "⋯" overflow. Each slot is a row: insert name, status dot (`-accent` if active, `-text-mute` if bypassed), click → opens the inspector to that insert (story 272).
  - Send list — 2 visible slots + overflow. Each send: send name, level (compact `Fader` from story 269 with `showMeter = false`), pre/post toggle.
  - Pan: a `Knob.size-28` (story 268) with `bipolarProperty = true`, units "L / R".
  - Fader: a `Fader.size-mixer` (story 269) with the integrated meter on its right. Curve `LOG_DB`.
  - M / S / R buttons — same outlined-default/filled-active widgets as story 270's `TrackStrip`, identical CSS classes (`.track-toggle.mute|solo|arm`) for visual consistency.
  - Fader value readout (`.numeric-value`) and channel name (editable on double-click).
- The strip's "card border" is dropped per §5.4. Visual separation between adjacent strips is a 1 px `-surface-bg` (same as the panel background) **gutter** — i.e., a fixed 4 px gap. No border, no shadow, no rounded corners on the strip itself. (The mixer panel as a whole sits at elevation 0 per story 263.)
- Width: 72 px Compact, 88 px Comfortable (selected by the density mode in story 278).
- Migrate the existing mixer channel rendering. Concretely: every place in `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/` and the mixer view that hand-rolls a `VBox` with `.mixer-channel` becomes `new MixerChannelStrip()` with property bindings to the mixer model. Audit callers via `Grep -rn "mixer-channel"` and migrate each.
- Remove the legacy `.mixer-channel`, `.mixer-channel-fader-thumb` (and any `purple` / `circular` fader styles) from `styles.css`. Leave an alias rule deprecating `.mixer-channel` → `.dawg-mixer-channel-strip` for one cycle, same convention as story 264's button aliases.
- Define `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/controls/mixer-channel-strip.css` with the styling rules above and the size-variant selectors `.mixer-channel-strip.density-compact` (72 px) and `.density-comfortable` (88 px).
- Tests:
  - `MixerChannelStripLayoutTest`: instantiate, snapshot layout bounds in Compact (72 ± 1 px) and Comfortable (88 ± 1 px).
  - `MixerChannelStripStateTest`: mirror story 270's M/S/R state tests at the channel-strip level.
  - `MixerChannelStripChannelTypeTest`: with `channelType = MASTER`, assert M/S/R toggles are *not* present in the rendered scene graph (only fader, meter, name).
  - `MixerChannelStripInsertActivationTest`: simulate a click on an insert slot row, assert an `InsertSelectedEvent` is **fired through the scene graph** (verify via `addEventFilter(InsertSelectedEvent.INSERT_SELECTED, …)` on a parent node) and the inspector receives it via story 272's handler.
  - `MixerChannelStripDisposeTest`: swap skin via `setSkin(null)`; assert listeners are unregistered from the channel-strip's properties.
  - `MixerChannelStripFaderBindingTest`: set `faderDbProperty = -6`, assert the embedded `Fader.valueProperty()` is `-6`. Inversely, drag the fader's cap and assert `faderDbProperty()` updates.

## Non-Goals

- VCA group assignment UI (story 153) — strips ignore VCA membership for this story.
- Per-send pre/post-fader toggle visual polish (story 154) — the toggle exists per slot but its visual is the default `dawg-button` style.
- Mid/Side wrapper toggle on the strip (story 157) — not part of §5.4.
- Implementing the inserts/sends *list* models (this is wiring; the model already exists). Slot adapter records are written if missing.
- Migrating the channel-strip *layout* to GpuCanvas — out of scope. Each child control may use a `Canvas` internally per its own story.

## Technical Notes

- The strip exposes a strict `Property<T>`-driven API; consumers bind from the mixer model and never call into the skin. This keeps the §2.5 "themability is a stylesheet swap, not a rewrite" promise.
- The "I/O label" (`I In 1+2` / `O Master`) is small mono per §3.2 — bind to the existing routing model. If routing is not yet selectable for buses, leave the bus's input label blank.
- Mixer-wide CSS classes (`.mixer-bus-divider`, etc.) are part of the parent panel, not this control.
- After this story, the mixer view is composed entirely of `MixerChannelStrip` controls — themability flows for free.
- Reference: UI Design Book §2.5, §5.4, §7.5 (multi-coloured-M/S/R veto — implicit AC).
