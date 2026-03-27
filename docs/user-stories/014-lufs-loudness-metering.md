---
title: "LUFS Loudness Metering with Platform Targets"
labels: ["enhancement", "metering", "mastering", "ui"]
---

# LUFS Loudness Metering with Platform Targets

## Motivation

The `LoudnessMeter` class exists in `daw-core` and the `LoudnessDisplay` renders loudness data in the visualization panel, but the metering needs to be more prominent and actionable. Modern mastering requires LUFS metering with platform-specific targets (Spotify −14 LUFS, Apple Music −16 LUFS, YouTube −14 LUFS). The current loudness display shows a basic readout, but does not show integrated, short-term, and momentary loudness simultaneously. It also lacks a loudness history graph, loudness range (LRA) display, and true peak indicator. Engineers need at-a-glance loudness compliance information to master for streaming platforms.

## Goals

- Display integrated, short-term, and momentary LUFS readings simultaneously
- Show true peak level alongside LUFS readings
- Add a loudness range (LRA) indicator
- Provide platform target presets (Spotify, Apple Music, YouTube, CD, Broadcast)
- Color-code the meter to indicate whether the loudness is within the selected platform's target range
- Add a loudness history graph that plots integrated loudness over time
- Allow resetting the integrated loudness measurement
- Make the loudness meter available as both a visualization tile and a standalone floating window

## Non-Goals

- Dialogue-gated loudness (for broadcast/film — separate feature)
- Per-track loudness metering (master bus only for this story)
- Automatic loudness normalization during export (covered by export story)
