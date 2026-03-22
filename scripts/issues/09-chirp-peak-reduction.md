## Description

Add a peak reduction processor that reduces crest factor (peak-to-average ratio) by spreading signal peaks across time using ultra-short chirp modulation. Unlike traditional limiting which clips or compresses peaks, chirp spreading redistributes peak energy without audible artifacts — enabling louder masters without distortion.

## AES Research References

- [Audio Peak Reduction Using Ultra-Short Chirps](docs/research/AES/Audio_Peak_Reduction_Using_Ultra-Short_Chirps.pdf) (2022) — Presents a method for reducing audio signal peaks by convolving with ultra-short chirps; demonstrates measurable crest factor reduction with inaudible artifacts

## Implementation Approach

- New class `ChirpPeakReducer implements AudioProcessor` in `daw-core/…/dsp/`
- Detect peaks above a configurable threshold using envelope following
- Apply ultra-short chirp convolution (1–5 ms) to spread peak energy temporally
- Configurable chirp bandwidth and duration
- Operates before the limiter in the mastering chain for transparent loudness maximization
- Parameters: threshold, chirp duration, chirp bandwidth, mix

## Extends

`AudioProcessor`, complements `LimiterProcessor`
