# Enhancement: Stereo Imaging and Correlation Metering Enhancements

## Summary

Enhance the existing `StereoImagerProcessor` and `CorrelationMeter` with mid/side width control, low-frequency mono summing, goniometer/vectorscope visualization data, and frequency-dependent stereo width analysis. These are essential mastering tools for ensuring stereo mixes translate well across all playback systems.

## Motivation

The existing `StereoImagerProcessor` provides basic stereo width control and the `CorrelationMeter` provides basic correlation measurement. Professional mastering requires more sophisticated stereo tools: frequency-dependent width control, bass mono-ification (narrowing low frequencies to prevent phase cancellation on mono playback systems), goniometer visualization (Lissajous display), and detailed correlation analysis. The AES research on stereo processing quality (ODAQ dataset) provides a validated framework for evaluating these enhancements.

## Research Sources

- [Mastering Techniques](../research/mastering-techniques.md) — Core Technique #7: "Mid/Side encoding for width manipulation," "Correlation metering to ensure mono compatibility," and "Bass mono-ification"
- [Mastering Techniques](../research/mastering-techniques.md) — High Priority: "Stereo imager with correlation meter"
- [Research README](../research/README.md) — Near-Term #3: "Stereo imager and correlation metering"
- [AES Research Papers](../research/aes-research-papers.md) — "Exploring Perceptual Audio Quality Measurement on Stereo Processing using the Open Dataset of Audio Quality" — validated quality framework for M/S processing

## Sub-Tasks

- [ ] Add frequency-dependent stereo width control to `StereoImagerProcessor` (independent width per band)
- [ ] Implement low-frequency mono summing filter (configurable crossover, typically 80–200 Hz)
- [ ] Add stereo widening algorithm (e.g., Haas effect, complementary comb filtering, or M/S gain)
- [ ] Implement goniometer (Lissajous XY) visualization data output in `CorrelationMeter`
- [ ] Implement vectorscope data output with polar coordinate representation
- [ ] Add frequency-dependent correlation analysis (correlation per frequency band)
- [ ] Add phase inversion detection and warning
- [ ] Create `GoniometerData` record in `daw-sdk/visualization` for Lissajous display data
- [ ] Add mono compatibility check method (predict mono fold-down quality)
- [ ] Add unit tests for low-frequency mono summing accuracy
- [ ] Add unit tests for stereo width control range and symmetry
- [ ] Add unit tests for goniometer data generation correctness
- [ ] Add unit tests for frequency-dependent correlation accuracy

## Affected Modules

- `daw-core` (`dsp/StereoImagerProcessor`, `analysis/CorrelationMeter`)
- `daw-sdk` (`visualization/CorrelationData`, new `visualization/GoniometerData`)
- `daw-app` (goniometer/vectorscope display)

## Priority

**High** — Essential mastering metering and processing
