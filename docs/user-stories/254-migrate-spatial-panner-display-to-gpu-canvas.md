---
title: "Migrate SpatialPannerDisplay (3D Object Position View) to GpuCanvas"
labels: ["enhancement", "ui", "spatial", "immersive", "performance", "gpu"]
---

# Migrate SpatialPannerDisplay (3D Object Position View) to GpuCanvas

## Motivation

`daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/display/SpatialPannerDisplay.java` renders the 3D object panner used by the spatial panner UI (story 017), the object-panner automation lane (story 172 / 239), and the Atmos A/B comparison view (story 175 / 241). It draws a top-down or perspective view of the listening room, the seven beds (L / R / C / LFE / Ls / Rs / Lt) and any surround / overhead speaker positions, plus the animated object trajectory dot for the selected track. It currently holds a raw `javafx.scene.canvas.Canvas` and a hand-rolled animation pulse for the trajectory dot's interpolation between automation breakpoints.

The `daw-fx` substrate (story 250) is a one-for-one drop-in: a per-frame `GpuRenderer` callback, `GpuRenderContext.deltaSeconds()` for trajectory interpolation, and the same Prism hardware backend (D3D 11 / Metal / OpenGL ES2) that the room telemetry view (story 250) already uses for its isometric 3D pass.

## Goals

- Migrate `SpatialPannerDisplay` to compose a `GpuCanvas`. The renderer is the body of the existing draw routine; trajectory interpolation reads `GpuRenderContext.deltaSeconds()` and `frameNumber()` from the context.
- Set `setAnimated(true)` while object-panner automation is playing back (driven from `TransportController` state) and `false` when transport is stopped — eliminates wasted frames when the dot is static.
- Use `GpuCanvas.setClearColor(...)` for the room background; the renderer stops issuing a background `fillRect` per frame.
- The existing `SpatialTrajectoryOverlay` (which draws breakpoint paths over the panner display) keeps its current API; verify it still composites correctly when its parent owns a `GpuCanvas` rather than a raw `Canvas` (it should — the overlay reads from the panner's shared coordinate system, not from the canvas pixels directly).
- Behaviour-preserving refactor: speaker positions, room outline, object dot, glow, and labels render identically. Pixels for a given `(azimuth, elevation, distance)` triple must match the pre-migration output within tolerance.
- Verify lifecycle: the spatial panner view in `MainController` / `SpatialPannerController` must call `dispose()` on the panner display when its dock pane is closed so the GpuCanvas timer is unregistered.
- Tests:
  - Headless test: mount the panner with a deterministic object position, force a frame, snapshot the canvas, assert the dot pixel is within one device pixel of the projected `(x, y)` for a given speaker layout (5.1 / 7.1 / 7.1.4 / 9.1.6).
  - Test confirms that during simulated playback (transport `playing == true`), the frame counter advances on each FX pulse, and that when transport stops, the counter stops advancing within one frame.
  - Test confirms `SpatialTrajectoryOverlay` still tracks the panner's coordinate transform after the substrate swap.

## Non-Goals

- Changing the projection (top-down vs. perspective vs. isometric) or speaker-layout templates.
- Adding new spatial layouts (Ambisonic-order overlays, binaural ear sphere, etc.).
- Replacing `SpatialTrajectoryOverlay` itself — only verifying its compositing still works.
- Migrating `AtmosAbView`'s per-channel level visualisation (separate scene object; consider in a follow-up if profiling shows benefit).

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/display/SpatialPannerDisplay.java` (substrate swap), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/spatial/SpatialTrajectoryOverlay.java` (verify-only), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/SpatialPannerController.java` (call `dispose()` on tear-down).
- The panner already uses normalised `(azimuth, elevation, distance)` coordinates — the projection function moves verbatim into the renderer body.
- Story 250 must land first to add the `daw-fx` dependency to `daw-app`.
- Reference original stories: **017 — 3D Spatial Panner UI**, **172 — Object Panner Automation**, **239 — Object Panner Automation Lane Integration**.
