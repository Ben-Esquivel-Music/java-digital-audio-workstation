# Enhancement: Semantic Embedding Auto-EQ

## Summary

Implement an intelligent auto-equalization system that uses semantic embeddings to understand audio content type and apply context-aware EQ corrections. Unlike traditional spectral-matching EQ, this approach understands *what* is being processed (vocals, drums, guitar, full mix) and applies EQ adjustments informed by the semantic content, not just the frequency spectrum.

## Motivation

AES research demonstrates a deep neural network approach to automatic EQ that uses semantic embeddings to represent audio content. This enables EQ that adapts to content type — for example, applying different EQ strategies for a vocal recording vs. a drum bus vs. a full mix, even when the spectral characteristics are similar. A complementary paper shows that lightweight neural EQ using just 6 biquad filters can achieve effective real-time results, making this feasible as a real-time DAW feature rather than an offline process.

## Research Sources

- [AES Research Papers](../research/aes-research-papers.md) — "Automatic Audio Equalization with Semantic Embeddings" — **High** priority, data-driven EQ with semantic understanding
- [AES Research Papers](../research/aes-research-papers.md) — "Application of Low-Complexity Neural Equalizer for Adaptive Sound Equalization" — lightweight neural EQ using 6 biquad filters for real-time use
- [Research README](../research/README.md) — Future #4: "semantic auto-EQ"
- [Research README](../research/README.md) — "Intelligent EQ: AES research presents semantic-embedding-based auto-EQ that adapts to content type"

## Sub-Tasks

- [ ] Research and select an audio embedding model suitable for JVM integration (e.g., PANNs, VGGish, or custom model via ONNX Runtime)
- [ ] Design `AutoEqualizer` interface in `daw-sdk` for intelligent EQ suggestions
- [ ] Implement audio content classifier (identify content type: vocals, drums, bass, guitar, keys, full mix, etc.)
- [ ] Implement spectral feature extraction (spectral centroid, bandwidth, rolloff, MFCC) for EQ target estimation
- [ ] Implement target EQ curve generation based on content type and reference profiles
- [ ] Implement lightweight neural EQ using parameterized biquad filters (6-band, matching the AES paper architecture)
- [ ] Create reference EQ profiles for common content types and genres
- [ ] Implement "EQ match" mode: analyze a reference track and generate biquad parameters to match its spectral profile
- [ ] Add user-adjustable suggestion intensity (subtle → aggressive EQ correction)
- [ ] Integrate auto-EQ suggestions with existing `ParametricEqProcessor` (apply suggestions as initial EQ settings)
- [ ] Add unit tests for content classification accuracy
- [ ] Add unit tests for EQ curve generation reasonableness (no extreme boosts/cuts)
- [ ] Add integration test for end-to-end auto-EQ workflow
- [ ] Document auto-EQ capabilities, model training data sources, and accuracy expectations

## Affected Modules

- `daw-sdk` (new `ai/AutoEqualizer` interface)
- `daw-core` (new `ai/eq/` package, integration with `dsp/ParametricEqProcessor`)

## Priority

**Future** — Requires ML model integration infrastructure
