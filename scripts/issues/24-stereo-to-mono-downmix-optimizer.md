## Description

Add an optimized stereo-to-mono down-mix algorithm that minimizes phase cancellation artifacts. Simple L+R summing causes destructive cancellation of out-of-phase content, reducing bass and losing spatial elements. This optimizer detects and compensates for these issues, producing a mono mix that better represents the original stereo signal.

## AES Research References

- [Low Complexity Methods for Robust Stereo-to-Mono Down-mixing](docs/research/AES/Low_Complexity_Methods_for_Robust_Stereo-to-Mono_Down-mixing.pdf) (2022) — Presents low-complexity methods for robust mono downmixing: polarity-adaptive summing, time-aligned summing, and energy-preserving summing that avoid destructive cancellation while maintaining low computational cost

## Implementation Approach

- New class `MonoDownMixOptimizer` in `daw-core/…/dsp/`
- Three modes:
  - **Standard sum**: simple `(L + R) / 2` (baseline)
  - **Polarity-adaptive**: per-band polarity detection using cross-correlation; invert side component in bands with negative correlation before summing
  - **Energy-preserving**: ensure mono RMS matches the average of L and R RMS levels by applying frequency-dependent gain compensation
- Mono compatibility score output: quantify how much energy is lost in standard summing vs. optimized
- Uses existing `CrossoverFilter` for multiband processing and `CorrelationMeter` for correlation analysis
- Applications: mono compatibility check, podcast/voice mono export, broadcast compatibility

## Extends

`CrossoverFilter`, `CorrelationMeter`, `MidSideEncoder`
