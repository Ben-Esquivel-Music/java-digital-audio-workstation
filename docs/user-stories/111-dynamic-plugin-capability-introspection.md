---
title: "Dynamic Plugin Capability Introspection via Reflection"
labels: ["enhancement", "plugins", "core", "reflection", "ui"]
---

# Dynamic Plugin Capability Introspection via Reflection

## Motivation

The DAW's plugin system treats all plugins uniformly through the `DawPlugin` interface, but plugins have widely varying capabilities: some process audio (`AudioProcessor`), some provide gain reduction metering (`GainReductionProvider`), some support sidechain input (`SidechainAwareProcessor`), some report latency (`getLatencySamples() > 0`), and some expose custom UI. The mixer and UI layers need to know these capabilities to present the right controls — for example, showing a gain reduction meter only for compressors, showing a sidechain source selector only for sidechain-aware processors, or showing a latency label only for processors with non-zero latency.

Currently, capability detection uses scattered `instanceof` checks at each call site: `InsertEffectRack.buildPopulatedSlot()` checks `instanceof SidechainAwareProcessor`, `MixerView` checks `instanceof GainReductionProvider` for meter wiring, and `InsertEffectFactory.inferBuiltInEffectType()` uses 9 `instanceof` checks. These checks are not centralized, are easy to forget when adding new capabilities, and are not available to the generic parameter editor UI.

Java reflection enables a centralized capability introspection system that discovers a plugin's or processor's capabilities once at insertion time and exposes them through a structured `PluginCapabilities` record. This record can be queried by any UI component without `instanceof` checks.

## Goals

- Define a `PluginCapabilities` record in `daw-core` with boolean fields for each discoverable capability: `processesAudio`, `providesSidechainInput`, `reportsGainReduction`, `reportsLatency`, `supportsStereoOnly`, plus a `Set<String>` of custom capability keys for extensibility
- Implement a `PluginCapabilityIntrospector` utility that, given an `AudioProcessor` or `DawPlugin` instance, uses reflection to probe:
  1. **Interface implementation**: `SidechainAwareProcessor.class.isAssignableFrom(processor.getClass())` for sidechain support, `GainReductionProvider.class.isAssignableFrom(...)` for gain reduction metering
  2. **Annotation presence**: `@RealTimeSafe` on the `process()` method, `@ProcessorParam` annotations for parameter count
  3. **Method overrides**: whether `getLatencySamples()` is overridden from the default (use `processor.getClass().getMethod("getLatencySamples").getDeclaringClass() != AudioProcessor.class`) to detect latency-reporting processors without invoking the method
  4. **Constructor signatures**: whether the processor accepts `(int channels, double sampleRate)` or requires additional setup parameters, to determine if it can be instantiated generically
- Cache `PluginCapabilities` per processor class in a `ConcurrentHashMap` to avoid repeated reflection
- Update `InsertEffectRack` to use `PluginCapabilities` instead of direct `instanceof` checks for sidechain selector visibility
- Update `MixerView` to use `PluginCapabilities` for gain reduction meter wiring
- Expose `PluginCapabilities` through `InsertSlot` so any UI component can query the capabilities of the processor in a given slot
- Add tests verifying: (1) `CompressorProcessor` reports `providesSidechainInput = true`, `reportsGainReduction = true`, (2) `ReverbProcessor` reports `providesSidechainInput = false`, `reportsGainReduction = false`, (3) `LimiterProcessor` reports `reportsLatency = true`, (4) capabilities are cached correctly

## Non-Goals

- Modifying any existing interface (`AudioProcessor`, `SidechainAwareProcessor`, `GainReductionProvider`)
- Adding new capability interfaces in this story (those are separate feature stories)
- Capability negotiation at runtime (capabilities are fixed per class, discovered once)
- External CLAP plugin capability detection (CLAP has its own feature discovery mechanism)
