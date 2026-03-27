---
title: "Track Stem Export (Bounce Individual Tracks)"
labels: ["enhancement", "export", "mixing"]
---

# Track Stem Export (Bounce Individual Tracks)

## Motivation

The `TrackBouncer` class exists in the core export module for bouncing individual tracks to audio files. However, there is no UI for stem export. Music producers frequently need to export individual tracks or groups of tracks as stems for collaboration, remix contests, live performance backing tracks, or delivery to mixing/mastering engineers. Stem export should process each track through its mixer channel (applying volume, pan, and insert effects) and write the result to a separate file. Batch export of all tracks at once is essential to avoid manual repetition.

## Goals

- Add a Stem Export dialog accessible from File > Export Stems
- Allow selecting which tracks to export (checkboxes for each track, "Select All" / "Deselect All")
- Export each selected track as a separate audio file, processed through its mixer channel effects
- Support naming conventions: track name, project name prefix, numbering
- Allow choosing the same format options as the main export (WAV, FLAC, sample rate, bit depth)
- Support exporting bus/group stems in addition to individual tracks
- Export all stems with the same length (padded to the project duration) for easy alignment
- Show a progress bar during batch export

## Non-Goals

- Exporting stems with different format settings per track
- Real-time bounce (always offline/faster-than-real-time)
- Stem separation from a mixed file (AI feature — separate story)
