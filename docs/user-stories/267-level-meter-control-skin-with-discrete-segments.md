---
title: "LevelMeter Control + Skin with Discrete LED-Style Segments"
labels: ["enhancement", "ui", "ui-overhaul", "phase-2", "metering", "controls", "canvas"]
---

# LevelMeter Control + Skin with Discrete LED-Style Segments

## Motivation

Phase 2 of the UI Design Book §6 migration roadmap. Builds on: #260 (`-meter-low`/`-meter-mid`/`-meter-hi`/`-meter-clip` tokens), #261 (grid), #263 (no universal drop-shadow).

UI Design Book §5.7 ("Level meter") and §1.1 (palette overload) describe what's wrong with the current meter and what the replacement looks like. Today's `.level-meter-fill-green`, `.level-meter-fill-orange`, and `.level-meter-fill-red` (`styles.css:565–575`) are gradient `Region` fills layered behind a mask. Three issues:

1. **Style names encode hue, not data semantics.** The state "below -18 dBFS" is encoded as "the green fill is showing" rather than as `meter-low`. Themes can't change the meter colour without also renaming the style class.
2. **Gradient fills don't look like analogue LEDs.** Professional level meters in the analogue world are *discrete* segments — each LED lights when the signal crosses a threshold, and the spacing is information (a 1-dB-per-LED meter near 0 dBFS, sparser below). UI Design Book §5.7 mandates "discrete LED-style segments — pixel-aligned blocks every 1 dB, drawn on Canvas".
3. **No `Control + Skin`.** Per UI Design Book §2.5, any widget with bound state is a `Control` + `Skin` + `StyleableProperty`. Today the meter is a `Region` with manually-bound width. Themes can't restyle without touching draw code.

UI Design Book §2.6 also calls for Canvas drawing on metering surfaces ("Waveforms, spectrum, oscilloscope, arrangement timeline, automation lanes, meters — Canvas + AnimationTimer (skill §6). Stop animation when the node leaves the scene."). Per the user's memory ([daw-fx first consumer is SoundWaveTelemetry](memory:project_daw_fx_first_consumer)), the `daw-fx` renderer contract is FFM/MemorySegment BGRA-pre. This story's meter uses a plain `Canvas` (not `daw-fx`) — the meter is one of the lighter Canvas surfaces, and adding a `daw-fx` dependency here would overshoot. (The level-meter-on-GpuCanvas migration is story 251; that story still applies after this one because it changes the *backend* of the same Skin.)

## Goals

