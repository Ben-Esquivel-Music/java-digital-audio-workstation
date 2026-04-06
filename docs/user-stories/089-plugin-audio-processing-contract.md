---
title: "DawPlugin Audio Processing Contract for Real-Time Effect Chains"
labels: ["enhancement", "plugins", "audio-engine", "core"]
---

# DawPlugin Audio Processing Contract for Real-Time Effect Chains

## Motivation

The `DawPlugin` interface in `daw-sdk` defines plugin lifecycle (`initialize`, `activate`, `deactivate`, `dispose`), metadata (`getDescriptor`), and parameter access — but has **no method for audio processing**. There is no `process(float[][], float[][], int)` method and no way for the host to ask a plugin to process an audio block.

The `AudioProcessor` interface exists separately in `daw-sdk` with exactly the right contract (`process`, `reset`, `getLatencySamples`), but there is no formal relationship between `DawPlugin` and `AudioProcessor`. Built-in effects must ad-hoc expose their processor (story 088), and external CLAP plugins go through a completely separate `ClapPluginHost` processing path. This means the mixer insert chain cannot treat built-in plugins, external JAR plugins, and CLAP plugins uniformly — each requires bespoke wiring code.

Professional plugin hosting APIs (VST3, CLAP, LV2, AU) all define audio processing as a core part of the plugin contract. A DAW plugin that cannot process audio is not a plugin — it's just metadata.

## Goals

- Add an `asAudioProcessor()` method to `DawPlugin` in `daw-sdk` that returns an `Optional<AudioProcessor>` — effect and instrument plugins return their processor, analysis-only plugins return empty
- Alternatively, define a sub-interface `ProcessingDawPlugin extends DawPlugin` that adds `AudioProcessor getAudioProcessor()` for plugins that process audio, keeping the base `DawPlugin` clean for non-processing plugins (analyzers, utilities)
- Ensure `BuiltInDawPlugin` implementations that wrap DSP processors implement the new contract
- Update `InsertEffectRack` and mixer channel insert wiring to use the unified contract: when a `DawPlugin` is inserted into a channel, call `asAudioProcessor()` to get the `AudioProcessor` and add it to the `EffectsChain`
- Update `ClapPluginHost` to implement the same contract by wrapping CLAP's `process()` callback as an `AudioProcessor`
- Add SDK-level documentation explaining how plugin developers implement audio processing
- Add tests verifying that built-in effect plugins, external JAR plugins, and (mock) CLAP plugins all satisfy the contract

## Non-Goals

- Changing the `AudioProcessor` interface itself (it is already well-designed)
- Implementing MIDI processing in the plugin contract (MIDI is handled separately by `SoundFontRenderer`)
- Plugin sandboxing or out-of-process hosting
- GUI rendering as part of the plugin contract (plugins provide parameter descriptors, the host renders UI)
