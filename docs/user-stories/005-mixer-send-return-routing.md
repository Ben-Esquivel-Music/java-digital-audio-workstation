---
title: "Mixer Send/Return Bus Routing"
labels: ["enhancement", "mixer", "audio-engine"]
---

# Mixer Send/Return Bus Routing

## Motivation

The current mixer view shows per-channel send level sliders, but the underlying audio engine has no actual send/return bus routing. In professional mixing, sends allow multiple tracks to share a common effect (e.g., a single reverb bus) rather than inserting the effect on every track individually. This is essential for both creative mixing and CPU efficiency. Without functional sends, the mixer's send controls are cosmetic only. The `Mixer` class needs actual bus channels, and the audio routing must sum send contributions from multiple channels into shared return buses.

## Goals

- Add support for auxiliary send/return buses in the `Mixer` core class
- Allow users to create new aux return channels from the mixer view
- Route send levels from track channels to aux return channels
- Process effects chains on return channels (shared reverb, delay, etc.)
- Sum return channel outputs into the master bus
- Display return channels as distinct channel strips in the mixer view
- Support pre-fader and post-fader send modes
- Make send routing changes undoable

## Non-Goals

- Sidechain routing between channels
- Direct output routing to hardware outputs
- Sub-group/bus grouping (separate feature)
