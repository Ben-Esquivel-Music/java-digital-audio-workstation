## Description

Add a multitrack analysis engine that extracts low-level audio features from each track in a session and provides statistical summary across the mix. Enables comparison of mix characteristics across different versions or against reference mixes — supporting "how does my mix compare?" workflows.

## AES Research References

- [Variation in Multitrack Mixes: Analysis of Low-level Audio Signal Features](docs/research/AES/Variation_in_Multitrack_Mixes__Analysis_of_Low-level_Audio_Signal_Features.pdf) (2016) — Defines a set of low-level audio features for characterizing multitrack mixes: spectral centroid, spectral flux, RMS level, crest factor, spectral spread, and stereo width; applies these to analyze variation across multiple mixes of the same song
- [Exploring trends in audio mixes and masters: Insights from a dataset analysis](docs/research/AES/Exploring_trends_in_audio_mixes_and_masters__Insights_from_a_dataset_analysis.pdf) (2024) — Large-scale analysis of mix/master trends using audio features; provides benchmarks for feature values across genres

## Implementation Approach

- New class `MixFeatureAnalyzer` in `daw-core/…/analysis/`
- Per-track feature extraction: RMS level, peak level, crest factor, spectral centroid, spectral spread, spectral flux, stereo width, loudness (LUFS)
- Session-level aggregate statistics: feature distributions, per-band energy ratios, dynamic range
- Comparison mode: compare two mixes feature-by-feature with delta reporting
- Leverages existing `SpectrumAnalyzer`, `LevelMeter`, `LoudnessMeter`, `CorrelationMeter`
- Returns structured `MixFeatureReport` record with per-track and aggregate metrics

## Extends

`SpectrumAnalyzer`, `LevelMeter`, `LoudnessMeter`, `CorrelationMeter`
