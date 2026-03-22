## Description

Add the ability to resynthesize the late reverb tail of a spatial room impulse response (RIR) with configurable anisotropic multi-slope decay. This enables extending or modifying measured room impulse responses — for example, extending the decay time of a short RIR or adjusting the directional decay balance without re-measuring the room.

## AES Research References

- [Resynthesis of Spatial Room Impulse Response Tails With Anisotropic Multi-Slope Decays](docs/research/AES/Resynthesis_of_Spatial_Room_Impulse_Response_Tails_With_Anisotropic_Multi-Slope_Decays.pdf) (2022) — Presents a method for resynthesizing the late reverb tail of multichannel RIRs with independent decay slopes per direction; uses shaped noise filtered to match the original RIR spectral and temporal envelope
- [Computationally-Efficient Simulation of Late Reverberation for Inhomogeneous Boundary Conditions and Coupled Rooms](docs/research/AES/Computationally-Efficient_Simulation_of_Late_Reverberation_for_Inhomogeneous_Boundary_Conditions_and_Coupled_Rooms.pdf) (2023) — Efficient late reverberation methods for rooms with non-uniform surfaces; relevant to non-isotropic decay modeling

## Implementation Approach

- New class `SpatialRirResynthesizer` in `daw-core/…/spatial/room/`
- Analyze measured RIR: extract energy decay curve per frequency band per spatial direction
- Resynthesize late tail using shaped Gaussian noise filtered to match spectral envelope
- Independent decay time per direction (anisotropic) and per frequency band
- Crossfade resynthesized tail with measured early reflections at a configurable mixing time
- Compatible with SOFA-format impulse responses via existing `SofaFileParser`

## Extends

`FftUtils`, `SofaFileParser`, `FdnRoomSimulator`
