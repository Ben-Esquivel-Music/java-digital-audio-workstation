---
title: "Support OGG, MP3, and AAC Audio Export Formats"
labels: ["enhancement", "export", "core"]
---

# Support OGG, MP3, and AAC Audio Export Formats

## Motivation

User story 011 describes multi-format audio export with dithering and sample rate conversion. The `DefaultAudioExporter` currently supports WAV and FLAC export, but its `switch` statement explicitly throws `UnsupportedOperationException` for OGG, MP3, and AAC with the message "not yet implemented. Future versions will use the FFM API (JEP 454) for native codec integration." The `AudioExportFormat` enum in the SDK already defines these formats, and the export UI in `ExportService` allows users to select them — but the export fails at runtime. Users who need to deliver compressed audio (e.g., MP3 for streaming platforms, OGG for game engines, AAC for Apple ecosystem distribution) must export as WAV and convert externally, which breaks the mastering-to-delivery workflow.

## Goals

- Implement `OggVorbisExporter` to encode audio data to OGG Vorbis format, using FFM bindings to the native libvorbis/libogg libraries or a pure-Java Vorbis encoder
- Implement `Mp3Exporter` to encode audio data to MP3 format, using FFM bindings to LAME or a compatible encoder
- Implement `AacExporter` to encode audio data to AAC format, using FFM bindings to a native AAC encoder (e.g., FDK-AAC)
- Wire these exporters into the `DefaultAudioExporter.export()` switch statement replacing the `UnsupportedOperationException`
- Support configurable quality/bitrate settings for lossy formats (e.g., MP3 128/192/256/320 kbps, OGG quality 0–10)
- Apply dithering before lossy encoding when the source bit depth exceeds the target format's precision
- Show export progress and completion notification via the existing `ExportProgressListener`
- Include metadata (title, artist, album) in the exported files where the format supports it

## Non-Goals

- Lossless compression beyond FLAC (e.g., ALAC, WavPack)
- Real-time streaming export
- DRM-protected export formats
