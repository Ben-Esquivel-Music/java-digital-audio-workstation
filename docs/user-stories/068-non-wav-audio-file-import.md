---
title: "Support FLAC, AIFF, OGG, and MP3 Audio File Import"
labels: ["enhancement", "file-io", "core"]
---

# Support FLAC, AIFF, OGG, and MP3 Audio File Import

## Motivation

User story 008 describes audio file import via drag-and-drop and file menu, and user story 064 describes placing imported clips in the arrangement view. Both are implemented for WAV files — `AudioFileImporter` reads WAV via `WavFileReader`, creates an `AudioClip`, and places it on a track. However, the `SupportedAudioFormat` enum declares five formats (WAV, FLAC, MP3, AIFF, OGG) but `AudioFileImporter.importFile()` explicitly throws `IllegalArgumentException("Only WAV files are currently supported for import")` for any non-WAV format. The file chooser in `MainController.onImportAudioFile()` only shows a "WAV Files" extension filter, and the drag-and-drop handler `installArrangementCanvasDragDrop()` only accepts files ending in `.wav`. Users working with FLAC stems from mastering sessions, AIFF files from Logic Pro exports, or MP3 reference tracks cannot import them into the DAW without first converting them externally.

## Goals

- Implement `FlacFileReader` to decode FLAC files into `float[][]` audio data, using the FLAC framing structure already understood by `FlacExporter`
- Implement `AiffFileReader` to decode AIFF/AIFC files into audio data (AIFF is widely used in professional Mac-based workflows)
- Implement `OggVorbisFileReader` and `Mp3FileReader` for lossy format import — these may use FFM bindings (JEP 454) to native decoder libraries or pure-Java decoding
- Update `AudioFileImporter.importFile()` to dispatch to the appropriate reader based on `SupportedAudioFormat` instead of rejecting non-WAV formats
- Update the file chooser extension filters to include all supported formats (WAV, FLAC, AIFF, AIF, OGG, MP3)
- Update the drag-and-drop handler to accept all supported audio file extensions, not just `.wav`
- Perform automatic sample rate conversion when the imported file's sample rate differs from the project sample rate (existing `SampleRateConverter` already handles this for WAV)
- Show a notification on import with the file name, format, duration, and any conversion applied

## Non-Goals

- Batch import of multiple files at once
- AAC or WMA format import
- Import of multi-channel surround audio files (beyond stereo)
