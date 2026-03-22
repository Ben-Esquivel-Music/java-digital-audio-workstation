# Enhancement: Binaural Renderer with HRTF/SOFA Support

## Summary

Implement a binaural rendering engine that spatializes audio for headphone playback using Head-Related Transfer Functions (HRTFs). Support the SOFA file format for loading custom HRTF profiles, and provide A/B switching between speaker and binaural monitoring modes.

## Motivation

The majority of modern music listeners use headphones. Binaural rendering is the bridge between immersive spatial mixes and headphone playback — it simulates 3D audio perception by applying direction-dependent filtering (HRTFs) to each audio source. AES research demonstrates measurable spectral and imaging differences between stereo and binaural masters, making accurate binaural rendering critical for immersive production. SOFA file support enables engineers to use personalized HRTF profiles for improved localization accuracy.

## Research Sources

- [Immersive Audio Mixing](../research/immersive-audio-mixing.md) — Core Technique #3: "HRTF profiles encode how sound arrives at each ear from any direction" and "SOFA file format stores HRTF data for interoperability"
- [Immersive Audio Mixing](../research/immersive-audio-mixing.md) — High Priority: "Binaural renderer with HRTF selection (SOFA import)"
- [AES Research Papers](../research/aes-research-papers.md) — "Spectral and Spatial Discrepancies Between Stereo and Binaural Spatial Masters" — documents measurable frequency, imaging, and loudness shifts in binaural rendering
- [AES Research Papers](../research/aes-research-papers.md) — "Extending Realism for Digital Piano Players: 3DoF and 6DoF Head-Tracked Binaural Audio" — perceptual benefits of head tracking
- [Audio Development Tools](../research/audio-development-tools.md) — "libmysofa → SOFA HRTF file reader" (C library, JNI candidate)
- [Audio Development Tools](../research/audio-development-tools.md) — "Spatial Audio Framework (SAF) → Ambisonics, HRIR, panning" (C/C++, JNI candidate)

## Sub-Tasks

- [ ] Design `BinauralRenderer` interface in `daw-sdk` for headphone spatialization
- [ ] Implement SOFA file parser in Java for loading HRTF datasets (AES69 SOFA format)
- [ ] Alternatively, evaluate JNI integration with `libmysofa` for SOFA file reading
- [ ] Implement HRTF interpolation for arbitrary source positions (between measured HRTF directions)
- [ ] Implement partitioned convolution for efficient real-time HRTF filtering (overlap-save method)
- [ ] Bundle a default HRTF dataset (e.g., MIT KEMAR or CIPIC) for out-of-the-box functionality
- [ ] Add HRTF profile selection UI data (list available profiles, preview directions)
- [ ] Implement A/B monitoring mode switching (speaker rendering ↔ binaural rendering)
- [ ] Add ITD (Interaural Time Difference) modeling for improved low-frequency localization
- [ ] Implement crossfade between HRTF filters when source position changes (avoid clicks)
- [ ] Add unit tests for SOFA file parsing and HRTF data integrity
- [ ] Add unit tests for HRTF interpolation accuracy
- [ ] Add unit tests for binaural rendering output (verify left/right channel differentiation)
- [ ] Document HRTF profile format requirements and custom SOFA import instructions

## Affected Modules

- `daw-sdk` (new `spatial/BinauralRenderer` interface)
- `daw-core` (new `spatial/binaural/` package)
- `daw-app` (monitoring mode toggle, HRTF profile selector)

## Priority

**High** — Critical for headphone-based immersive monitoring
