# Enhancement: 3D Spatial Panner with Automation

## Summary

Implement a 3D spatial panner that enables positioning audio sources in three-dimensional space (X/Y/Z coordinates) with distance attenuation modeling, size/spread controls, and full automation support. This is the foundational component for all immersive audio features in the DAW.

## Motivation

Immersive audio production (Dolby Atmos, Apple Spatial Audio, Ambisonics) requires the ability to position and move audio sources in 3D space. The 3D panner is the core user-facing tool for spatial mixing, enabling engineers to place instruments in a hemisphere around the listener with automated movement over time. The existing `Position3D` record in `daw-sdk/telemetry` provides a starting point, but a full 3D panner with distance modeling, spread controls, and automation is needed.

## Research Sources

- [Immersive Audio Mixing](../research/immersive-audio-mixing.md) — Core Technique #1: "3D pan automation for dynamic movement and placement" with "X/Y/Z coordinates," "distance attenuation modeling," and "snap-to-speaker and free-form positioning modes"
- [Immersive Audio Mixing](../research/immersive-audio-mixing.md) — High Priority: "3D panner with X/Y/Z positioning and automation"
- [AES Research Papers](../research/aes-research-papers.md) — "Spatial Composition and What It Means for Immersive Audio Production" — framework for spatial audio as compositional tool
- [AES Research Papers](../research/aes-research-papers.md) — "Optimized Loudspeaker Panning for Adaptive Sound-Field Correction" — Bayesian panning for non-standard speaker layouts

## Sub-Tasks

- [ ] Design `SpatialPanner` interface in `daw-sdk` with 3D position (azimuth, elevation, distance), size, and spread parameters
- [ ] Create `SpatialPosition` record with spherical coordinates (azimuth, elevation, distance) and Cartesian (X, Y, Z) conversion
- [ ] Implement VBAP (Vector Base Amplitude Panning) algorithm for speaker-based rendering
- [ ] Implement distance attenuation model (inverse square law with configurable rolloff curve)
- [ ] Implement distance-based spectral filtering (high-frequency rolloff with distance)
- [ ] Implement distance-based early reflection/reverb send (more reverb at greater distance)
- [ ] Add source size/spread parameter for diffuse source rendering
- [ ] Implement pan automation data structure with per-parameter curves (azimuth, elevation, distance over time)
- [ ] Add snap-to-speaker positioning mode for fixed speaker layouts
- [ ] Add free-form positioning mode for continuous 3D placement
- [ ] Create `SpatialPannerData` visualization record for 3D panner UI
- [ ] Add unit tests for VBAP panning accuracy and energy preservation
- [ ] Add unit tests for distance attenuation model correctness
- [ ] Add unit tests for coordinate system conversion (spherical ↔ Cartesian)
- [ ] Add unit tests for pan automation interpolation accuracy

## Affected Modules

- `daw-sdk` (new `spatial/SpatialPanner` interface, `spatial/SpatialPosition` record)
- `daw-core` (new `spatial/` package with VBAP implementation)
- `daw-app` (3D panner visualization UI)

## Priority

**High** — Foundation for all immersive audio features
