# Enhancement: Pure Java DSP Foundation via TarsosDSP Integration

## Summary

Integrate [TarsosDSP](https://github.com/JorenSix/TarsosDSP) as the core pure-Java DSP library for audio processing. TarsosDSP provides pitch detection, time stretching, audio effects, and filters with no external native dependencies, making it the ideal foundation for the DAW's signal processing pipeline.

## Motivation

The DAW currently has basic DSP processors (`ParametricEqProcessor`, `CompressorProcessor`, `LimiterProcessor`, etc.) implemented from scratch. TarsosDSP is the only mature pure-Java audio processing library with practical DSP capabilities, and integrating it would provide a robust foundation of battle-tested algorithms for pitch detection, onset detection, effects processing, and audio analysis — significantly accelerating development of higher-level features.

## Research Sources

- [Audio Development Tools](../research/audio-development-tools.md) — Phase 1 Core Audio Engine: "Basic DSP → TarsosDSP → Direct Java dependency"
- [Research README](../research/README.md) — "TarsosDSP is the only pure-Java audio processing library with practical DSP capabilities"
- [Research README](../research/README.md) — Immediate Priority #1: "Pure Java DSP via TarsosDSP integration"

## Sub-Tasks

- [ ] Add TarsosDSP as a Maven dependency in `daw-core/pom.xml`
- [ ] Create `TarsoDspBridge` adapter class in `daw-core` to wrap TarsosDSP processors behind the existing `AudioProcessor` interface from `daw-sdk`
- [ ] Integrate TarsosDSP pitch detection into `daw-core/analysis` (complement existing `SpectrumAnalyzer`)
- [ ] Integrate TarsosDSP onset detection for beat/transient detection capabilities
- [ ] Integrate TarsosDSP time-stretching algorithm for non-destructive tempo changes
- [ ] Wire TarsosDSP filters (FIR/IIR) into the existing `BiquadFilter` pipeline as alternative implementations
- [ ] Add unit tests for all TarsosDSP adapter classes
- [ ] Benchmark TarsosDSP processing latency against existing custom DSP implementations
- [ ] Document integration architecture and usage in `daw-core` module README

## Affected Modules

- `daw-sdk` (interface compatibility)
- `daw-core` (primary integration point)

## Priority

**Immediate** — Core foundation for all subsequent DSP features
