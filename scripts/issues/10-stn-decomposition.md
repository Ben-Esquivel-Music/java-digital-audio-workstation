## Description

Add a spectral decomposition engine that separates an audio signal into three components: sinusoidal (tonal), transient (percussive), and noise (stochastic). This three-way decomposition enables advanced editing workflows — independently processing or visualizing the tonal, rhythmic, and noise content of a signal.

## AES Research References

- [Enhanced Fuzzy Decomposition of Sound Into Sines, Transients, and Noise](docs/research/AES/Enhanced_Fuzzy_Decomposition_of_Sound_Into_Sines,_Transients,_and_Noise.pdf) (2023) — Proposes a fuzzy STN decomposition using median filtering of spectrograms with soft masking for artifact-free separation; the "enhanced" variant improves temporal resolution for transients while preserving tonal continuity
- [Transient Detection Methods for Audio Coding](docs/research/AES/Transient_Detection_Methods_for_Audio_Coding.pdf) (2023) — Complements STN decomposition with specialized transient detection for block-switching decisions

## Implementation Approach

- New class `StnDecomposer` in `daw-core/…/analysis/`
- STFT analysis using the existing `FftUtils` with configurable window size and overlap
- Horizontal (time) median filtering of the spectrogram to extract tonal component
- Vertical (frequency) median filtering to extract transient component
- Residual (original − tonal − transient) yields the noise component
- Soft (fuzzy) masking using Wiener-type gain functions to avoid binary artifacts
- Returns three separate `float[][]` buffers for sines, transients, and noise
- Applications: transient shaping, de-noising, harmonic editing, visualization

## Extends

`FftUtils`
