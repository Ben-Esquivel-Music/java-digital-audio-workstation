---
title: "Clip Crossfade Editing Between Overlapping Clips"
labels: ["enhancement", "editing", "arrangement-view", "audio-engine"]
---

# Clip Crossfade Editing Between Overlapping Clips

## Motivation

When two audio clips on the same track overlap, the transition between them should be a smooth crossfade rather than an abrupt cut. Professional DAWs allow users to create and edit crossfades by dragging clip edges to overlap, then adjusting the crossfade duration and curve shape. The `CrossfadeGenerator` and `CrossfadeCurve` types exist in the mastering module for album crossfades, but arrangement-level crossfades between clips on the same track are not implemented. Without crossfades, editing workflows that involve comping takes or assembling recordings from multiple segments produce audible clicks and pops at edit points.

## Goals

- Automatically create a crossfade when two clips on the same track overlap
- Display the crossfade region visually on the track with overlapping waveforms
- Allow resizing the crossfade by dragging its edges
- Support crossfade curve types: linear, equal-power, S-curve
- Allow selecting the crossfade type via a context menu or properties panel
- Process crossfades in the audio engine during real-time playback
- Make crossfade edits undoable
- Support a default crossfade duration configurable in settings

## Non-Goals

- Crossfades between clips on different tracks
- Automatic crossfade creation when splitting a clip (should be manual)
- Spectral crossfading (frequency-domain blending)
