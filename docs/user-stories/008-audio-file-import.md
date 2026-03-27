---
title: "Audio File Import via Drag-and-Drop and File Menu"
labels: ["enhancement", "ui", "arrangement-view", "file-io"]
---

# Audio File Import via Drag-and-Drop and File Menu

## Motivation

Users need to import existing audio files (WAV, FLAC, MP3, AIFF, OGG) into their project. The `BrowserPanel` shows a file system tree and a samples list, but there is no mechanism to drag a file from the browser onto a track or use File > Import to add audio to the timeline. Importing audio is one of the most common DAW operations — artists import stems, samples, reference tracks, and field recordings constantly. Without this, the DAW can only work with audio it records internally.

## Goals

- Support drag-and-drop from the `BrowserPanel` file system tree onto a track in the arrangement view
- Support drag-and-drop from the OS file manager onto the arrangement view
- Provide a File > Import Audio menu item that opens a file chooser
- Automatically convert imported files to the project's sample rate and bit depth if they differ
- Create an `AudioClip` at the drop position (or playhead position for menu import)
- Create a new track if the file is dropped below existing tracks
- Show a progress indicator for large file imports
- Support importing multiple files at once (each to a separate track)

## Non-Goals

- Video file import
- MIDI file import (separate feature — `MidiFileExporter` exists but no importer)
- Sample rate conversion quality options (use a sensible default)
