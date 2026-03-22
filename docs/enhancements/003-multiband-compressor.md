# Enhancement: Multiband Compressor

## Summary

Implement a multiband compressor that splits the audio signal into configurable frequency bands and applies independent compression to each band. This enables precise dynamic control of specific frequency ranges without affecting others — essential for mastering and advanced mixing.

## Motivation

The current `CompressorProcessor` operates on the full-band signal. Professional mastering and mixing frequently require multiband compression to independently control dynamics in the low end, midrange, and high frequencies. For example, taming a boomy bass without affecting vocal dynamics, or controlling sibilance without compressing the entire mix. This is listed as a high-priority core feature across the mastering research.

## Research Sources

- [Mastering Techniques](../research/mastering-techniques.md) — Core Technique #4: "Multiband compression for containing specific frequency ranges independently"
- [Mastering Techniques](../research/mastering-techniques.md) — High Priority: "Single-band and multiband compressor"
- [Audio Development Tools](../research/audio-development-tools.md) — Phase 2 Effects and Processing: "Compressor/Limiter → Implement in Java → JUCE DSP module" reference

## Sub-Tasks

- [ ] Design `MultibandCompressorProcessor` class extending the `AudioProcessor` interface
- [ ] Implement Linkwitz-Riley crossover filter network for band splitting (2-band, 3-band, 4-band configurations)
- [ ] Create `CrossoverFilter` utility class for steep linear-phase band splitting
- [ ] Implement per-band `CompressorProcessor` instances with independent attack, release, ratio, threshold, and knee parameters
- [ ] Add per-band gain makeup and solo/bypass controls
- [ ] Implement band recombination with phase-correct summing
- [ ] Add per-band gain reduction metering output (for visualization)
- [ ] Add configurable crossover frequency controls
- [ ] Add unit tests for crossover filter flatness (sum of all bands = original signal)
- [ ] Add unit tests for per-band compression independence
- [ ] Add unit tests for various band configurations (2, 3, 4 bands)
- [ ] Document crossover filter design choices and compression algorithm details

## Affected Modules

- `daw-core` (`dsp/MultibandCompressorProcessor`, `dsp/CrossoverFilter`)
- `daw-sdk` (visualization data for per-band gain reduction metering)

## Priority

**High** — Core mastering and mixing feature
