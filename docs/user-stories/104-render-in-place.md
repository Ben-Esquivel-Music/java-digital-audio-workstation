---
title: "Render-in-Place for Tracks with Insert Effects and Virtual Instruments"
labels: ["enhancement", "audio-engine", "mixer", "editing"]
---

# Render-in-Place for Tracks with Insert Effects and Virtual Instruments

## Motivation

Track freeze (story 035) renders a track's audio with its insert effects and replaces the live track with a frozen audio file to reduce CPU load. However, it is a binary operation — the track is either frozen (no editing) or unfrozen (full CPU load). There is no way to permanently commit insert effects to audio while keeping the track editable, or to convert a MIDI track with SoundFont synthesis into a standard audio track.

"Render-in-place" (also called "bounce in place" or "commit") is a common workflow in professional DAWs (Logic Pro, Pro Tools, Ableton, Reaper). It creates a new audio clip containing the fully processed output of a track — including insert effects, virtual instruments, and automation — and optionally replaces the original track or places the rendered audio on a new track. Unlike freeze, the result is a permanent, editable audio clip. This is essential for:

- Converting MIDI tracks to audio for further audio-level editing (time-stretching, fading, clip-level effects)
- Committing CPU-heavy effects permanently to free resources
- Creating "stems" without leaving the arrangement view

## Goals

- Add a "Render in Place" action to the track context menu (right-click on a track)
- Render the track's audio output through its full signal chain: clips → insert effects → volume/pan → automation — for the duration of the track's content (or a user-selected time range)
- For MIDI tracks, render through the SoundFont renderer to produce audio
- Create a new `AudioClip` containing the rendered float audio data and place it on either: (a) a new audio track below the original, or (b) the original track, replacing the source clips (user choice via dialog)
- Offer options in a render dialog: "Replace original clips", "Create new track", "Include automation", "Include sends" (pre-fader send contributions)
- Use the same `RenderPipeline` as the offline export path (story 102) to ensure consistency
- The render operation should be undoable — undo restores the original track state
- Show a progress dialog during rendering for long tracks
- Add tests verifying: (1) rendered audio matches the expected output, (2) MIDI tracks produce audio clips, (3) undo restores original state

## Non-Goals

- Real-time rendering (render-in-place always runs offline at maximum speed)
- Rendering multiple tracks simultaneously into a single stem (that is stem export, story 029)
- Rendering with master bus effects (render-in-place is per-track, pre-master)
- Automatic deletion of the original MIDI data after rendering
