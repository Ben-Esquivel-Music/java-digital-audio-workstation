## Description

Add an automated phase alignment and polarity detection tool for multitrack sessions. Phase issues between microphones recording the same source (e.g., drum kit, stereo miking) cause comb-filtering and tonal degradation. This tool detects inter-track time offsets and polarity inversions, and recommends corrections.

## AES Research References

- [Detection of phase alignment and polarity in drum tracks](docs/research/AES/Detection_of_phase_alignment_and_polarity_in_drum_tracks.pdf) (2023) — Presents algorithms for automated detection of phase misalignment and polarity inversion in multi-microphone drum recordings using cross-correlation and spectral coherence
- [Spectral and spatial perceptions of comb-filtering for sound reinforcement applications](docs/research/AES/Spectral_and_spatial_perceptions_of_comb-filtering_for_sound_reinforcement_applications..pdf) (2022) — Perceptual consequences of phase/comb-filtering artifacts; validates the importance of phase alignment tools

## Implementation Approach

- New class `PhaseAlignmentAnalyzer` in `daw-core/…/analysis/`
- Cross-correlation between track pairs to find the optimal time offset (in samples)
- Polarity detection: compare cross-correlation peak at offset 0 vs. inverted signal
- Spectral coherence measurement to quantify the severity of phase cancellation per frequency band
- Returns per-pair results: optimal delay (samples), polarity recommendation (normal/inverted), coherence score
- Integration with `CorrelationMeter` for real-time display
- Applications: drum overhead alignment, stereo mic correction, DI/amp phase matching

## Extends

`CorrelationMeter`, `FftUtils`
