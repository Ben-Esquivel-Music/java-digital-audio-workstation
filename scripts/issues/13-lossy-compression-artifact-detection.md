## Description

Add analysis capabilities to detect and classify lossy compression artifacts in audio files. This tool identifies the telltale statistical signatures of MP3/AAC encoding — complementing the lossless integrity checker with more detailed forensic analysis.

## AES Research References

- [MP3 compression classification through audio analysis statistics](docs/research/AES/MP3_compression_classification_through_audio_analysis_statistics.pdf) (2022) — Develops statistical features for classifying MP3 compression levels from audio analysis; identifies spectral band energy ratios and temporal envelope characteristics unique to different MP3 bitrates
- [Comparing the Effect of Audio Coding Artifacts on Objective Quality Measures and on Subjective Ratings](docs/research/AES/Comparing_the_Effect_of_Audio_Coding_Artifacts_on_Objective_Quality_Measures_and_on_Subjective_Ratings.pdf) (2018) — Correlates objective measurements with perceived quality degradation from coding artifacts

## Implementation Approach

- New class `CompressionArtifactDetector` in `daw-core/…/analysis/`
- Spectral band energy ratio analysis across critical bands
- Pre-echo detection in the time domain (characteristic of block-based codecs)
- "Birdie" artifact detection: isolated narrowband tonal artifacts from codec quantization
- Statistical classification of probable encoding format and bitrate
- Returns: detected codec type, estimated bitrate, artifact locations (time/frequency), severity score
- Uses existing `FftUtils` and `SpectrumAnalyzer`

## Extends

`FftUtils`, `SpectrumAnalyzer`
