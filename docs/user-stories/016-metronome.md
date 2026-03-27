---
title: "Metronome with Configurable Sound and Count-In"
labels: ["enhancement", "transport", "audio-engine", "recording"]
---

# Metronome with Configurable Sound and Count-In

## Motivation

A metronome (click track) is essential for recording in time. The transport has tempo and time signature settings, but there is no metronome implementation. Musicians need to hear a click while recording so they stay in time. The metronome should emphasize the downbeat (beat 1) with a different sound, support subdivision clicks, and allow users to configure the click volume and sound. Count-in (hearing clicks for 1-4 bars before recording starts) is critical for giving musicians a tempo reference before the take begins.

## Goals

- Add a metronome that generates audible clicks synced to the transport's tempo and time signature
- Distinguish the downbeat (beat 1) from other beats with a different pitch or sample
- Allow enabling/disabling the metronome via a toggle button in the transport bar
- Provide configurable count-in duration (off, 1 bar, 2 bars, 4 bars) before recording
- Allow adjusting metronome volume independently of the master bus
- Support subdivision clicks (e.g., eighth notes, sixteenth notes)
- Render metronome clicks during both playback and recording modes
- Allow selecting from built-in click sounds (woodblock, cowbell, electronic)

## Non-Goals

- Custom user-provided click samples
- Visual-only metronome (flashing indicator without sound)
- Metronome output to a separate hardware output for headphone-only click
