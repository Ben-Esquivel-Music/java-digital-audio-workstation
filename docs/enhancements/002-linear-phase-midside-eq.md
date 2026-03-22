# Enhancement: Linear-Phase and Mid/Side Parametric EQ

## Summary

Extend the existing `ParametricEqProcessor` with linear-phase filtering mode and Mid/Side (M/S) processing capabilities. Linear-phase EQ avoids phase distortion critical for mastering, while M/S processing enables independent equalization of the center (mid) and stereo (side) content.

## Motivation

The current `ParametricEqProcessor` uses minimum-phase biquad filters via `BiquadFilter`. Professional mastering requires linear-phase EQ to avoid phase smearing across the frequency spectrum, and M/S EQ to independently shape the mono center image and stereo width. These are standard features in every professional mastering DAW and are essential for competitive mastering workflows.

## Research Sources

- [Mastering Techniques](../research/mastering-techniques.md) — Core Technique #3: "Linear-phase EQ for mastering to avoid phase distortion" and "Mid/Side EQ for separate processing of center and stereo content"
- [AES Research Papers](../research/aes-research-papers.md) — "Exploring Perceptual Audio Quality Measurement on Stereo Processing using the Open Dataset of Audio Quality" — validated framework for evaluating M/S processing quality
- [Mastering Techniques](../research/mastering-techniques.md) — High Priority: "Parametric EQ with linear-phase option"

## Sub-Tasks

- [ ] Research and implement linear-phase FIR filter generation from biquad coefficients (frequency sampling or windowed sinc method)
- [ ] Add `FilterMode` enum (`MINIMUM_PHASE`, `LINEAR_PHASE`) to `ParametricEqProcessor`
- [ ] Implement latency compensation reporting for linear-phase mode (FIR filters introduce latency)
- [ ] Implement M/S encoding matrix (Mid = L+R, Side = L−R) in a new `MidSideEncoder` utility
- [ ] Implement M/S decoding matrix (L = Mid+Side, R = Mid−Side) in a `MidSideDecoder` utility
- [ ] Add `ProcessingMode` enum (`STEREO`, `MID_SIDE`) to `ParametricEqProcessor`
- [ ] Enable independent EQ band configuration for mid and side channels in M/S mode
- [ ] Add unit tests for linear-phase filter frequency response accuracy
- [ ] Add unit tests for M/S encode/decode round-trip (verify signal preservation)
- [ ] Add unit tests for M/S EQ processing with independent mid/side band settings
- [ ] Update `ParametricEqProcessor` Javadoc with usage examples for both modes

## Affected Modules

- `daw-core` (`dsp/ParametricEqProcessor`, `dsp/BiquadFilter`, new M/S utilities)
- `daw-sdk` (possible new interfaces for M/S processing)

## Priority

**High** — Core mastering feature
