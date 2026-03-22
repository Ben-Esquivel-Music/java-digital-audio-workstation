## Description

Add a specialized transient detector optimized for real-time block-switching decisions in the audio engine. When a transient is detected, the engine can switch to shorter processing blocks to improve temporal resolution — reducing pre-echo and smearing artifacts in time-frequency processing.

## AES Research References

- [Transient Detection Methods for Audio Coding](docs/research/AES/Transient_Detection_Methods_for_Audio_Coding.pdf) (2023) — Evaluates multiple transient detection methods (spectral flux, temporal energy ratio, attack time analysis) for audio coding block-switching; recommends combined spectral-temporal approaches for lowest missed-detection rate
- [Real-Time Audio Pattern Detection for Smart Musical Instruments](docs/research/AES/Real-Time_Audio_Pattern_Detection_for_Smart_Musical_Instruments.pdf) (2026) — Real-time audio pattern detection applicable to live input monitoring and adaptive processing

## Implementation Approach

- New class `TransientDetector` in `daw-core/…/analysis/`
- Dual-domain detection: temporal energy ratio (short/long window energy ratio) combined with spectral flux
- Low-latency design: operates on individual audio blocks without look-ahead
- Binary output: transient detected (switch to short block) or not (continue with long block)
- Configurable sensitivity threshold
- Complements the existing `OnsetDetector` which is designed for offline analysis; this is optimized for real-time block-by-block decisions

## Extends

`OnsetDetector` (shared algorithms), `FftUtils`