- Create `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/controls/LevelMeter.java` as a `javafx.scene.control.Control` with the following observable properties:
  - `DoubleProperty peakDbProperty()` — current peak in dBFS (typically negative).
  - `DoubleProperty rmsDbProperty()` — current RMS (rendered as a darker secondary line per §5.7's "Peak number readout" note).
  - `DoubleProperty peakHoldDbProperty()` — peak-hold value; cleared by the control internally on a 2 s timer per §5.7.
  - `ObjectProperty<Orientation> orientationProperty()` — vertical or horizontal. (JavaFX has no `EnumProperty`; use `SimpleObjectProperty<Orientation>` initialised to `Orientation.VERTICAL`.)
  - `IntegerProperty channelCountProperty()` — typically 2 (stereo), supports up to 8 for surround.
  - `BooleanProperty animatedProperty()` — wired to the global Reduce Motion setting (story 279).
  - Standard JavaFX `StyleableProperty` bindings for `-meter-low`, `-meter-mid`, `-meter-hi`, `-meter-clip`, `-meter-background` (resolves to `-surface-2`), `-meter-segment-gap` (default 1 px), `-meter-segment-height` (default 2 px = one LED per dB).
  - Override `getUserAgentStylesheet()` to return `LevelMeter.class.getResource("level-meter.css").toExternalForm()` so the control renders correctly even when consumed from a context (e.g. a plugin GUI window) that does not load the main app `styles.css`. Token overrides from the app stylesheet still cascade on top via lookup-colour resolution.
  - Provide a fluent `Builder` (`LevelMeter.create().channels(2).orientation(VERTICAL).size("inline").build()`) alongside the constructor and setters. Builder methods return `this`; `build()` is terminal. The builder is *one* construction path — direct constructors and property setters remain available.
- Create `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/controls/skin/LevelMeterSkin.java` extending `SkinBase<LevelMeter>` that renders to an internal `Canvas` via `AnimationTimer`. The skin overrides `computeMinWidth/Height`, `computePrefWidth/Height`, `computeMaxWidth/Height` per size variant (e.g. inline = pref 4 × 16 px, min 2 × 8 px, max `Double.MAX_VALUE`) so parents can lay out the meter sensibly. In `layoutChildren(x, y, w, h)`, derive segment count, segment height, and font from the long-axis size — never hard-code pixel offsets against the default size. Drawing rules from §5.7:
  - Below `-18 dBFS`: segments filled with `-meter-low`.
  - `-18` to `-6`: `-meter-mid`.
  - `-6` to `0`: `-meter-hi`.
  - Above `0`: `-meter-clip` and the peak-hold pixel sticks for 2 s.
  - Background segments (unlit) use `-meter-background`.
  - No glow, no shadow (per §5.7 and §7.10).
- Lifecycle correctness: per the `javafx-application-design` skill, the `AnimationTimer` is started in `onSceneChanged` when `getSkinnable().getScene() != null` and stopped when the scene becomes null. This avoids the leaked-timer pattern documented in the user's [JavaFX headless test pitfalls](memory:feedback_javafx_headless_test_pitfalls) memory. The skin registers listeners on the control's properties in its constructor and **removes them in `dispose()`** so swapping the skin or detaching the control does not leak strong references.
- Define `level-meter.css` (loaded by default with `styles.css`) with the supported sizes from §5.7:
  - Inline (track strip): 4 px wide, 16 px tall — `.level-meter.size-inline`.
  - Mixer channel strip: 8 px wide, full-height — `.level-meter.size-channel`.
  - Master / transport: 12 px wide × 36 px tall — `.level-meter.size-master`.
  - Performance Stage: 24 px wide × 320 px tall with `-meter-tick-marks: true` — `.level-meter.size-performance` (used by story 280).
- Replace existing meter usages incrementally — *not all in this story*. Migrate:
  - The transport's master meter (in `main-view.fxml` status bar area).
  - The track strip's inline meter (`TrackStripController` if it has one wired today; otherwise add — UI Design Book §5.3 mockup shows `║▁▂▃▄▅▆ ─12.4dB`).
  - Leave mixer channel meters to story 271 (`MixerChannelStrip` is its own story).
- Remove the legacy `.level-meter-fill-green/orange/red` rules from `styles.css` once no remaining consumer references them. If any consumer still does, leave a deprecation comment and migrate in a follow-up.
- Tests:
  - `LevelMeterTest`: instantiate `LevelMeter`, set `peakDb = -12`, force a layout pass, assert the canvas has been painted by checking the topmost lit segment index (compute from the peak). Repeat for `-3` (should reach mid), `+1` (should clip), `-∞` (no lit segments).
  - `LevelMeterPeakHoldTest`: set `peakDb = +2`, hold for 1.5 s, assert peak-hold pixel still at the +2 row. Wait 2.5 s, assert peak-hold has fallen off.
  - `LevelMeterLifecycleTest`: instantiate, attach to a scene, remove from scene, GC, assert the underlying `AnimationTimer` has stopped (verify via a counter incremented per `handle()` call). Also assert that calling `setSkin(null)` runs `dispose()` and removes all registered property listeners.
  - `LevelMeterSkinThemeTest`: apply a stylesheet that re-tints `-meter-low: red;`; assert the lit segment colour changes after `applyCss()`.
  - `LevelMeterBuilderTest`: build a meter via the fluent builder and verify the resulting properties match the chain.

## Non-Goals

- Migrating mixer channel strip meters (deferred to story 271).
- Migrating all telemetry / spectrum analyser surfaces — those have their own GpuCanvas migration stories (250–254).
- Implementing K-weighted (BS.1770) meter ballistics — that is story 014 / 166's territory; this control is the *display*, not the DSP.
- Touch interaction on the meter (clicking to reset peak-hold) — defer to a follow-on; double-click clears peak-hold for now (one-line skin handler).
- Switching the meter to `daw-fx` GpuCanvas — story 251 already covers that and will replace this control's `Canvas` backend without changing the `Control + Skin` API.

## Technical Notes

- **Audio thread → FX thread relay.** Per the `javafx-application-design` skill §11, every scene-graph read or write must happen on the FX thread. The audio thread writes peak/RMS values **only** to an `AtomicLong` that encodes the `double` via `Double.doubleToLongBits` (avoids the per-update allocation of `AtomicReference<Double>`). The skin's `AnimationTimer.handle(...)` runs on the FX thread, reads the atomic, and propagates the value into `peakDbProperty().set(...)`. The control's `peakDbProperty()` setter is *only* called from the FX thread — never from the audio thread, never from `Platform.runLater` (per the project's real-time-safety rules, story 109).
- The 2 s peak-hold timer is JavaFX-side, not audio-thread side. Track via `System.nanoTime()` inside the AnimationTimer's `handle`.
- This control is the template for stories 268 (Knob), 269 (Fader), 270 (TrackStrip), and 271 (MixerChannelStrip) — establish the file layout (`controls/Foo.java`, `controls/skin/FooSkin.java`, `controls/foo.css`) and StyleableProperty boilerplate so subsequent stories follow the same pattern.
- **`module-info.java` updates.** This story introduces the `com.benesquivelmusic.daw.app.ui.controls` and `controls.skin` packages. Subsequent Phase 2 stories add `inspector`, `inspector.sections`, `views`, `theme`, `density`, `motion`, `dialogs`, `icons`, `design`, and `layout`. Every new package that is referenced from FXML (`fx:controller`, `@FXML` injection) must be added to `module-info.java` as `opens com.benesquivelmusic.daw.app.ui.<package> to javafx.fxml;`. Public API packages also need `exports`. Verify via the existing FXML loader smoke test; failures surface as `IllegalAccessException` at scene load.
- Reference: UI Design Book §2.5, §2.6, §5.7, §7.10.

## Implementation Note (2026-05-15)

The `LevelMeter` control, its `LevelMeterSkin`, `level-meter.css`, and the
full test suite are delivered. Two scoped deferrals, recorded here rather
than dropped silently:

- **Use-site meter migration deferred to stories 251 / 270.** The "Replace
  existing meter usages incrementally" bullets were written against the
  legacy `.level-meter-fill-*` `Region` model. That model no longer exists
  at the named sites: the transport master meter is `LevelMeterDisplay`, a
  `daw-fx` `GpuCanvas`-backed Region whose migration is **story 251's**
  explicit scope (and this story's Non-Goals already exclude the GpuCanvas
  backend swap); `TrackStripController` has **no meter today**, so adding
  one is net-new TrackStrip work that belongs with **story 270**. The dead
  `.level-meter-fill-{green,orange,red}` rules had zero Java/FXML consumers
  and were removed cleanly in `styles.css` — nothing is left dangling.
- **`module-info.java` note is not applicable.** `daw-app` is not a JPMS
  module (no `module-info.java`; the shade build excludes
  `module-info.class`); it runs on the classpath as the unnamed module, so
  FXML reflective access needs no `opens`/`exports`. The note remains
  relevant for any future module that *does* consume these packages.
