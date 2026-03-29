---
title: "Wire Mixer Channel Insert Effects Slots to DSP Processors"
labels: ["enhancement", "ui", "mixer", "dsp"]
---

# Wire Mixer Channel Insert Effects Slots to DSP Processors

## Motivation

User story 009 describes per-channel insert effects and EQ in the mixer view. The `daw-core` module contains a comprehensive collection of DSP processors (`CompressorProcessor`, `ReverbProcessor`, `SpringReverbProcessor`, `ParametricEqProcessor`, `DelayProcessor`, `ChorusProcessor`, `PhaserProcessor`, `LimiterProcessor`, `GateProcessor`, `SaturationProcessor`, and many more) all implementing the `AudioProcessor` interface. The `MixerChannel` has an `EffectsChain` for hosting insert effects. The `MixerView` renders channel strips with faders and pan knobs. However, there is no UI mechanism to add, remove, reorder, or configure insert effects on a mixer channel. Users cannot drag a compressor onto a channel strip, open its parameter editor, or bypass an effect. The `PluginParameterEditorPanel` exists for editing parameters but is not wired to mixer channel inserts.

## Goals

- Add an insert effects rack section to each mixer channel strip in the `MixerView`
- Show a numbered list of insert slots (e.g., 8 slots per channel) that can be populated with effects
- Clicking an empty insert slot opens a picker dialog listing available DSP processors (built-in effects from `daw-core/dsp`)
- Show the effect name and a bypass toggle button in each populated insert slot
- Double-clicking a populated insert slot opens the `PluginParameterEditorPanel` for that effect's parameters
- Allow drag-and-drop reordering of insert effects within a channel's effects chain
- Allow removing an effect by right-clicking the slot and selecting "Remove"
- Register insert add/remove/reorder operations as undoable actions
- Wire the insert effects into the `EffectsChain` on the `MixerChannel` so they are processed by the audio engine

## Non-Goals

- External plugin (CLAP/VST) hosting in insert slots (separate feature)
- Per-band EQ display curves on the channel strip
- Send/return bus configuration (separate feature)
