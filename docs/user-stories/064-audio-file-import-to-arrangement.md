---
title: "Audio File Import with Clip Placement in Arrangement View"
labels: ["enhancement", "ui", "arrangement-view", "file-io"]
---

# Audio File Import with Clip Placement in Arrangement View

## Motivation

User story 008 describes audio file import via drag-and-drop and file menu. The `daw-core` module has `AudioFileImporter`, `WavFileReader`, `AudioImportResult`, and `SupportedAudioFormat` — a complete import pipeline that can read WAV files and convert audio data. The `BrowserPanel` displays a file tree and sample tabs. However, importing an audio file does not create a visible clip on a track in the arrangement view. There is no drag-and-drop from the browser panel or the OS file manager onto a track lane, and the File menu import does not place an `AudioClip` at the playhead position on a selected track. Users can import audio conceptually but cannot see or interact with the result in their session.

## Goals

- Add a "Import Audio File" menu item (or toolbar action) that opens a file chooser, imports the selected file via `AudioFileImporter`, creates an `AudioClip` with the imported audio data, and places it on the selected track at the current playhead position
- Support drag-and-drop from the `BrowserPanel` file tree onto a track lane in the arrangement view to import and place the audio file
- Support drag-and-drop from the OS file manager onto the arrangement view to import and place audio files
- Show an import progress indicator for large files using `ImportProgressListener`
- After import, render the new clip in the arrangement view with a waveform preview
- Make the import operation undoable (undo removes the imported clip from the track)
- Support importing WAV files (the formats currently handled by `AudioFileImporter`); design the UI and clip-placement flow so that additional `SupportedAudioFormat` types (AIFF, FLAC, OGG, MP3) can be enabled in a follow-up story
- Show a notification on successful import with the file name and duration

## Non-Goals

- Automatic sample rate conversion during import (import at native rate, convert on playback)
- Batch import of multiple files at once
- Audio file format conversion export
