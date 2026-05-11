---
title: "Fader Control + Skin with Integrated Meter Column and Wide Rectangular Cap"
labels: ["enhancement", "ui", "ui-overhaul", "phase-2", "mixer", "controls"]
---

# Fader Control + Skin with Integrated Meter Column and Wide Rectangular Cap

## Motivation

Phase 2 of the UI Design Book §6 migration roadmap. Builds on: #260, #261, #263, #267 (LevelMeter), #268 (Knob — file layout pattern).

UI Design Book §5.4 ("Mixer channel strip") describes a fader column visually paired with its meter — they live side by side and share the channel-strip footprint. Today the mixer channel strip uses JavaFX's default vertical `Slider` with a *purple circular* thumb (`styles.css:475–500` neighborhood), which clashes with every professional DAW convention. Every console-grade fader uses a **wide rectangular cap** (60–80 % of the slider width, ~12 px tall) for a reason — you can read the position from across a studio. The current circular thumb is a holdover from the JavaFX default and is one of the more visible "feels like a website, not a tool" cues.

UI Design Book §5.4's ASCII for the channel strip column shows the fader and meter as visually paired:

```
│  ║│   ▁  │    fader column │ meter column
│  ║│   ▂  │
│  ▓│   ▆  │    fader cap = surface-3, filled accent at touch
```

UI Design Book §5.4 also explicitly says: "the fader thumb is currently purple and circular; replace with a **wide rectangular cap** (the standard for DAW faders) that is `-surface-3` with a 1 px `-accent` line across the centre."

This story creates a `Fader` `Control + Skin` that:
1. Renders the wide rectangular cap with the §5.4 styling.
2. **Embeds** a `LevelMeter` (story 267) to its right at a fixed gap, exposing the meter's properties through a delegating API so consumers bind once.
3. Provides log-curve / linear travel mappings (dB faders have a non-linear travel curve; pan faders are linear).
4. Provides keyboard parity per §2.8.
5. Supports a "touch" pseudo-class that lights the cap's centre line in `-accent` while the user drags.

## Goals

- Create `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/controls/Fader.java` as a `javafx.scene.control.Control` with:
  - `DoubleProperty valueProperty()` — current value in user units (dB, normalised 0–1 for sends, etc.).
  - `DoubleProperty minProperty()`, `maxProperty()`, `defaultValueProperty()`.
  - `ObjectProperty<TravelCurve> curveProperty()` — `LINEAR`, `LOG_DB` (with the standard 0 dB = ~75 % travel curve used by professional mixers), `LOG_GAIN`. (JavaFX has no `EnumProperty`; use `SimpleObjectProperty<TravelCurve>`.)
  - Integrated meter accessor: `LevelMeter getMeter()` returns the embedded meter (lazy-created); consumers set `fader.getMeter().peakDbProperty().bind(audioEngine.peakOf(channelId))`.
  - `BooleanProperty showMeterProperty()` — defaults true; can be disabled for faders that don't represent an audio channel (e.g., a send level fader with no integrated meter).
  - `BooleanProperty animatedProperty()` — Reduce Motion (story 279).
  - Standard size variants via style class (`.fader.size-mixer`, `.size-inspector`, `.size-performance` for the giant Performance Stage fader in story 280).
  - Override `getUserAgentStylesheet()` to return `Fader.class.getResource("fader.css").toExternalForm()` so the control is self-contained.
  - Provide a fluent `Builder` (`Fader.create().min(-96).max(12).defaultValue(0).curve(LOG_DB).showMeter(true).size("mixer").build()`); builder is *one* construction path, not the only one.
