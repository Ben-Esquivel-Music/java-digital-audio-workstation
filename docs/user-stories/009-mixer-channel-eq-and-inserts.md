---
title: "Per-Channel Insert Effects and EQ in Mixer View"
labels: ["enhancement", "mixer", "dsp", "ui"]
---

# Per-Channel Insert Effects and EQ in Mixer View

## Motivation

The mixer channel strips currently show volume fader, pan, mute/solo/arm, and a send control, but there is no way to insert effects on a per-channel basis from the mixer view. Professional DAWs have insert slots on each channel strip where users can load EQ, compressor, reverb, and other effects. The `EffectsChain` class exists in the core module and DSP processors (EQ, compressor, limiter, reverb, delay, etc.) are implemented, but they are not exposed in the mixer UI. Users need to be able to add, remove, reorder, and bypass insert effects directly from the mixer channel strip.

## Goals

- Add insert effect slots to each mixer channel strip (at least 8 slots)
- Provide a dropdown menu to select from available DSP processors when clicking an empty slot
- Show the name of the loaded effect in each occupied slot
- Allow bypassing individual insert effects (click to toggle bypass)
- Allow reordering insert effects via drag-and-drop within the insert chain
- Open an effect parameter editor when double-clicking a loaded insert
- Wire the insert effects to the `EffectsChain` in the audio processing pipeline
- Support the existing built-in processors: ParametricEQ, Compressor, Limiter, Reverb, Delay, Chorus, NoiseGate, StereoImager, GraphicEQ

## Non-Goals

- External plugin (VST/CLAP/LV2) loading in insert slots (separate feature)
- Inline EQ curve display on the channel strip (enhancement)
- Per-insert undo (batch undo for the entire insert chain change)
