---
title: "Migrate Real-Time Spectrum / Correlation / Tuner Analyzers to GpuCanvas"
labels: ["enhancement", "ui", "analysis", "performance", "gpu"]
---

# Migrate Real-Time Spectrum / Correlation / Tuner Analyzers to GpuCanvas

## Motivation

The real-time analyzer scene objects in `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/display/` each render an FFT- or sample-driven trace once per frame on a hand-rolled `javafx.scene.canvas.Canvas` with their own resize listener and (in some cases) their own animation pulse:

- `SpectrumDisplay.java` (logarithmic FFT bars with peak hold, average trace, pre/post-EQ overlay, stereo split — story 023 / 081)
- `CorrelationDisplay.java` (goniometer / vectorscope dot cloud with persistence / phosphor decay — story 028)
- `TunerDisplay.java` (chromatic tuner needle and cents readout — story 082)

These are the three displays where the GPU pipeline most directly pays for itself: every frame redraws gradients, alpha-blended traces, and persistence trails — exactly the workload Prism's hardware backend (Direct3D 11 on Windows, Metal on macOS, OpenGL ES2 on Linux) exists to absorb. The `daw-fx` substrate (story 250) gives them a uniform, well-tested host.

## Goals

- Migrate `SpectrumDisplay`, `CorrelationDisplay`, and `TunerDisplay` so each composes a `GpuCanvas` whose `GpuRenderer` is the body of the existing `render()` method. The renderer reads `width()` / `height()` / `frameNumber()` / `deltaSeconds()` from `GpuRenderContext`.
- Drive the spectrum's smoothing exponent, the goniometer phosphor decay, and the tuner needle ballistic from `GpuRenderContext.deltaSeconds()` — not from a hand-tracked `nanoTime()` delta. This makes the visual decay correct under variable frame pacing and fixes any drift the current code has when the FX thread misses a vsync.
- Use `GpuCanvas.setClearColor(...)` for the per-analyzer background, replacing the per-frame background `fillRect` each renderer currently issues. For displays that intentionally do *not* clear (e.g. `CorrelationDisplay`'s phosphor trail, which depends on previous-frame pixels), keep the alpha-fade fill inside the renderer and leave the clear colour `null` — GpuCanvas honours that.
- Set `setAnimated(true)` only when the analyzer is mounted; rely on GpuCanvas's scene-attachment gating to stop the timer when the host panel is detached or hidden via `VisualizationPanelController`.
- Behaviour-preserving refactor: smoothed bins, peak hold, average trace, and persistence behaviour must match the pre-migration output for a given deterministic FFT input. No DSP, no FFT-window or smoothing-coefficient changes.
- Tests:
  - Headless test for each analyzer: feed a deterministic spectrum / lissajous / pitch snapshot, force a frame, snapshot the canvas, assert key pixels (peak bin position, lissajous extremum, needle x-coordinate) match the pre-migration golden image within tolerance.
  - Test confirms the goniometer phosphor decay halves alpha across one frame at the configured decay constant when `deltaSeconds() ≈ 1/60`.
  - Test confirms the spectrum's resize handling no longer requires an explicit `requestRender()` from outside — GpuCanvas's size listener already invokes one.

## Non-Goals

- New analyzer features — adding a waterfall view, sonogram, or A-weighting to the spectrum analyzer is outside this story.
- Changing FFT size, window function, smoothing coefficients, or peak-hold timing constants.
- Migrating the *windowed* analyzer hosts (`SpectrumDisplayWindow`, `CorrelationDisplayWindow`, `TunerDisplayWindow`) — they only own a `Stage` plus the underlying display; once the display migrates the window picks up the GpuCanvas substrate transitively.
- Sharing FFT computation across multiple displays (orthogonal performance work).

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/display/SpectrumDisplay.java`, `CorrelationDisplay.java`, `TunerDisplay.java`. The window wrappers (`*Window.java`) need no changes beyond verifying they call `dispose()` on close.
- `CorrelationDisplay`'s persistence trail must keep its current pixel-feedback loop — set `clearColor` to `null` and let the renderer issue the alpha-fade fill itself. Document that constraint in a one-line comment on the renderer field.
- `SpectrumDisplay`'s `LinearGradient`-based bar fill is preserved: GpuCanvas does not change paint semantics, only host substrate.
- Story 250 must land first to add the `daw-fx` dependency to `daw-app`.
- Reference original stories: **023 — Spectrum Analyzer**, **028 — Stereo Correlation Meter and Goniometer**, **082 — Chromatic Tuner Built-In Plugin**.
