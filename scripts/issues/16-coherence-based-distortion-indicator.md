## Description

Add a coherence function measurement between input and output of a signal chain to detect and quantify nonlinear distortion. Coherence drops below 1.0 at frequencies where distortion is present — providing a frequency-dependent distortion map that is more informative than scalar THD measurements.

## AES Research References

- [Coherence as an Indicator of Distortion for Wide-Band Audio Signals such as M-Noise and Music](docs/research/AES/Coherence_as_an_Indicator_of_Distortion_for_Wide-Band_Audio_Signals_such_as_M-Noise_and_Music.pdf) (2019) — Demonstrates that magnitude-squared coherence between input and output provides a reliable, frequency-dependent distortion indicator for wideband signals including music; superior to traditional THD+N for non-stationary signals

## Implementation Approach

- New class `CoherenceAnalyzer` in `daw-core/…/analysis/`
- Welch's method: average cross-spectral density and auto-spectral densities over overlapping segments
- Magnitude-squared coherence: `γ²(f) = |Sxy(f)|² / (Sxx(f) · Syy(f))`
- Requires paired input/output buffers (pre/post effect chain)
- Returns per-frequency coherence values (0.0 = fully distorted, 1.0 = perfectly linear)
- Applications: effect chain quality assessment, master bus distortion monitoring

## Extends

`FftUtils`
