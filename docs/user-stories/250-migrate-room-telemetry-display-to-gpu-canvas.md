---
title: "Migrate SoundWaveTelemetry RoomTelemetryDisplay to GpuCanvas (First daw-fx Consumer)"
labels: ["enhancement", "ui", "telemetry", "performance", "gpu"]
---

# Migrate SoundWaveTelemetry RoomTelemetryDisplay to GpuCanvas (First daw-fx Consumer)

## Motivation

The new `daw-fx` module ships `GpuCanvas`, a resizable `Region` that wraps a JavaFX `Canvas`, drives a pluggable `GpuRenderer` per frame via an internal `AnimationTimer`, and reports the active Prism backend (Direct3D 11 / Metal / OpenGL ES2 / Software) through `GpuPipeline.detect()`:

- `daw-fx/src/main/java/com/benesquivelmusic/daw/fx/GpuCanvas.java`
- `daw-fx/src/main/java/com/benesquivelmusic/daw/fx/GpuRenderer.java`
- `daw-fx/src/main/java/com/benesquivelmusic/daw/fx/GpuRenderContext.java`
- `daw-fx/src/main/java/com/benesquivelmusic/daw/fx/GpuPipeline.java`

```
$ grep -rn 'daw-fx\|GpuCanvas' daw-app/
(no matches)
```

`daw-app` does not yet depend on `daw-fx` and no scene object instantiates `GpuCanvas`. The animated isometric 3D telemetry visualisation in `RoomTelemetryDisplay` (sonar ripples, RT60 ambient glow, traveling energy particles, pulsing sources, animated mic aim indicators — story 066, story 085) is the canonical first consumer: it already drives a per-frame `updateAnimation(double)` loop and rebuilds the entire canvas every frame, so it is the case GpuCanvas was designed to absorb.

## Goals

- Add `daw-fx` to `daw-app/pom.xml` dependencies and to the `daw-app` `module-info` requires (if/when the module is migrated).
- Replace the raw `Canvas` field inside `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/display/RoomTelemetryDisplay.java` with a `GpuCanvas` whose `GpuRenderer` is a method reference to the existing `render(GraphicsContext, double, double)` body. The renderer reads width / height / `frameNumber` from `GpuRenderContext` and reads telemetry state from the surrounding `RoomTelemetryDisplay` instance — no per-frame allocation.
- Drive the existing animation accumulator from `GpuRenderContext.deltaSeconds()` (already piped from the GpuCanvas internal `AnimationTimer`); delete the bespoke ad-hoc animation loop and the `updateAnimation(double)` entry point in favour of `gpuCanvas.setAnimated(true)` when the view is mounted and `setAnimated(false)` when hidden.
- Use `GpuCanvas.setClearColor(BACKGROUND)` for the existing `#0a0a1e` fill so the renderer no longer issues a per-frame `fillRect` for the background.
- Mirror the existing `Region` API surface (mouse handlers, drag callbacks, telemetry data setter) — this is a behaviour-preserving refactor, not a redesign. The drawn pixels for a given telemetry snapshot must match within tolerance against a baseline render.
- Add `dispose()` to `RoomTelemetryDisplay` that calls `GpuCanvas.dispose()` and is invoked from the parent `TelemetryView` when the panel is detached, to stop the `AnimationTimer` and unregister listeners (the lifecycle GpuCanvas already documents).
- Plug `GpuPipeline.detect()` into `LatencyTelemetryPanel` (or a new "Renderer:" line on the existing telemetry view) so the user can confirm the active GPU backend matches expectation on Windows / macOS / Linux.
- Tests:
  - Headless test (`JavaFxToolkitExtension` from `daw-fx`): mount a `RoomTelemetryDisplay` with a deterministic `RoomTelemetryData` snapshot in a `Scene`, force two frames, assert `GpuCanvas.getFrameCount() >= 2` and that the active renderer is the telemetry renderer, not `GpuRenderer.NOOP`.
  - Test confirms `setAnimated(false)` stops further frame counter advances on the next pulse.
  - Test confirms detaching the panel triggers `dispose()` and that the timer no longer ticks.

## Non-Goals

- Visual redesign of the telemetry view — colours, isometric projection, sonar ripple geometry, suggestion badges, and labels remain identical.
- Changes to `SoundWaveTelemetryEngine` or any `daw-sdk` telemetry record (see story 066 for visualisation enhancements; this story is purely the rendering-substrate migration).
- Force-selecting a Prism pipeline (`prism.order=…`) — GpuCanvas reports the backend, it does not pick one.
- Migrating the other `display/*Display.java` siblings — those are tracked in stories 251 – 254.

## Technical Notes

- Files: `daw-app/pom.xml` (add `daw-fx` dependency), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/display/RoomTelemetryDisplay.java` (substrate swap), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/TelemetryView.java` (call `dispose()` on tear-down), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/LatencyTelemetryPanel.java` (renderer line).
- The renderer signature is `void render(GpuRenderContext ctx)` — pull `gc`, `width()`, `height()`, `deltaSeconds()`, and `frameNumber()` from `ctx` rather than passing them through a side channel.
- `GpuCanvas` is `final` and itself `extends Region`; `RoomTelemetryDisplay` should compose it (own a `GpuCanvas` field) rather than try to extend it.
- Per project memory, this is the first consumer of the `daw-fx` API — keep the integration narrow and expose any rough edges of the GpuCanvas / GpuRenderer / GpuRenderContext surface here so they can be hardened before stories 251 – 254 land.
- Reference original stories: **085 — Sound Wave Telemetry Built-In Plugin**, **066 — Telemetry Visualization Enhancements**.
