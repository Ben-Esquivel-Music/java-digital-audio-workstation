## Description

Add a bandwidth extension processor that restores high-frequency content lost to lossy compression or band-limited recording. Uses spectral analysis to detect the cutoff frequency and generates plausible high-frequency content via harmonic extrapolation and noise shaping — improving perceived quality of degraded source material.

## AES Research References

- [Perceptually Controlled Selection of Alternatives for High-Frequency Content in Intelligent Gap Filling](docs/research/AES/Perceptually_Controlled_Selection_of_Alternatives_for_High-Frequency_Content_in_Intelligent_Gap_Filling.pdf) (2025) — Presents perceptually-controlled bandwidth extension using alternative high-frequency candidates selected for tonal alignment with the original content; demonstrates improved quality over simple spectral band replication
- [Sound Board: High-Resolution Audio](docs/research/AES/Sound_Board__High-Resolution_Audio.pdf) (2015) — Context on high-resolution audio and the perceptual significance of bandwidth

## Implementation Approach

- New class `BandwidthExtender implements AudioProcessor` in `daw-core/…/dsp/`
- Cutoff detection: use spectral analysis to identify the high-frequency rolloff point (from `LosslessIntegrityChecker`)
- Spectral band replication (SBR): mirror and transpose spectral content from below the cutoff to fill the gap above
- Harmonic extrapolation: generate harmonics of detected tonal components above the cutoff
- Noise shaping: shape white noise to match the spectral envelope extrapolation above the cutoff
- Perceptual filtering: post-filter generated content to match expected spectral slope
- Parameters: target bandwidth, generation method (SBR / harmonic / noise), intensity, blend

## Extends

`FftUtils`, `SpectrumAnalyzer`, `BiquadFilter`
