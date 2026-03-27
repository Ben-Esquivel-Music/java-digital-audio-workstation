---
title: "Reference Track A/B Comparison"
labels: ["enhancement", "mixing", "mastering", "ui"]
---

# Reference Track A/B Comparison

## Motivation

Professional mixing and mastering engineers frequently compare their work against commercial reference tracks to ensure competitive quality in terms of loudness, tonal balance, stereo width, and dynamics. The DAW should support importing a reference track that plays alongside the mix without being processed through the master bus effects chain. Users need an instant A/B toggle to switch between their mix and the reference, with automatic volume matching (so loudness differences don't bias the comparison). This workflow is described in the mastering research documents as essential for quality assurance.

## Goals

- Allow importing a reference track via a dedicated "Add Reference Track" option
- Display the reference track as a special track type that bypasses the mixer effects chain
- Provide an A/B toggle button in the transport bar to switch between mix and reference
- Automatically level-match the reference to the mix output (based on integrated LUFS)
- Show waveform and spectrum comparison between the mix and reference
- Support multiple reference tracks with a selector to choose the active one
- Allow looping a section of the reference track independently of the project loop
- Mute the reference track during export

## Non-Goals

- Automatic EQ matching to the reference (AI feature — separate story)
- Reference track library management
- Streaming service integration (importing from Spotify, Apple Music, etc.)
