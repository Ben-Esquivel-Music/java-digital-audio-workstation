---
title: "3D Spatial Panner UI for Immersive Audio Positioning"
labels: ["enhancement", "spatial-audio", "ui", "immersive"]
---

# 3D Spatial Panner UI for Immersive Audio Positioning

## Motivation

The core module has extensive spatial audio infrastructure: `VbapPanner`, `AmbisonicEncoder/Decoder`, `ObjectBasedRenderer`, `BinauralRenderer`, and `FoldDownRenderer`. However, there is no UI for spatially positioning audio objects in 3D space. The mixer view only provides a basic stereo pan slider. For immersive audio production (Dolby Atmos, Ambisonics), users need a visual 3D panner interface to position sounds in X/Y/Z space and hear the result in real-time through the binaural renderer. The research documents identify this as a high-priority feature for a state-of-the-art DAW.

## Goals

- Add a 3D panner widget that can be opened from each mixer channel strip
- Display a top-down (X/Y) view with an optional side view (X/Z) for height positioning
- Allow dragging the sound source position with the mouse
- Show the current speaker layout as reference points on the panner
- Update the spatial rendering in real-time as the user adjusts position
- Support distance attenuation modeling (level decreases with distance)
- Integrate with the existing `VbapPanner` for speaker-based rendering
- Integrate with the existing `AmbisonicEncoder` for Ambisonics bus routing
- Show the source's azimuth, elevation, and distance as numeric readouts

## Non-Goals

- Pan automation recording (requires automation system — separate feature)
- Head tracking integration for binaural monitoring
- Full Dolby Atmos renderer certification
