## Description

Add panning table synthesis to extend the existing `VbapPanner` for irregular and non-standard speaker layouts. VBAP requires triangulation of speaker positions which can fail or produce artifacts for non-ideal layouts. Panning table synthesis pre-computes a lookup table that smoothly interpolates panning gains for any source position, handling arbitrary speaker placements gracefully.

## AES Research References

- [Emulating Vector Base Amplitude Panning Using Panningtable Synthesis](docs/research/AES/Emulating_Vector_Base_Amplitude_Panning_Using_Panningtable_Synthesis.pdf) (2023) — Proposes a panning table synthesis method that emulates VBAP behavior while handling irregular and degenerate speaker layouts; pre-computes gain tables at configurable angular resolution for efficient real-time lookup
- [Multichannel Compensated Amplitude Panning, An Adaptive Object-Based Reproduction Method](docs/research/AES/Multichannel_Compensated_Amplitude_Panning,_An_Adaptive_Object-Based_Reproduction_Method.pdf) (2019) — Compensated panning for object-based reproduction with non-uniform speaker arrangements
- [Immersive Audio Reproduction and Adaptability for Irregular Loudspeaker Layouts Using Modified EBU ADM Renderer](docs/research/AES/Immersive_Audio_Reproduction_and_Adaptability_for_Irregular_Loudspeaker_Layouts_Using_Modified_EBU_ADM_Renderer.pdf) (2024) — Modified ADM renderer for irregular layouts; validates the need for flexible panning algorithms

## Implementation Approach

- New class `PanningTableSynthesizer` in `daw-core/…/spatial/panner/`
- Pre-compute panning gain table at configurable angular resolution (e.g., 1° azimuth × 1° elevation)
- For each grid point: compute VBAP gains using existing `VbapPanner`, or use nearest-neighbor interpolation for degenerate triangulations
- Runtime: bilinear interpolation of the pre-computed table for sub-degree source positions
- Handles irregular layouts: missing speakers, non-symmetric arrangements, height-only arrays
- Integrates as an alternative panning strategy alongside `VbapPanner`

## Extends

`VbapPanner`, `SpeakerLayout`
