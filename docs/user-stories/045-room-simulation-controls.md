---
title: "Room Acoustic Simulation Parameter Controls in Telemetry View"
labels: ["enhancement", "spatial-audio", "telemetry", "ui"]
---

# Room Acoustic Simulation Parameter Controls in Telemetry View

## Motivation

The `TelemetryView` and `TelemetrySetupPanel` provide a setup workflow for the Sound Wave Telemetry room visualization. The `FdnRoomSimulator`, `RoomPresetLibrary`, `RoomSimulationParameterMapper`, and `RoomAcousticBridge` classes provide the room simulation engine. However, the telemetry view's interaction with room parameters is limited. Users should be able to interactively adjust room dimensions, wall materials, source/microphone positions, and see the simulation update in real-time. The `RoomPresetLibrary` has presets for different room types, but they are not accessible in the UI. Rich room simulation controls would help users understand acoustic environments and make better recording decisions.

## Goals

- Add preset selection for common room types (studio, concert hall, bedroom, church) from `RoomPresetLibrary`
- Allow interactive adjustment of room dimensions (width, depth, height) with sliders
- Allow selecting wall materials from the `WallMaterial` enum with visual feedback on absorption
- Support drag-and-drop repositioning of sound sources and microphones on the telemetry canvas
- Display real-time RT60 (reverberation time) calculation as room parameters change
- Show early reflection paths and levels for each source/microphone pair
- Provide suggestions for optimal microphone placement based on the simulation
- Allow saving custom room configurations as user presets

## Non-Goals

- Physical room measurement with actual microphones
- 3D room visualization (the telemetry view is 2D top-down)
- Integration with external room correction software
