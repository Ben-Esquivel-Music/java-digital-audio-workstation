# Enhancement: Room Acoustic Simulation via RoomAcoustiC++ JNI Integration

## Summary

Integrate [RoomAcoustiC++](https://github.com/music-computing/RoomAcoustiCpp) — an open-source C++ library for real-time room acoustic modeling — via JNI to provide room simulation capabilities within the DAW. This enables virtual room placement of sound sources, configurable room geometry and materials, and real-time auralization for mixing and monitoring.

## Motivation

AES research presents RoomAcoustiC++ as a hybrid geometric acoustics / FDN (Feedback Delay Network) model that runs in real time. It combines an Image Edge Model for early reflections with FDN-based late reverberation, providing physically accurate room simulation suitable for both creative use (placing instruments in virtual rooms) and practical use (simulating monitoring environments). The DAW already has room telemetry data structures (`RoomDimensions`, `WallMaterial`, `SoundSource`, `Position3D`) in `daw-sdk` that align well with this integration.

## Research Sources

- [AES Research Papers](../research/aes-research-papers.md) — "RoomAcoustiC++: An open-source room acoustic model for real-time audio simulations" — **High** priority, directly usable C++ library for JNI integration
- [AES Research Papers](../research/aes-research-papers.md) — "Predicting the Perceptibility of Room Acoustic Variations Using Generalized Linear Mixed Models" — quality thresholds for room simulation detail
- [Research README](../research/README.md) — Future #5: "Room acoustic simulation via RoomAcoustiC++ JNI integration"
- [Research README](../research/README.md) — Architecture: "RoomAcoustiC++ is an open-source C++ library for real-time room acoustic modeling using hybrid geometric acoustics and FDN"

## Sub-Tasks

- [ ] Evaluate RoomAcoustiC++ API surface and identify the subset needed for DAW integration
- [ ] Design `RoomSimulator` interface in `daw-sdk` extending existing telemetry types (`RoomDimensions`, `WallMaterial`, `SoundSource`, `Position3D`)
- [ ] Implement JNI bridge for RoomAcoustiC++ core functions (room configuration, source placement, rendering)
- [ ] Map existing `RoomConfiguration` and `RoomTelemetryData` to RoomAcoustiC++ parameters
- [ ] Implement room geometry input (rectangular rooms, configurable dimensions and wall materials)
- [ ] Implement sound source placement within the room (leverage `Position3D` and `SoundSource`)
- [ ] Implement listener position and orientation within the room
- [ ] Implement real-time impulse response generation from room configuration
- [ ] Implement convolution-based auralization using the generated impulse responses
- [ ] Add room material presets (wood, concrete, glass, carpet, acoustic foam, etc.) extending existing `WallMaterial`
- [ ] Add room preset library (studio, concert hall, bathroom, cathedral, etc.)
- [ ] Build native library for Windows, macOS, and Linux
- [ ] Add unit tests for room configuration serialization and parameter mapping
- [ ] Add integration tests for impulse response generation from known room geometries
- [ ] Document room simulation setup, parameter guide, and performance characteristics

## Affected Modules

- `daw-sdk` (`telemetry/` — extend existing room types, new `spatial/RoomSimulator` interface)
- `daw-core` (`telemetry/` — integrate with RoomAcoustiC++, new `spatial/room/` package)
- `daw-app` (`ui/display/RoomTelemetryDisplay` — enhance with room simulation visualization)
- New native module for RoomAcoustiC++ JNI bridge

## Priority

**Future** — Advanced spatial feature; requires native library compilation and JNI infrastructure
