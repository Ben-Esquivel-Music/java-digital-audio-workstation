---
title: "Reflective Parameter Binding for Plugin Automation"
labels: ["enhancement", "automation", "plugins", "reflection", "audio-engine"]
---

# Reflective Parameter Binding for Plugin Automation

## Motivation

Story 087 (Automation Playback Engine) applies automation to mixer channel parameters (volume, pan, mute, send level) via hard-coded `AutomationParameter` enum values and direct setter calls in `AudioEngine.applyAutomation()`. Story 101 (Plugin Parameter Automation) extends this to plugin parameters, but requires a mechanism to discover which parameters a plugin exposes and map automation lane values to those parameters in real time.

The current automation system uses a fixed enum (`AutomationParameter`) that cannot represent plugin-specific parameters. Each plugin may have 1–30 parameters (a compressor has 6, a parametric EQ can have 24+), and these vary by plugin type. Hard-coding each mapping is not scalable.

With `@ProcessorParam` annotations (story 107), Java reflection provides a natural bridge: the automation system can reflectively discover a processor's parameters, create automation lanes for them, and apply automation values by invoking the annotated setters via cached `MethodHandle`s on the audio thread. The key constraint is that the actual `MethodHandle.invoke()` call on the audio thread must be allocation-free — `MethodHandle`s satisfy this requirement when the call site is monomorphic.

## Goals

- Extend `AutomationParameter` (or introduce a parallel `PluginAutomationParameter` type) to represent plugin-specific parameters, identified by `(insertSlotIndex, paramId)` pairs rather than fixed enum values
- Implement a `ReflectiveParameterBinder` in `daw-core` that, given a `MixerChannel` and its `InsertSlot` list:
  1. **Discovery phase** (non-real-time, called when inserts change): for each insert slot's processor, reflects over `@ProcessorParam` annotations, creates `MethodHandle`s for each setter, and stores them in a pre-allocated `ParameterBinding[]` array indexed by `(slotIndex, paramId)`
  2. **Apply phase** (`@RealTimeSafe`, called from `AudioEngine.applyAutomation()`): for each plugin parameter automation lane that has active data, reads the automation value at the current transport position, clamps it to the parameter's `[min, max]` range, and invokes the cached `MethodHandle` — zero allocations, zero reflection lookups
- Update `AudioEngine.applyAutomation()` to call `ReflectiveParameterBinder.apply()` after the existing channel-level automation, applying plugin parameter automation values to each insert's processor
- Pre-allocate the binding arrays during `Mixer.prepareForPlayback()` so the audio thread path is allocation-free
- Expose the list of automatable plugin parameters per channel through a `getAutomatablePluginParameters(MixerChannel)` method for the automation lane UI to populate its parameter dropdown
- Add tests verifying: (1) parameter bindings are created correctly for annotated processors, (2) setting an automation value via the binder produces the same result as calling the setter directly, (3) the apply phase does not allocate (verify via `@RealTimeSafe` contract), (4) re-binding after insert changes picks up the new processor's parameters

## Non-Goals

- Automation recording modes (Write/Latch/Touch) — those are UI concerns covered by story 101
- Automation of non-annotated processors or external plugins (they use their own parameter systems)
- LFO or expression-based parameter modulation
- Bezier interpolation for automation curves (linear is sufficient initially)
