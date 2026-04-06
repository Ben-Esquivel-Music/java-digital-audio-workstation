---
title: "Bridge Built-In Effect Plugins to Their DSP Processors"
labels: ["enhancement", "plugins", "dsp", "core"]
---

# Bridge Built-In Effect Plugins to Their DSP Processors

## Motivation

The three built-in effect plugins — `ParametricEqPlugin`, `CompressorPlugin`, and `ReverbPlugin` — are metadata-only shells. Their `initialize(PluginContext)` methods are empty. They have no reference to their corresponding DSP processors (`ParametricEqProcessor`, `CompressorProcessor`, `ReverbProcessor`), no `AudioProcessor` field, and no way to process audio. Their Javadoc says "Wraps the DAW's CompressorProcessor as a first-class plugin" but no wrapping actually occurs.

When a user selects "Compressor" from the Plugins menu or inserts it into a mixer channel, the plugin creates no audio processing capability. Even if story 086 (insert effects in live playback) and story 062 (insert effects UI wiring) are fully implemented, these three plugins would still produce silence because they have no `AudioProcessor` to contribute to the `EffectsChain`.

The `SignalGeneratorPlugin` (547 lines) and `VirtualKeyboardPlugin` demonstrate the correct pattern: they create and wire their underlying engine components during `initialize()`. The effect plugins need the same treatment.

## Goals

- Add an `AudioProcessor` field to each effect plugin (`ParametricEqPlugin`, `CompressorPlugin`, `ReverbPlugin`) that holds a reference to its corresponding DSP processor
- In `initialize(PluginContext)`, create the DSP processor instance: `ParametricEqPlugin` creates a `ParametricEqProcessor`, `CompressorPlugin` creates a `CompressorProcessor`, `ReverbPlugin` creates a `ReverbProcessor`
- Expose a `getAudioProcessor()` method (or equivalent) on `BuiltInDawPlugin` so the host can retrieve the processor and insert it into a `MixerChannel`'s `EffectsChain` or `InsertSlot`
- Configure default parameters for each processor on creation (e.g., compressor: threshold -20 dB, ratio 4:1, attack 10 ms, release 100 ms; EQ: flat response; reverb: room preset)
- Expose the processor's parameters through the plugin's `getParameters()` method so the `PluginParameterEditorPanel` can display and edit them
- In `dispose()`, release the processor reference and reset state
- Add unit tests verifying: (1) `initialize()` creates a non-null processor, (2) the processor is the correct type, (3) `getAudioProcessor()` returns the processor, (4) `dispose()` cleans up, (5) default parameters are reasonable

## Non-Goals

- Creating custom UIs for each effect plugin (the generic `PluginParameterEditorPanel` is sufficient initially)
- Adding new DSP processors beyond the three that already exist
- Changing the `DawPlugin` SDK interface (the `getAudioProcessor()` method goes on `BuiltInDawPlugin` only)
- Wiring processors into the mixer insert chain (covered by stories 062 and 086)
