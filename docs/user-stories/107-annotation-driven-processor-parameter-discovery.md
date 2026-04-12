---
title: "Annotation-Driven Processor Parameter Discovery via Reflection"
labels: ["enhancement", "dsp", "core", "reflection", "architecture"]
---

# Annotation-Driven Processor Parameter Discovery via Reflection

## Motivation

The `InsertEffectFactory` currently maintains four parallel switch statements — `createProcessor()`, `getParameterDescriptors()`, `createParameterHandler()`, and `getParameterValues()` — each with 9+ branches mapping `InsertEffectType` enum values to concrete processor classes, their parameter lists, and their getter/setter methods. Adding a new DSP processor requires editing all four switch statements plus the enum, a fragile and error-prone process. There are also 11 processors in the `daw-core/dsp` package (e.g., `AnalogDistortionProcessor`, `LeslieProcessor`, `BassExtensionProcessor`, `MultibandCompressorProcessor`) that are **not** available in the insert chain at all because nobody has added the corresponding boilerplate to `InsertEffectFactory`.

Every DSP processor already follows an identical convention: parameters are exposed as `public double getXxx()` / `public void setXxx(double value)` accessor pairs. This convention is a perfect candidate for annotation-driven discovery via Java reflection. By annotating processor parameters with a `@ProcessorParam` annotation, the factory can reflectively discover parameters, build parameter descriptors, and wire getter/setter handlers — all without any switch statements.

Professional audio frameworks (JUCE, iPlug 2) use declarative parameter registration. Java's annotation + reflection model achieves the same result without code generation or external configuration files, and the annotations are retained at runtime for introspection by the parameter editor UI, automation system, and preset serializer.

## Goals

- Define a `@ProcessorParam` annotation in `daw-sdk` (retained at `RUNTIME`, targeting `METHOD`) with attributes: `id` (int), `name` (String), `min` (double), `max` (double), `defaultValue` (double), and optional `unit` (String, e.g., "dB", "ms", "Hz", "%")
- Annotate all getter methods on existing DSP processors with `@ProcessorParam` (e.g., `@ProcessorParam(id = 0, name = "Threshold", min = -60.0, max = 0.0, defaultValue = -20.0, unit = "dB") public double getThresholdDb()` on `CompressorProcessor`)
- Implement a `ReflectiveParameterRegistry` utility in `daw-core` that, given any `AudioProcessor` instance, uses `Class.getDeclaredMethods()` to find all `@ProcessorParam`-annotated getters, resolves matching setters by naming convention (`getXxx` -> `setXxx`), and builds: (1) a `List<PluginParameter>` descriptor list, (2) a `BiConsumer<Integer, Double>` parameter handler for setting values, (3) a `Map<Integer, Double>` of current parameter values
- Cache reflected method handles per processor class using `Map<Class<?>, List<ReflectedParam>>` to avoid repeated reflection on every `processBlock` call
- Update `InsertEffectFactory` to delegate to `ReflectiveParameterRegistry` for any processor with `@ProcessorParam` annotations, falling back to the existing switch-based logic for processors without annotations (backward compatibility)
- Annotate all 20 DSP processors in `daw-core/dsp`, including the 11 that are not yet in the factory
- Add all 11 previously-unavailable processors to `InsertEffectType` and `InsertEffectFactory.availableTypes()` with zero manual parameter wiring — the reflection registry handles them automatically
- Add tests verifying: (1) reflective discovery finds all annotated parameters, (2) setter invocation via the registry matches direct setter calls, (3) round-trip: set value via registry, read back via registry, values match, (4) processors without annotations fall back gracefully

## Non-Goals

- Removing the existing switch-based code paths in `InsertEffectFactory` (they serve as fallback and remain for any future processor that opts out of annotations)
- Compile-time annotation processing (APT) — runtime reflection is sufficient and simpler
- Replacing the `PluginParameter` record in `daw-sdk` — `@ProcessorParam` maps to `PluginParameter` at discovery time
- Annotating external or CLAP plugin parameters (they have their own discovery mechanisms)
