---
title: "Album Sequencing and Assembly View"
labels: ["enhancement", "mastering", "ui", "export"]
---

# Album Sequencing and Assembly View

## Motivation

The `AlbumSequence` and `CrossfadeGenerator` classes exist in the core mastering module, and `AlbumTrackEntry` and `CrossfadeCurve` types are defined in the SDK. However, there is no UI for assembling tracks into an album sequence. Mastering engineers need to arrange songs in the final playback order, set gaps between tracks, add crossfades, and export the sequence as a single continuous file or as individual tracks with proper metadata. This is a standard mastering workflow described in the research documents but currently inaccessible to users.

## Goals

- Add an album assembly view with a horizontal track listing
- Support drag-and-drop reordering of album tracks
- Allow configuring the gap duration between each track (default: 2 seconds)
- Support crossfade transitions between adjacent tracks with configurable curves (linear, equal-power, S-curve)
- Show waveform previews for each album track
- Allow playback of the entire album sequence with seamless transitions
- Export the album as a single continuous WAV file or as individual tracks with correct timing
- Add metadata fields per album track (title, artist, ISRC code)
- Generate a track listing / cue sheet

## Non-Goals

- DDP (Disc Description Protocol) export for CD replication
- Red Book CD burning
- Vinyl-specific mastering adjustments (low-cut, de-essing)
