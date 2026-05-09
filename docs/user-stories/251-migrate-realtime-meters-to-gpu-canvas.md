---
title: "Migrate Real-Time Level / Loudness / Input Meters to GpuCanvas"
labels: ["enhancement", "ui", "metering", "performance", "gpu"]
---

# Migrate Real-Time Level / Loudness / Input Meters to GpuCanvas

## Motivation

The metering displays in `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/display/` each maintain their own raw `javafx.scene.canvas.Canvas` field, ad-hoc `widthProperty().addListener(... -> render())` resize wiring, and bespoke `AnimationTimer` (or `MeterAnimator`) per-frame loop:

- `LevelMeterDisplay.java` (peak / RMS bar with hold ballistics)
- `LoudnessDisplay.java` (LUFS integrated / short-term / momentary)
- `InputMeterStrip.java` (armed-track input column with green / yellow / red LEDs and clip latch — its own `AnimationTimer`)
- `MiniClipIndicator.java` (per-track clip LED in the arrangement view)

The `daw-fx` module (story 250) already provides this exact pattern as a reusable substrate: `GpuCanvas` owns the size-binding, the `AnimationTimer`, the optional clear-colour fill, and a single per-frame `GpuRenderer` callback that receives a `GpuRenderContext`. Continuing to hand-roll the substrate in every meter is duplication that drifts — `LoudnessDisplay`'s resize listener already differs subtly from `LevelMeterDisplay`'s.

## Goals

- Migrate `LevelMeterDisplay`, `LoudnessDisplay`, `InputMeterStrip`, and `MiniClipIndicator` so each composes a `GpuCanvas` instead of holding a raw `Canvas` + listeners + timer. The existing draw routine becomes the body of a `GpuRenderer`.
- Drive the per-frame ballistics (peak hold decay, RMS smoothing, integrated LUFS averaging) from `GpuRenderContext.deltaSeconds()` rather than `System.nanoTime()` deltas captured in each display. The `MeterAnimator` ballistic class can stay, but its tick is now the GpuCanvas frame.
- Set `GpuCanvas.animated(true)` only while the meter is mounted in a `Scene` (GpuCanvas already gates the timer on scene attachment) and call `dispose()` from the parent view's tear-down so meters never keep a hidden timer running.
- Use `GpuCanvas.setClearColor(...)` for the per-meter background fill (`#0d0d1a` for level / loudness, `#1a1a2e` etc. for the others) so each renderer stops issuing a redundant background `fillRect`.
- Behaviour-preserving refactor only: each meter must produce visually identical output for a given input snapshot — same gradient stops, same scale tick positions, same clip-LED behaviour. No DSP changes (LUFS / peak / RMS calculation lives in `daw-core` analysis classes and is not touched).
- Tests:
  - Headless test for each meter: mount in a `Scene`, push a known level snapshot, force a frame, capture the canvas image (use `WritableImage` snapshot per the JavaFX-headless conventions in story 208 / project memory) and assert key pixels match within tolerance.
  - Test confirms the animation ballistic (peak-hold decay) advances over `delta` seconds equivalently to the pre-migration implementation, parameterised by `MeterAnimator`.
  - Test confirms `MiniClipIndicator` lights when a level snapshot above 0 dBFS arrives and clears on reset, regardless of whether the indicator is currently visible (the timer is gated by `Scene` attachment, not visibility).

## Non-Goals

- Replacing `MeterAnimator` ballistics or changing the dB range / hold time defaults.
- Adding new metering modes (true-peak limiter integration is story 162 / 211; LUFS short-term / momentary curves are story 166).
- Migrating non-real-time displays (`WaveformDisplay`, `TunerDisplay`, `SpectrumDisplay`, `CorrelationDisplay`) — those are tracked in stories 252 – 253.
- Changing the on-screen scale labels or the dB legend layout.

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/display/LevelMeterDisplay.java`, `LoudnessDisplay.java`, `InputMeterStrip.java`, `MiniClipIndicator.java`. The `MeterAnimator` helper stays as-is.
- Where a meter currently exposes `update(LevelData, long deltaNanos)`, swap to `update(LevelData)` and let the `GpuRenderer` pull the latest snapshot + accumulate `deltaSeconds()` itself; callers stop computing `nanoTime()` deltas.
- `InputMeterStrip` already runs its own `AnimationTimer` polling `InputLevelMonitor.snapshot()`; that polling moves into the `GpuRenderer` so there is one timer per strip rather than two.
- Story 250 must land first so the `daw-fx` dependency is wired up in `daw-app/pom.xml` and the rendering-substrate API is stable.
- Reference original stories: **014 — LUFS Loudness Metering**, **137 — Input Gain Staging with Clip Indicators**, **166 — LUFS Short-Term and Momentary Histories**.
