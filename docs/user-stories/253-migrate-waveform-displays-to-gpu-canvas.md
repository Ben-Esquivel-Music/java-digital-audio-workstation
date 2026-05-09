---
title: "Migrate WaveformDisplay and Browser Sample Thumbnails to GpuCanvas"
labels: ["enhancement", "ui", "waveform", "performance", "gpu"]
---

# Migrate WaveformDisplay and Browser Sample Thumbnails to GpuCanvas

## Motivation

`daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/display/WaveformDisplay.java` is the shared peak / RMS waveform thumbnail used by the browser sample preview (story 027), the audio import dialog, and the album-assembly view. It currently:

- Holds a raw `javafx.scene.canvas.Canvas` and re-binds its size in the constructor.
- Triggers a full re-render on every `setCursorPosition`, `setPeakColor`, `setRmsColor`, `setBackgroundColor`, and resize.
- Has no animated state — but is still drawn synchronously in property setters, which means a sample-preview cursor that updates 50× / second issues 50 hand-driven `render()` calls each frame.

The `daw-fx` substrate (story 250) absorbs both the resize-redraw and the on-demand redraw plumbing: `GpuCanvas.requestRender()` collapses many calls in a frame into a single redraw on the next pulse, and `setAnimated(true)` lets the playback cursor advance smoothly off the FX `AnimationTimer` rather than off whichever timer the caller chose.

## Goals

- Migrate `WaveformDisplay` to compose a `GpuCanvas`. The renderer is the body of the existing `render()` method; the property setters call `gpuCanvas.requestRender()` instead of synchronously redrawing.
- When a cursor is being animated (e.g. while the browser sample preview is playing back), set `gpuCanvas.setAnimated(true)` so the cursor advance is driven by the GpuCanvas timer; reset to `false` on stop. This gives a single, vsync-aligned animation loop instead of the current ad-hoc property updates.
- Drive the cursor advance from `GpuRenderContext.deltaSeconds()` so the cursor speed is correct regardless of frame rate.
- Use `GpuCanvas.setClearColor(backgroundColor)` so the renderer no longer issues the per-frame background `fillRect`. Setter for `backgroundColor` updates the GpuCanvas property directly and triggers a `requestRender()` automatically (GpuCanvas already listens for clear-colour changes).
- Behaviour-preserving refactor: same peak / RMS gradient stops, same cursor colour and width, same center-line style. Pixels for a given `WaveformData` snapshot must match within tolerance.
- Audit the call sites in `BrowserPanel.java`, `AudioImportController.java`, and `AlbumAssemblyView.java` to confirm they call `dispose()` on the waveform display (or its hosting controller does so) when the panel / dialog closes, to stop the timer cleanly.
- Tests:
  - Headless test: render a deterministic `WaveformData` (synthesised peak / RMS arrays) at three different cursor positions, snapshot the canvas at each, assert pixel parity within tolerance against a pre-migration baseline.
  - Test confirms that calling `setCursorPosition` ten times within a single FX frame results in exactly one renderer invocation (collapse via `requestRender`).
  - Test confirms `setAnimated(true)` then `setAnimated(false)` advances the frame counter while running and stops it after.

## Non-Goals

- Replacing the `WaveformData` SDK record or the peak / RMS computation pipeline (those live in `daw-core` and `daw-sdk`).
- Loading or caching waveform data — the rendered-track / waveform cache is story 206.
- Adding zoom / scroll inside `WaveformDisplay` (the arrangement-canvas zoom is a separate scene object, story 021 / 095).
- Migrating `MiniClipIndicator` — that is in story 251 with the meter group.

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/display/WaveformDisplay.java` (substrate swap), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/BrowserPanel.java`, `AudioImportController.java`, `AlbumAssemblyView.java` (verify `dispose()` is called on tear-down).
- `requestRender()` on GpuCanvas already coalesces multiple calls in a single FX pulse — drop any boolean `pendingRender` flag this class might have grown.
- `GpuCanvas.requestRender()` runs synchronously on the FX thread; its `deltaSeconds()` is documented as `0.0` for one-off renders, so the cursor advance code must check whether it is being called from the timer (`deltaSeconds() > 0`) before integrating.
- Story 250 must land first to add the `daw-fx` dependency to `daw-app`.
- Reference original stories: **021 — Waveform Zoom and Minimap Navigation**, **027 — Browser Panel Sample Preview**.
