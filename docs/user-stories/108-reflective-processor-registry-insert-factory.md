---
title: "Reflective Processor Registry to Replace Hard-Coded InsertEffectFactory"
labels: ["enhancement", "mixer", "core", "reflection", "architecture"]
---

# Reflective Processor Registry to Replace Hard-Coded InsertEffectFactory

## Motivation

`InsertEffectFactory` is the central bottleneck for making DSP processors available as mixer insert effects. Its `createProcessor()` method contains a switch statement that maps each `InsertEffectType` enum value to a `new XxxProcessor(channels, sampleRate)` constructor call. Its `inferBuiltInEffectType()` method contains a chain of 9 `instanceof` checks to reverse-map a processor instance back to its enum type. Every new processor requires editing both methods, the enum, and the `availableTypes()` list — four coordinated changes across two files.

Java reflection enables a self-registering pattern: each processor class declares its own `InsertEffectType` association via a class-level annotation, and the factory discovers all registered processors at startup by scanning the annotation. This eliminates the switch statements entirely and makes adding a new processor a single-file change: write the processor class with the annotation, and it appears in the mixer automatically.

The `InsertEffectType` enum can itself be replaced by a dynamic registry — or it can be retained as a stable persistence key while the factory uses the annotation-to-class mapping for everything else.

## Goals

- Define a `@InsertEffect` annotation in `daw-core` (retained at `RUNTIME`, targeting `TYPE`) with attributes: `type` (String, the persistence key matching `InsertEffectType.name()`), `displayName` (String), and `stereoOnly` (boolean, default `false`)
- Annotate all DSP processor classes with `@InsertEffect` (e.g., `@InsertEffect(type = "COMPRESSOR", displayName = "Compressor") public final class CompressorProcessor`)
- Implement a `ProcessorRegistry` in `daw-core` that on first access: (1) scans a configured set of processor classes (initially by iterating `InsertEffectType.values()` and resolving the annotated class, or by scanning the `daw-core/dsp` package), (2) builds a bidirectional `InsertEffectType <-> Class<? extends AudioProcessor>` map, (3) caches constructor references via `MethodHandle` for allocation-free instantiation
- Replace `InsertEffectFactory.createProcessor()` switch with `registry.createProcessor(type, channels, sampleRate)` which looks up the class, invokes the cached constructor, and returns the instance
- Replace `InsertEffectFactory.inferBuiltInEffectType()` instanceof chain with `registry.inferType(processor)` which uses a `Map<Class<?>, InsertEffectType>` lookup — O(1) instead of O(n) instanceof checks
- Replace `InsertEffectFactory.availableTypes()` hard-coded list with `registry.availableTypes()` which returns all registered non-CLAP types
- Ensure backward compatibility: project files that reference `InsertEffectType` names (e.g., `"COMPRESSOR"`) in XML continue to deserialize correctly
- Add tests verifying: (1) all annotated processors are discovered, (2) `createProcessor` returns the correct type for each `InsertEffectType`, (3) `inferType` returns the correct type for each processor instance, (4) round-trip create-then-infer produces the same type

## Non-Goals

- Classpath scanning via a library like ClassGraph or Reflections (use an explicit class list or package scan within the `daw-core` module)
- Removing `InsertEffectType` enum (it remains as the stable persistence key)
- Dynamic hot-loading of new processor classes at runtime (processors are discovered at startup)
- Changing the `AudioProcessor` interface in `daw-sdk`
