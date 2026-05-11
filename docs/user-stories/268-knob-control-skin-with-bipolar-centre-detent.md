---
title: "Knob Control + Skin with Bipolar Centre Detent and Keyboard Parity"
labels: ["enhancement", "ui", "ui-overhaul", "phase-2", "controls", "accessibility"]
---

# Knob Control + Skin with Bipolar Centre Detent and Keyboard Parity

## Motivation

Phase 2 of the UI Design Book §6 migration roadmap. Builds on: #260, #261, #263, #267 (file layout template).

UI Design Book §5.8 ("Knob") and §2.8 ("Keyboard parity") together specify a control that today does not exist as a proper widget — every place that needs a rotary parameter (pan, sends, plugin parameters) hand-rolls something with a `Slider` or a custom `Region`. The result is inconsistent visuals, no keyboard accessibility on most of them, and no theming hook.

UI Design Book §5.8 specifies:
- Circular dial, **28 / 36 / 48 px** diameter (size variants).
- Indicator line from centre.
- Travel arc thin (1.5 px) along the outer edge, drawn from `-text-mute` (full travel) and overlaid in `-accent` from min to current.
- States: default, hover (line brightens to `-text-hi`), focused (focus ring), dragging (line goes `-accent`), bipolar (centre detent at 12 o'clock for pan).
- Drawing rules: dial is `-surface-2` filled circle, 1 px `-line-strong` border.

UI Design Book §2.8 requires keyboard parity: knobs accept `↑/↓` and `Shift+↑/↓` (fine), `Home` / `End` (min / max), `0` (default value). Mouse drag uses vertical motion (drag up to increase). Modifier keys: `Shift` slows the drag rate (10×), `Ctrl/Cmd` returns to default value on click.

This story creates the `Knob` `Control + Skin` once; subsequent stories (271 mixer channel pan and sends, plugin parameter panels in the inspector via story 272) consume it.

## Goals

- Create `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/controls/Knob.java` as a `javafx.scene.control.Control` with:
  - `DoubleProperty valueProperty()` — current value, in the configured min/max range.
  - `DoubleProperty minProperty()`, `DoubleProperty maxProperty()`, `DoubleProperty defaultValueProperty()`.
  - `BooleanProperty bipolarProperty()` — when true, the travel arc starts at 12 o'clock (centre detent for pan).
  - `StringProperty unitProperty()` — units displayed below the knob ("dB", "Hz", "%", "L/R", "");
  - `ObjectProperty<Function<Double,String>> valueFormatterProperty()` — formatter for the value-entry popover and on-knob readout (default: 1-decimal numeric). **Not** a `StyleableProperty` — `CssMetaData` does not support `Function` types; this is a plain `ObjectProperty`, configured from Java, not CSS.
  - Size variants via style class (`.knob.size-28`, `.size-36`, `.size-48`).
- Create `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/controls/skin/KnobSkin.java` extending `SkinBase<Knob>` that draws on a `Canvas` per §5.8. The skin overrides `compute{Min,Pref,Max}{Width,Height}` per size variant (e.g. `.size-28` → pref 28 × 28, min 16 × 16, max 64 × 64). In `layoutChildren(x, y, w, h)`, the skin computes `size = Math.min(w, h)` and derives **every** internal dimension (border stroke, arc radius, indicator length, focus-ring offset, font size) from `size`. This keeps the knob circular and proportioned at any size a parent assigns, not only at the CSS default. The skin registers listeners on the control's properties in its constructor and removes them in `dispose()`. Drawing rules per §5.8:
  - Filled circle in `-surface-2`, 1 px `-line-strong` border.
  - Travel arc (1.5 px wide, outer edge) — full `-text-mute` underlay; `-accent` overlay from min to current (or from centre to current when bipolar).
  - Indicator line (2 px `-text` line from centre to dial edge).
  - Focus ring drawn outside the dial when `:focused` (uses `-focus-ring`).
- Mouse interaction:
  - Click + drag vertically: drag up increases, drag down decreases. Drag rate: full range over 200 px of vertical travel.
  - `Shift + drag`: 10× slower (fine adjust).
  - `Ctrl/Cmd + click`: reset to `defaultValueProperty()`. Double-click also resets.
  - Scroll wheel: one notch per `(max - min) / 100` change; `Shift + scroll` for fine.
- Keyboard interaction (focused knob):
  - `↑` / `Right` increases by `(max - min) / 100`.
  - `↓` / `Left` decreases by the same.
  - `Shift + ↑` / `Shift + ↓`: 10× finer.
  - `PgUp` / `PgDn`: 10× coarser.
  - `Home` / `End`: min / max.
  - `0` (zero key): reset to default.
  - `Enter`: open a small text-entry popover where the user can type a value directly (the same pattern used by professional DAW knobs).
- Reduced motion (story 279): the centre-detent click animation (60 ms ease-in spring) is suppressed when the global Reduce Motion flag is on.
- Define `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/controls/knob.css` with the three size variants and the styleable token consumption (`-knob-dial-color: -surface-2;`, `-knob-border-color: -line-strong;`, `-knob-arc-color: -accent;`, `-knob-track-color: -text-mute;`, `-knob-indicator-color: -text;`).
- Override `Knob#getUserAgentStylesheet()` to return `Knob.class.getResource("knob.css").toExternalForm()` so the control renders correctly when consumed outside the main app stylesheet (e.g. inside a plugin GUI). Token overrides from the parent stylesheet still cascade via lookup-colour resolution.
- Provide a fluent `Builder` (`Knob.create().min(-1).max(1).defaultValue(0).bipolar(true).unit("L/R").size(28).build()`). Builder methods return `this`; `build()` is terminal. Direct constructors and setters remain available — the builder is *one* construction path, not the only one. Skill §5 names knobs explicitly as a builder-worthy widget.
- Tests:
  - `KnobValueClampTest`: assert that setting `value < min` clamps to `min` and `value > max` clamps to `max`; assert `defaultValue` outside `[min,max]` raises `IllegalArgumentException` at construction.
  - `KnobKeyboardTest`: focus a knob, send synthetic `KeyEvent` for each key listed above, assert the value changes by the expected amount.
  - `KnobBipolarTest`: with `bipolar = true`, `defaultValue = 0`, set `value = -0.5`; assert the travel arc skin renders from centre counter-clockwise (verify via a marker pixel test on the canvas).
  - `KnobReducedMotionTest`: with `animated = false`, snap to default; assert no transition timeline is created.
  - `KnobAccessibilityTest`: assert the control reports `AccessibleRole.SLIDER` (closest match) and emits `Accessible.VALUE` updates so screen readers narrate value changes.
  - `KnobResizeTest`: place a `Knob.size-28` inside a 60 × 40 cell, force layout, assert the canvas remains square (`Math.min(w, h)`) and the indicator/arc geometry scales proportionally — no fixed pixel offsets at the default size.
  - `KnobDisposeTest`: instantiate, attach to scene, swap skin via `setSkin(null)`, assert all listeners registered by the original skin are removed (verified by mutating control properties and confirming no skin-side reaction).
  - `KnobBuilderTest`: build a knob via the fluent builder, verify resulting properties match the chain.

## Non-Goals

- Implementing a value-entry popover styling — the popover uses the dialog/popover styles from story 276; this story stops at "Enter opens the entry control".
- Adding a graphical "modulation glow" ring around the dial for plugin-modulated parameters — defer to a follow-on plugin-feature story.
- Replacing every existing `Slider` with a `Knob` mechanically — pan/sends/plugin parameters yes; track-strip volume and timeline scrubbers stay as faders/sliders.
- Building a `KnobBank` container for multi-knob layouts (e.g. an EQ band's three knobs) — that is a presentational concern, addressed inside the inspector drawer story 272.

## Technical Notes

- The `Knob` is `:focus-traversable = true` by default — without this, the §2.8 keyboard parity requirement is unverifiable. Add `setFocusTraversable(true)` in the constructor.
- The drag rate "full range per 200 px" is a usability decision; document it and expose as a styleable property `-knob-drag-sensitivity` so plugin developers can override per knob (a noisy plugin like a phase rotator may want 400 px).
- This control is consumed by story 271 (mixer channel pan and sends), and any plugin parameter panel migrated into the inspector by story 272.
- Reference: UI Design Book §2.5, §2.8, §5.8.
