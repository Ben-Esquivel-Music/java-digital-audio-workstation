# Enhancement: Look-Ahead Limiter with True Peak Detection

## Summary

Enhance the existing `LimiterProcessor` with look-ahead capability and ITU-R BS.1770 true peak detection. Look-ahead limiting prevents transient overshoot by anticipating peaks before they occur, while true peak metering detects intersample peaks that exceed 0 dBFS — critical for broadcast and streaming compliance.

## Motivation

The current `LimiterProcessor` operates as a simple brickwall limiter without look-ahead or true peak awareness. Modern mastering requires look-ahead limiting to transparently catch transients, and true peak detection to prevent intersample clipping that occurs during D/A conversion or lossy encoding. Streaming platforms (Spotify, Apple Music, YouTube) all require true peak compliance, making this essential for professional deliverables.

## Research Sources

- [Mastering Techniques](../research/mastering-techniques.md) — Core Technique #4: "Brick-wall limiting for loudness maximization" and "True Peak measurement to prevent intersample clipping"
- [Mastering Techniques](../research/mastering-techniques.md) — High Priority: "Look-ahead limiter with true-peak detection"
- [Mastering Techniques](../research/mastering-techniques.md) — Core Technique #8: Platform targets require true peak compliance

## Sub-Tasks

- [ ] Implement look-ahead buffer (configurable 1–5ms) in `LimiterProcessor` for transient anticipation
- [ ] Implement gain smoothing with attack/release envelope for transparent limiting
- [ ] Implement ITU-R BS.1770-4 true peak detection using 4× oversampling and interpolation
- [ ] Add true peak ceiling parameter (typically −1.0 dBTP for streaming, −0.3 dBTP for broadcast)
- [ ] Add true peak metering output separate from sample peak metering
- [ ] Implement latency compensation reporting for the look-ahead delay
- [ ] Add auto-release mode that adapts release time based on signal dynamics
- [ ] Add unit tests for true peak detection accuracy against known intersample peak test signals
- [ ] Add unit tests for look-ahead limiting transient handling
- [ ] Add unit tests for gain reduction transparency (verify minimal distortion)
- [ ] Add platform-specific true peak ceiling presets (Spotify −1.0 dBTP, Apple −1.0 dBTP, broadcast −0.5 dBTP)

## Affected Modules

- `daw-core` (`dsp/LimiterProcessor`, `analysis/LevelMeter`)
- `daw-sdk` (`visualization/LevelData` — extend for true peak data)

## Priority

**High** — Essential for streaming and broadcast compliance
