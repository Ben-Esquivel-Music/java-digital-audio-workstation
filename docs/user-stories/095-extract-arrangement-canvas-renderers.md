---
title: "Extract Clip and Waveform Rendering from ArrangementCanvas"
labels: ["enhancement", "ui", "arrangement-view"]
---

# Extract Clip and Waveform Rendering from ArrangementCanvas

## Motivation

`ArrangementCanvas` is 1,225 lines handling multiple rendering responsibilities: track lane backgrounds, audio clip waveform thumbnails, MIDI clip piano-roll previews, clip name labels, fade curve overlays, trim preview lines, playhead rendering, loop region highlighting, time selection overlay, rubber-band selection, and automation lane expansion. Each rendering responsibility involves its own coordinate calculations, color theming, and clipping logic.

The class has an `AutomationLaneRenderer` extracted as a separate component, proving the pattern works. However, the largest rendering responsibilities — clip waveform drawing, clip interaction overlays (fade handles, trim lines), and grid/ruler rendering — remain in the main class. This makes it difficult to modify one rendering concern without risk of breaking another, and the 1,225-line class is hard to review in pull requests.

## Goals

- Extract audio clip waveform thumbnail rendering into a `ClipWaveformRenderer` that takes a `GraphicsContext`, clip bounds, and audio data and draws the waveform
- Extract MIDI clip piano-roll preview rendering into a `ClipMidiPreviewRenderer` that draws the miniature note rectangles within a clip bounds
- Extract clip overlay rendering (name labels, fade curves, trim preview lines, selection highlights) into a `ClipOverlayRenderer`
- Extract grid and ruler rendering (bar/beat lines, time labels) into a `GridRenderer` if not already separate
- `ArrangementCanvas` delegates to these renderers during its `render()` method, passing the necessary drawing context and layout parameters
- Each renderer is stateless (or nearly so) and independently testable with a mock `GraphicsContext`
- No visible rendering changes — the arrangement view looks identical after refactoring
- `ArrangementCanvas` should drop below 600 lines after extraction

## Non-Goals

- Changing the rendering appearance or adding new visual features
- Optimizing rendering performance (e.g., caching, dirty-region tracking) — that is a separate concern
- Extracting mouse interaction handling (click, drag, hover) — the interaction controllers already exist
- Moving `ArrangementCanvas` to a different module
