## Description

Enhance the `SpectrumAnalyzer` with log-frequency-symmetric fractional-octave smoothing. Standard rectangular or triangular smoothing in the linear frequency domain produces asymmetric smoothing on log-frequency displays, over-smoothing high frequencies relative to low frequencies. This enhancement preserves visual accuracy of spectral displays.

## AES Research References

- [A Generalized Method for Fractional-Octave Smoothing of Transfer Functions that Preserves Log-Frequency Symmetry](docs/research/AES/A_Generalized_Method_for_Fractional-Octave_Smoothing_of_Transfer_Functions_that_Preserves_Log-Frequency_Symmetry.pdf) (2017) — Presents a generalized method using variable-width rectangular or Gaussian windows that scale with frequency, producing visually symmetric smoothing on log-frequency axes; applicable to 1/3-octave, 1/6-octave, and arbitrary fractional-octave widths

## Implementation Approach

- New method `smoothFractionalOctave(double[] magnitudes, double sampleRate, double octaveFraction)` in `SpectrumAnalyzer` or new utility class `SpectrumSmoother`
- Variable-width smoothing window that scales proportionally to center frequency
- Supports 1/1, 1/3, 1/6, 1/12, 1/24 octave smoothing
- Operates on magnitude spectrum in dB domain for correct log-frequency behavior
- Preserves existing raw spectrum output; smoothing is an optional post-processing step

## Extends

`SpectrumAnalyzer`, `FftUtils`
