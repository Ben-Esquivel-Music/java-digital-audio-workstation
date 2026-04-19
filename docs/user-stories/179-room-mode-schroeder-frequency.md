---
title: "Room Mode Calculator with Schroeder Frequency Overlay"
labels: ["enhancement", "telemetry", "acoustics"]
---

# Room Mode Calculator with Schroeder Frequency Overlay

## Motivation

Below the Schroeder frequency (the transition point where modal behavior dominates) the room's length/width/height produce discrete resonances at predictable frequencies. These "room modes" are the defining low-frequency problem of any small room. Acoustic engineers hand-calculate the axial, tangential, and oblique modes as a first-principles room analysis. Software like REW (Room EQ Wizard) computes and displays them visually. The DAW has room dimensions; exposing the mode frequencies is low-effort, high-value educational telemetry.

## Goals

- Add `RoomModeCalculator` in `com.benesquivelmusic.daw.core.telemetry.acoustics`: given room dimensions, computes axial modes (`nx c / 2L`), tangential modes, and oblique modes up to configurable order (default n≤3).
- Compute Schroeder frequency: `f_s ≈ 2000 * sqrt(T60/V)` where V is volume and T60 is the computed reverb time.
- Emit `ModeSpectrum` record: `record ModeSpectrum(List<RoomMode> modes, double schroederHz)` where `RoomMode` is `record RoomMode(double frequencyHz, ModeKind kind, int[] indices, double magnitudeDb)`.
- `TelemetrySetupPanel` gains a "Room Modes" plot: frequency axis (20–500 Hz typical), vertical lines for each mode colored by kind (axial red, tangential orange, oblique yellow), Schroeder-frequency line (dashed vertical).
- Mode-density heatmap overlay on `RoomTelemetryDisplay` showing magnitude at the listening position.
- Suggestions when modes cluster (< 20 Hz apart) producing strong peaks or when an axial-ratio evaluation (Bolt-area, Bonello criterion) flags a poor room proportion.
- Persist nothing new.
- Tests: a 5 × 4 × 3 m room produces known axial modes at c/10, c/8, c/6 Hz with their first overtones at double; Schroeder frequency matches published formula.

## Non-Goals

- Modal decay time per mode (requires impulse-response measurement — separate story).
- 3D finite-element simulation (out of scope; analytic box modes only).
- Automatic placement optimization to avoid modes (moves mic to best seat — a future story).
