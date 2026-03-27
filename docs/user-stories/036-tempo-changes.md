---
title: "Tempo and Time Signature Changes Along the Timeline"
labels: ["enhancement", "transport", "arrangement-view", "core"]
---

# Tempo and Time Signature Changes Along the Timeline

## Motivation

The `Transport` class supports a single tempo and time signature for the entire project. Real-world music frequently changes tempo and time signature — a song might start at 120 BPM in 4/4, transition to 140 BPM for the chorus, and switch to 3/4 for a bridge section. Without tempo/time-signature changes, users cannot accurately compose, record, or edit music that deviates from a single tempo. The timeline ruler, metronome, and beat grid all depend on tempo, so this is a foundational feature that affects many other systems.

## Goals

- Support multiple tempo change events along the timeline (e.g., "bar 17: 140 BPM")
- Support time signature change events (e.g., "bar 33: 3/4")
- Display tempo changes as markers on the timeline ruler
- Update the metronome to follow tempo changes during playback
- Update the beat grid to reflect changing tempo and time signature
- Provide a tempo track/lane for visual editing of tempo changes
- Support linear and curved tempo transitions (accelerando/ritardando)
- Correctly convert between beats and absolute time when tempo varies

## Non-Goals

- Audio time-stretching to follow tempo changes (separate feature)
- MIDI tempo maps imported from MIDI files
- Tap tempo input for live tempo setting
