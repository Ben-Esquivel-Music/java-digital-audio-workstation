---
title: "Per-Surface Material Map for Room Telemetry"
labels: ["enhancement", "telemetry", "acoustics", "sdk"]
---

# Per-Surface Material Map for Room Telemetry

## Motivation

`RoomConfiguration` currently carries a single `WallMaterial` applied uniformly to every surface. In real rooms the ceiling is often absorbent (fiberglass panels), the floor is reflective (concrete + rug islands), and the walls are a mix. The current model cannot represent that, so the RT60 estimate and reflection-based suggestions are wrong for any realistic space. Story 122 expands ceiling geometry; this story expands material per surface, closing the other half of the acoustic-fidelity gap.

## Goals

- Add `SurfaceMaterialMap` record in `com.benesquivelmusic.daw.sdk.telemetry`: `record SurfaceMaterialMap(WallMaterial floor, WallMaterial frontWall, WallMaterial backWall, WallMaterial leftWall, WallMaterial rightWall, WallMaterial ceiling)`.
- Replace the single `WallMaterial` on `RoomConfiguration` with `SurfaceMaterialMap`, keeping a convenience constructor that broadcasts one material to all six surfaces for backwards compatibility.
- Update `SoundWaveTelemetryEngine` RT60 computation using per-surface absorption coefficients (Sabine / Eyring variant chosen automatically based on mean absorption).
- Reflection-energy computation per surface pair uses each surface's absorption; the existing per-reflection metadata captures the originating surface for UI highlighting.
- `TelemetrySetupPanel`: extend the room section with a 3D wireframe inset whose surfaces are color-coded by material; clicking a surface opens a material picker for that surface.
- `RoomPreset` / `RoomPresetLibrary` updated: "Concert Hall" uses marble floors + absorbent ceiling, "Church" uses stone walls + wood floor, etc. Accurate presets make the presets genuinely educational.
- Persist via `ProjectSerializer`; legacy projects (single `WallMaterial`) load by broadcasting to all surfaces.
- Tests: RT60 differs correctly between "all absorbent" and "all reflective" material maps; the broadcast constructor produces bit-identical RT60 to the old single-material code.

## Non-Goals

- Per-panel material granularity (each wall is one material in this story).
- Frequency-dependent absorption (a follow-up story using octave-band coefficients).
- Automatic material detection from photos.
