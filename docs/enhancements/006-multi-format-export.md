# Enhancement: Multi-Format Audio Export with Dithering and Sample Rate Conversion

## Summary

Implement a comprehensive audio export pipeline supporting multiple formats (WAV, FLAC, OGG, MP3, AAC), automatic sample rate conversion, bit-depth reduction with dithering, and batch export for multiple format targets simultaneously.

## Motivation

Professional mastering requires delivering audio in multiple formats for different distribution channels (CD at 44.1kHz/16-bit, streaming at 44.1kHz/24-bit, hi-res at 96kHz/24-bit). Dithering is mandatory when reducing bit depth to avoid quantization distortion. The research identifies multi-format export with automatic sample rate conversion and dithering as an immediate priority for the DAW.

## Research Sources

- [Mastering Techniques](../research/mastering-techniques.md) — Core Technique #9: "Formats: WAV (16/24-bit, 44.1/48/96 kHz), MP3, AAC, FLAC" and "Dithering when reducing bit depth"
- [Mastering Techniques](../research/mastering-techniques.md) — High Priority: "Multi-format export with dithering"
- [Research README](../research/README.md) — Immediate Priority #4: "Multi-format audio export with dithering"
- [Audio Development Tools](../research/audio-development-tools.md) — "FFmpeg → Audio format conversion, codec support" and "libsndfile → Robust audio file I/O"

## Sub-Tasks

- [ ] Design `AudioExporter` interface in `daw-sdk` with format, sample rate, and bit depth configuration
- [ ] Implement WAV export (8/16/24/32-bit integer, 32/64-bit float) in `daw-core`
- [ ] Implement FLAC lossless export in `daw-core`
- [ ] Implement OGG Vorbis export with configurable quality in `daw-core`
- [ ] Evaluate FFmpeg JNI integration vs. pure Java libraries (javax.sound, JAVE2) for MP3/AAC encoding
- [ ] Implement sample rate conversion (SRC) with configurable quality (sinc interpolation)
- [ ] Implement TPDF dithering for bit-depth reduction (24→16, 32→16, 32→24)
- [ ] Implement noise-shaped dithering option for enhanced perceptual quality
- [ ] Create `ExportPreset` records for common format targets (CD, Streaming, Hi-Res, Vinyl)
- [ ] Implement batch export that renders to multiple format targets in a single operation
- [ ] Add metadata embedding during export (title, artist, album, ISRC if available)
- [ ] Add export validation reporting (loudness, true peak, format compliance)
- [ ] Add unit tests for dithering noise floor characteristics
- [ ] Add unit tests for sample rate conversion accuracy (frequency response, aliasing)
- [ ] Add unit tests for round-trip format accuracy (export → re-import → compare)

## Affected Modules

- `daw-sdk` (new `AudioExporter` interface, `ExportPreset` records)
- `daw-core` (new `export/` package)
- `daw-app` (export dialog UI)

## Priority

**Immediate** — Core deliverable feature