- Create `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/controls/skin/FaderSkin.java` extending `SkinBase<Fader>` that overrides `compute{Min,Pref,Max}{Width,Height}` per size variant. In `layoutChildren(x, y, w, h)`, the skin derives column width, cap width, cap height, tick spacing, and meter gap from the assigned `w` and `h` — no fixed pixel offsets keyed to the CSS default. Listeners on control properties are registered in the constructor and removed in `dispose()`. Drawing per §5.4:
  - Renders the fader column as a vertical track (`-surface-2`, 4 px wide) with a 0 dB tick mark.
  - Renders the cap as a wide rectangle: 70 % of the column's width × 12 px tall, fill `-surface-3`, with a 1 px `-accent` horizontal centreline. The cap snaps to a centred position relative to the column.
  - When dragging (`:dragging` pseudo-class), the centreline turns from `-accent` to a brighter overlay (`-accent` at 100 % alpha).
  - Embeds the `LevelMeter` (story 267) at a fixed 4 px gap to the right of the column, sized `.size-channel` by default; size variants on the fader propagate to size variants on the meter (`fader.size-mixer` → `meter.size-channel`; `fader.size-performance` → `meter.size-performance`).
- Mouse interaction:
  - Click + drag vertically to scrub.
  - Click outside the cap snaps to that position (Pro Tools style).
  - Double-click resets to `defaultValue` (typically 0 dB = unity gain).
  - Scroll wheel adjusts by `(max - min) / 100` per notch; `Shift + scroll` for fine.
- Keyboard interaction (focused fader):
  - `↑` / `↓` adjust by `(max - min) / 100`.
  - `Shift + ↑` / `Shift + ↓`: 10× finer.
  - `PgUp` / `PgDn`: 10× coarser.
  - `Home` / `End`: max / min (note: max is "up" for a fader).
  - `0`: reset to default.
  - `Enter`: open a small text-entry popover.
- Define `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/controls/fader.css` with:
  - `-fader-track-color: -surface-2`
  - `-fader-cap-color: -surface-3`
  - `-fader-cap-line-color: -accent`
  - `-fader-zero-tick-color: -line-strong`
  - Size variants applying `-fx-pref-width` / `-fx-pref-height` from the §3.3 grid.
- Tests:
  - `FaderValueClampTest` (mirrors `KnobValueClampTest`).
  - `FaderCurveTest`: with `curveProperty = LOG_DB`, set value to 0 dB; assert cap position is at 75 % of column height (within 1 px tolerance). Set value to `-∞`; assert cap is at the bottom. Set value to `+6 dB` (max); assert cap is at the top.
  - `FaderKeyboardTest`: mirrors `KnobKeyboardTest` for fader keys.
  - `FaderMeterIntegrationTest`: bind `fader.getMeter().peakDbProperty()` to a programmable value source, drive `peakDb = -6`; assert the embedded meter reaches the `-meter-hi` band.
  - `FaderDragPseudoClassTest`: simulate a press-drag-release; assert `:dragging` is applied during the drag and removed on release; assert the cap centre line tints to the dragging overlay during the drag.
  - `FaderResizeTest`: place the fader inside containers of two different heights, assert cap snaps proportionally (not at a literal pixel offset).
  - `FaderDisposeTest`: swap the skin via `setSkin(null)`; assert listeners are unregistered from the control's properties.
  - `FaderBuilderTest`: build via the fluent builder and verify resulting properties match.

## Non-Goals

- Building a "motorised fader" effect that animates between positions on automation playback — defer; the value just snaps. (Animation here is decoration unless real automation is driving it.)
- Implementing fader groups / VCAs — that is story 153.
- Migrating every existing `Slider` to `Fader`. This story creates the control; mixer channel strip migration is story 271; track-strip volume (if any) and timeline scrubbers stay as `Slider`.
- The meter embedded in the fader does not own its DSP data — consumers wire the peak via property binding (this story is purely the *display* control).

## Technical Notes

- The "integrated meter" is a `LevelMeter` child of the `FaderSkin`. The two share the channel-strip footprint but are logically independent: a `Fader` with `showMeterProperty(false)` simply lays out without the meter column.
- For `LOG_DB`, use the conventional mapping where `0 dB` sits at 75 % of travel, `-∞` at 0 %, `+12 dB` at 100 %. Document the exact curve coefficient inline so future contributors can match it across surfaces (export, automation render, etc.).
- This is the last of the four "instrument-grade" Phase 2 controls (LevelMeter, Knob, Fader, plus TrackStrip in story 270). After this story, the building blocks for the channel strip (story 271) and inspector (story 272) are all ready.
- Reference: UI Design Book §2.5, §2.8, §5.4.
