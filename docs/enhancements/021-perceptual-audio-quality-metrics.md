# Enhancement: Perceptual Audio Quality Metrics

## Summary

Implement automated perceptual audio quality assessment tools that evaluate mastered audio against quality metrics derived from AES research. This includes CNN-based virtual listener panels for predicting subjective quality ratings, objective quality metrics (VISQOL, POLQA-style), and integration into the export validation pipeline.

## Motivation

AES research presents two complementary approaches to automated quality assessment: (1) an audio quality metrics toolbox for standardized quality evaluation across content exchange and media management, and (2) CNN-based virtual listener panels that predict human quality ratings without expensive listening tests. Together, these enable automated quality gates in the mastering workflow — flagging potential issues (distortion, excessive compression, tonal imbalance, phase problems) before export.

## Research Sources

- [AES Research Papers](../research/aes-research-papers.md) — "An audio quality metrics toolbox for media assets management, content exchange, and dataset alignment" — automated quality assessment toolkit
- [AES Research Papers](../research/aes-research-papers.md) — "Establishing a Virtual Listener Panel for audio characterisation" — CNN-based quality prediction replacing human panels
- [AES Research Papers](../research/aes-research-papers.md) — "Exploring Perceptual Audio Quality Measurement on Stereo Processing using the Open Dataset of Audio Quality" — validated ODAQ framework for stereo quality
- [AES Research Papers](../research/aes-research-papers.md) — "Towards Robust Speech Quality Evaluation in Challenging Acoustic Conditions" — objective metrics for speech/voice content
- [Research README](../research/README.md) — Future #6: "Perceptual quality metrics for automated mastering validation"

## Sub-Tasks

- [ ] Design `QualityAnalyzer` interface in `daw-sdk` for audio quality assessment
- [ ] Implement basic objective quality metrics: signal-to-noise ratio (SNR), total harmonic distortion (THD), crest factor
- [ ] Implement spectral quality metrics: spectral flatness, spectral centroid deviation from target, bandwidth utilization
- [ ] Implement stereo quality metrics: correlation coefficient, stereo width consistency, mono compatibility score
- [ ] Implement dynamic range metrics: Peak-to-Loudness Ratio (PLR), dynamic range (DR) score
- [ ] Evaluate ONNX Runtime integration for running CNN-based virtual listener models in Java
- [ ] Implement virtual listener quality prediction model (if feasible via ONNX; otherwise, implement rule-based heuristic quality scoring)
- [ ] Create `QualityReport` record aggregating all metrics with pass/fail thresholds
- [ ] Integrate quality analysis into the export validation pipeline (pre-export quality check)
- [ ] Add quality metric comparison between pre-mastering and post-mastering versions
- [ ] Add quality metric history tracking across mastering revisions
- [ ] Add unit tests for each objective quality metric against known test signals
- [ ] Add unit tests for quality report generation and threshold evaluation
- [ ] Document quality metrics definitions, thresholds, and interpretation guide

## Affected Modules

- `daw-sdk` (new `analysis/QualityAnalyzer` interface, `analysis/QualityReport` record)
- `daw-core` (new `analysis/quality/` package)
- `daw-app` (quality report display, export validation UI)

## Priority

**Future** — Advanced analysis feature; basic metrics can be implemented early, CNN models require ML infrastructure
