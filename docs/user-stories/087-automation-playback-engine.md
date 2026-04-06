---
title: "Apply Automation Lane Values to Mixer Parameters During Audio Playback"
labels: ["enhancement", "audio-engine", "automation", "mixer", "core"]
---

# Apply Automation Lane Values to Mixer Parameters During Audio Playback

## Motivation

The automation data model is fully implemented: `AutomationLane` stores breakpoints with `InterpolationMode` (linear/curved), `getValueAtTime(double beats)` returns interpolated values, and undoable actions exist for adding/moving/removing automation points (stories 003, 059). The `ArrangementCanvas` renders automation envelopes visually. However, `AudioEngine.processBlock()` and `Mixer.mixDown()` **never read automation values** — they use the static `MixerChannel.getVolume()` and `MixerChannel.getPan()` values set by the UI faders.

This means automation lanes are purely decorative. A user can draw a volume fade-out over 8 bars, but the audio plays at a constant level. Every professional DAW reads automation values per audio block and applies them to the corresponding mixer parameters. Without this, the DAW cannot produce dynamic mixes — the most basic purpose of automation.

## Goals

- At the start of each `AudioEngine.processBlock()` call, read the current transport position in beats
- For each track that has automation lanes, call `automationLane.getValueAtTime(currentBeat)` for each automated parameter (volume, pan, mute, send level)
- Apply the retrieved automation values to the corresponding `MixerChannel` properties before `Mixer.mixDown()` processes the block — e.g., `channel.setVolume(automatedVolume)`
- Support per-block granularity as the initial implementation (one value lookup per block), which is sufficient at typical block sizes (128–512 samples at 44.1 kHz = 3–12 ms resolution)
- Provide a per-track "automation read" enable flag (`AutomationMode.READ`) so users can disable automation playback without deleting automation data
- When automation is actively controlling a parameter, update the UI fader/knob position to reflect the automated value (post the update to the JavaFX application thread)
- Ensure automation application is `@RealTimeSafe` — `getValueAtTime()` is a pure computation (binary search + interpolation) with no allocations
- Add tests verifying: (1) volume automation changes the output level, (2) pan automation moves the stereo image, (3) mute automation silences/unsilences the channel, (4) automation read mode can be disabled per-track

## Non-Goals

- Sub-block sample-accurate automation (per-sample interpolation within a block — future optimization)
- Automation recording from fader movements (write/latch/touch modes — separate story)
- Plugin parameter automation (requires plugin parameter discovery — separate story)
- Automation curves with mathematical expressions or LFO generators
