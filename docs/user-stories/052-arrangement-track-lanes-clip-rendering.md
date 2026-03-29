---
title: "Arrangement View Track Lanes with Clip Rendering"
labels: ["enhancement", "ui", "arrangement-view", "core"]
---

# Arrangement View Track Lanes with Clip Rendering

## Motivation

The arrangement view currently shows a track list panel with per-track strips (mute, solo, arm, volume, pan) but the main content area is an empty placeholder label that currently reads "Add tracks to begin your session". Even after tracks are added and clips are created or imported, there is no visual representation of clips on a timeline. In every professional DAW (Pro Tools, Logic Pro, Ableton Live, Reaper), the arrangement view is the primary workspace where users see colored clip rectangles positioned on track lanes against a time axis. Without rendered clips, users cannot see where audio or MIDI regions are placed, cannot visually align parts, and cannot interact with their session in any meaningful way. The `AudioClip` model already stores `startBeat`, `durationBeats`, `fadeInBeats`, `fadeOutBeats`, and `audioData`, but none of this data is rendered visually in the arrangement.

## Goals

- Replace the arrangement placeholder with a scrollable canvas or node layout that renders horizontal track lanes aligned with the track list panel
- Render each `AudioClip` as a colored rectangle positioned at its `startBeat` with width proportional to `durationBeats`, respecting the current zoom level
- Draw a miniature waveform overview inside each audio clip rectangle using the clip's `audioData` buffer
- Show the clip name as an overlay label inside each clip rectangle
- Render MIDI clips as colored rectangles with a simplified piano-roll note preview
- Synchronize vertical scrolling between the track list panel and the arrangement canvas
- Synchronize horizontal scrolling and zoom with the existing `ZoomLevel`, `ViewportState`, and `ArrangementNavigator` infrastructure
- Update clip positions in real time when the model changes (add, remove, move, trim, split)

## Non-Goals

- Interactive clip dragging or editing (separate user stories cover split, trim, and fade handles)
- Automation lane rendering below tracks (separate feature)
- Waveform caching or LOD optimization for very large sessions
