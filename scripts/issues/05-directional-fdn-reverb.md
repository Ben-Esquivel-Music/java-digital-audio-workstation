## Description

Extend the existing `FdnRoomSimulator` with directional output capabilities. A directional FDN assigns spatial direction to each delay line output, enabling reverb that preserves the spatial distribution of reflections — essential for immersive audio production where reverb should surround the listener rather than collapse to a single point.

## AES Research References

- [Directional Feedback Delay Network](docs/research/AES/Directional_Feedback_Delay_Network.pdf) (2019) — Proposes assigning directional weights to FDN delay-line outputs and rendering them via Ambisonics encoding, producing spatially distributed reverberation
- [Designing Directional Reverberators for Spatial Sound Reproduction](docs/research/AES/Designing_Directional_Reverberators_for_Spatial_Sound_Reproduction.pdf) (2024) — Methods for designing reverberators that produce direction-dependent decay and spectral characteristics
- [Object-Based Reverberation for Spatial Audio](docs/research/AES/Object-Based_Reverberation_for_Spatial_Audio.pdf) (2017) — Framework for treating reverb as a spatial object with position and spread metadata

## Implementation Approach

- New class `DirectionalFdnProcessor` in `daw-core/…/spatial/room/`
- Each of the N FDN delay-line outputs is assigned a spherical direction (azimuth, elevation)
- Output is encoded to first-order Ambisonics (W, X, Y, Z) using the existing `AmbisonicEncoder`
- Directional diffusion: late reflections arrive from distributed directions, not a single point
- Compatible with the existing `FdnRoomSimulator` architecture (Householder matrix, allpass diffusers)
- Parameters: room size, decay, damping, directional spread

## Extends

`FdnRoomSimulator`, `AmbisonicEncoder`
