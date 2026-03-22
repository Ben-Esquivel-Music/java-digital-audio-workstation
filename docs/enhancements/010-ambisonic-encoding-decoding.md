# Enhancement: Ambisonic Encoding and Decoding (FOA/HOA)

## Summary

Implement Ambisonic encoding from mono/stereo sources to B-format, and decoding from B-format to arbitrary speaker layouts and binaural output. Support First-Order Ambisonics (FOA, 4 channels) and Higher-Order Ambisonics (HOA, up to 3rd or 7th order) with salient/diffuse stream separation for enhanced spatial resolution.

## Motivation

Ambisonics is the standard scene-based spatial audio format used in VR/360° media and as an intermediate representation for immersive mixing. Unlike object-based approaches (Dolby Atmos), Ambisonics encodes the entire sound field into a compact channel set that can be decoded to any speaker layout or binaural at playback. AES research demonstrates that FOA spatial resolution can be significantly enhanced using salient/diffuse decomposition (ASDM), making first-order content perceptually comparable to higher orders.

## Research Sources

- [Immersive Audio Mixing](../research/immersive-audio-mixing.md) — Core Technique #4: "First-Order Ambisonics (FOA): 4-channel B-format" and "Higher-Order Ambisonics (HOA): Increased spatial resolution"
- [Immersive Audio Mixing](../research/immersive-audio-mixing.md) — Medium Priority: "Ambisonic encoding/decoding (FOA and HOA)"
- [AES Research Papers](../research/aes-research-papers.md) — "Ambisonic Spatial Decomposition Method with salient/diffuse separation" — algorithm for enhancing FOA spatial resolution
- [Audio Development Tools](../research/audio-development-tools.md) — "Spatial Audio Framework (SAF) → Ambisonics, HRIR, panning, room simulation"
- [Research README](../research/README.md) — Future #2: "Spatial audio (3D panner, binaural renderer, Ambisonics with salient/diffuse separation)"

## Sub-Tasks

- [ ] Design `AmbisonicBus` abstraction in `daw-sdk` for carrying Ambisonic signals (FOA: 4ch, 2nd order: 9ch, 3rd order: 16ch)
- [ ] Implement FOA (1st order) B-format encoder from mono source + position (azimuth, elevation)
- [ ] Implement HOA encoder (2nd and 3rd order) using spherical harmonic coefficients
- [ ] Implement Ambisonic decoder for arbitrary 2D speaker layouts (basic, max-rE, in-phase decoding)
- [ ] Implement Ambisonic decoder for 3D speaker layouts (including height channels)
- [ ] Implement Ambisonic-to-binaural decoder using virtual speaker method or direct HRTF convolution
- [ ] Implement A-format to B-format conversion for Ambisonic microphone recordings (e.g., Sennheiser AMBEO, Zoom H3-VR)
- [ ] Implement ASDM (Ambisonic Spatial Decomposition Method) for salient/diffuse stream separation to enhance FOA resolution
- [ ] Add Ambisonic bus routing in the mixer (Ambisonic channel groups)
- [ ] Implement Ambisonic rotation (yaw, pitch, roll) for scene orientation
- [ ] Add unit tests for spherical harmonic encoding accuracy
- [ ] Add unit tests for decode-encode round-trip signal preservation
- [ ] Add unit tests for ASDM salient/diffuse separation with known test signals
- [ ] Add unit tests for A-format to B-format conversion

## Affected Modules

- `daw-sdk` (new `spatial/AmbisonicBus` abstraction)
- `daw-core` (new `spatial/ambisonics/` package)
- `daw-core` (`mixer/Mixer` — Ambisonic bus routing)

## Priority

**Medium** — Requires 3D Spatial Panner (Issue #008) as a prerequisite
