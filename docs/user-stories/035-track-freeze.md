---
title: "Track Freeze and Unfreeze for CPU Management"
labels: ["enhancement", "audio-engine", "ui", "performance"]
---

# Track Freeze and Unfreeze for CPU Management

## Motivation

When a project has many tracks with CPU-intensive effects (reverb, convolution, multiband compression), the audio engine may not keep up with real-time processing, causing dropouts and glitches. Track freezing is a standard DAW feature where a track's audio is rendered offline to a temporary file, and the track plays back the rendered file instead of processing effects in real-time. This frees CPU resources for other tracks. Unfreezing restores the original effects chain for further editing. This is essential for large sessions with heavy processing.

## Goals

- Add a "Freeze" button/option to each track's context menu
- When frozen, render the track's audio through its complete effects chain to a temporary WAV file
- Replace real-time effects processing with playback of the frozen file during playback
- Show a visual indicator (e.g., snowflake icon or blue tint) on frozen tracks
- Disable editing of frozen tracks (effects, volume, clip edits) until unfrozen
- Add an "Unfreeze" option to restore the original effects chain and remove the frozen file
- Show CPU usage before and after freezing in a CPU meter
- Support batch freeze (freeze all selected tracks at once)

## Non-Goals

- Automatic freeze/unfreeze based on CPU usage
- Freeze with tail (capturing reverb tail beyond clip boundaries)
- Render-in-place (permanently replacing clips with rendered audio)
