---
title: "Reflective Preset Serializer for Automatic Processor State Capture"
labels: ["enhancement", "dsp", "persistence", "reflection", "usability"]
---

# Reflective Preset Serializer for Automatic Processor State Capture

## Motivation

Saving and loading effect presets currently requires hand-coded logic in `InsertEffectFactory.getParameterValues()` and `InsertEffectFactory.createParameterHandler()` — one switch branch per processor type, with manual getter/setter calls for every parameter. Story 100 (Track Templates and Channel Strip Presets) specifies a preset system, but implementing it requires the ability to snapshot and restore the complete state of any processor. Today, adding a new parameter to an existing processor (e.g., adding a "look-ahead" knob to the compressor) requires updating both the `getParameterValues` and `createParameterHandler` switch branches, and forgetting either one means the parameter is silently lost on save or ignored on load.

Java reflection can generalize this: given any `AudioProcessor` instance, a reflective serializer can discover all `@ProcessorParam`-annotated methods (from story 107), invoke each getter to capture the current value, and serialize the results as a key-value map (JSON or XML). To restore, it invokes each setter with the stored value. This makes preset save/load work automatically for any annotated processor — including the 11 processors not currently in the factory — with zero per-processor serialization code.

This also enables story 100's `ChannelStripPreset` to capture complete insert chain state (all processors, all parameters) without knowing the concrete processor types at compile time.

## Goals

- Implement a `ReflectivePresetSerializer` in `daw-core` that:
  1. **Snapshot**: given an `AudioProcessor`, reflects over `@ProcessorParam`-annotated getters, invokes each via cached `MethodHandle`, and returns a `Map<String, Double>` keyed by the annotation's `name` attribute
  2. **Restore**: given an `AudioProcessor` and a `Map<String, Double>`, finds the matching `@ProcessorParam`-annotated setter for each key and invokes it, skipping unknown keys gracefully (forward compatibility)
  3. **Serialize to XML/JSON**: converts the snapshot map to a portable format suitable for preset files and project serialization
  4. **Validate on restore**: clamps values to the `[min, max]` range declared in `@ProcessorParam` to prevent invalid state from malformed preset files
- Update `ProjectSerializer` to use `ReflectivePresetSerializer` for insert effect parameter serialization instead of `InsertEffectFactory.getParameterValues()`, falling back to the existing logic for non-annotated processors
- Update `ProjectDeserializer` to use `ReflectivePresetSerializer` for parameter restoration, falling back to `InsertEffectFactory.createParameterHandler()` for non-annotated processors
- Define a `ProcessorPreset` record: `(String processorClassName, String displayName, Map<String, Double> parameterValues)` for standalone preset files
- Implement `PresetManager` in `daw-core` that reads/writes `ProcessorPreset` files from a user directory (e.g., `~/.daw/presets/effects/`)
- Add factory presets for common configurations (e.g., "Vocal Compressor", "Room Reverb", "Tape Delay") shipped as bundled resources
- Add tests verifying: (1) snapshot captures all annotated parameters, (2) restore applies all values, (3) round-trip snapshot-serialize-deserialize-restore preserves all values, (4) unknown keys in preset files are skipped, (5) out-of-range values are clamped

## Non-Goals

- Full track template system (story 100 covers the UI and workflow layer)
- Preset sharing, cloud storage, or format standardization
- Preset morphing or interpolation between two presets
- Undo/redo integration for preset loading (story 100's concern)
