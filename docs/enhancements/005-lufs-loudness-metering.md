# Enhancement: LUFS Loudness Metering with Platform-Specific Presets

## Summary

Enhance the existing `LoudnessMeter` with full ITU-R BS.1770 LUFS measurement (integrated, short-term, momentary, and loudness range) and add platform-specific loudness target presets for major streaming services. Include loudness history visualization data and export validation against target loudness.

## Motivation

The existing `LoudnessMeter` and `LoudnessData` provide basic loudness measurement. Professional mastering requires the complete ITU-R BS.1770 loudness measurement suite with integrated, short-term (3s window), and momentary (400ms window) LUFS readings, plus loudness range (LRA). Each streaming platform normalizes to different targets, and engineers need preset-based guidance and export validation to ensure masters translate correctly across all platforms.

## Research Sources

- [Mastering Techniques](../research/mastering-techniques.md) — Core Technique #8: "LUFS (Loudness Units Full Scale) — integrated, short-term, and momentary" and "Platform targets: Spotify (−14 LUFS), Apple Music (−16 LUFS), YouTube (−14 LUFS)"
- [Mastering Techniques](../research/mastering-techniques.md) — High Priority: "LUFS loudness meter with platform presets"
- [Research README](../research/README.md) — Immediate Priority #3: "LUFS loudness metering"
- [Mastering Techniques](../research/mastering-techniques.md) — Genre loudness table (Pop/EDM: −8 to −11, Rock: −10 to −13, Jazz/Classical: −16 to −20)

## Sub-Tasks

- [ ] Implement ITU-R BS.1770-4 K-weighting filter (high-shelf + high-pass) in `LoudnessMeter`
- [ ] Implement momentary loudness measurement (400ms sliding window)
- [ ] Implement short-term loudness measurement (3-second sliding window)
- [ ] Implement integrated loudness measurement (gated, full program duration)
- [ ] Implement loudness range (LRA) measurement per EBU R128
- [ ] Create `LoudnessTarget` record/enum with platform presets (Spotify −14, Apple −16, YouTube −14, Amazon −14, Tidal −14, CD −9 to −13)
- [ ] Add genre-specific loudness reference presets (Pop/EDM, Rock, Jazz/Classical, Hip-Hop/R&B)
- [ ] Add loudness history data output for time-based visualization (loudness over time graph)
- [ ] Add export validation method that checks integrated loudness and true peak against a selected platform target
- [ ] Extend `LoudnessData` in `daw-sdk` to include momentary, short-term, integrated, and LRA fields
- [ ] Add unit tests for K-weighting filter frequency response accuracy
- [ ] Add unit tests for LUFS measurement against ITU-R BS.1770 reference test signals
- [ ] Add unit tests for loudness range calculation accuracy

## Affected Modules

- `daw-core` (`analysis/LoudnessMeter`)
- `daw-sdk` (`visualization/LoudnessData`)

## Priority

**High** — Essential for streaming-ready masters
