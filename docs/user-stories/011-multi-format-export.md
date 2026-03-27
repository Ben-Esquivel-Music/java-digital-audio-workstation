---
title: "Multi-Format Audio Export with Dithering and Sample Rate Conversion"
labels: ["enhancement", "export", "mastering"]
---

# Multi-Format Audio Export with Dithering and Sample Rate Conversion

## Motivation

The `DefaultAudioExporter`, `WavExporter`, `SampleRateConverter`, `TpdfDitherer`, and `NoiseShapedDitherer` classes exist in the core module, but there is no unified export dialog in the UI that lets users choose output format, sample rate, bit depth, dithering method, and loudness target. Users need a clear export workflow: select a range or the entire project, choose the output format (WAV, FLAC, MP3), configure quality settings, and export. The `ExportPreset` and `AudioExportConfig` SDK types suggest this was planned but never connected to the UI. Without a polished export workflow, users cannot produce deliverables from their sessions.

## Goals

- Add an Export dialog accessible from File > Export or a toolbar button
- Support export formats: WAV (16/24/32-bit), FLAC, and MP3
- Allow selection of output sample rate (44.1, 48, 88.2, 96 kHz) with automatic sample rate conversion
- Provide dithering options (None, TPDF, Noise-Shaped) when reducing bit depth
- Support exporting the full project or a selected time range
- Include loudness normalization to a target LUFS (e.g., -14 LUFS for streaming)
- Show a progress bar during export
- Validate the exported file against the configured loudness target and display a report
- Support export presets (Spotify, Apple Music, CD, Hi-Res) using `ExportPreset`

## Non-Goals

- Video export or muxing audio with video
- Batch export of individual tracks as stems (separate feature)
- Real-time bounce (export always runs faster than real-time)
