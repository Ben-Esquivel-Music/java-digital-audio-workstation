---
title: "Reflection-Based @RealTimeSafe Contract Verification in Tests"
labels: ["enhancement", "testing", "audio-engine", "reflection", "quality"]
---

# Reflection-Based @RealTimeSafe Contract Verification in Tests

## Motivation

The `@RealTimeSafe` annotation in `daw-sdk` documents methods that are safe to call on the real-time audio thread: zero heap allocations, zero lock acquisitions, zero blocking I/O. However, this contract is only enforced by developer discipline — there is no automated verification. A well-intentioned refactoring could introduce an allocation (e.g., autoboxing, varargs, or an `ArrayList` creation) inside a `@RealTimeSafe` method, and the regression would go undetected until it causes an audio glitch in production.

Java reflection enables systematic verification of `@RealTimeSafe` contracts at test time. A test can reflectively discover all methods annotated with `@RealTimeSafe`, invoke them under controlled conditions, and verify that no allocations or blocking occurred. Additionally, reflection can verify structural invariants — for example, that every `process()` method in every `AudioProcessor` implementation is annotated `@RealTimeSafe`, or that `@RealTimeSafe` methods do not call non-`@RealTimeSafe` methods.

The annotation is already `@Retention(RUNTIME)` and `@Target({METHOD, TYPE})`, making it fully introspectable.

## Goals

- Create a `RealTimeSafeContractTest` suite in `daw-core` that uses reflection to:
  1. **Discover all `@RealTimeSafe` methods** across `daw-core` and `daw-sdk` by reflectively scanning all classes in the `com.benesquivelmusic.daw` package tree, finding every method where `method.isAnnotationPresent(RealTimeSafe.class)` is true or the declaring class is annotated `@RealTimeSafe`
  2. **Verify annotation presence on critical paths**: assert that `Mixer.mixDown()`, `EffectsChain.process()`, `AudioEngine.processBlock()`, and every DSP processor's `process()` and `processSidechain()` methods are annotated `@RealTimeSafe`
  3. **Verify no `synchronized` blocks**: for each `@RealTimeSafe` method, verify via bytecode inspection or source-level heuristic that the method does not contain `synchronized` keywords (use `java.lang.reflect.Modifier` checks on called methods)
  4. **Verify method signatures avoid allocation-prone patterns**: flag `@RealTimeSafe` methods that declare varargs parameters, return boxed types (`Integer`, `Double`, etc.), or have parameters of type `String` (which suggests allocation for formatting)
  5. **Verify processor completeness**: assert that every concrete `AudioProcessor` implementation in `daw-core/dsp` has `@RealTimeSafe` on its `process()` method
- Report violations as test failures with clear messages (e.g., "CompressorProcessor.process() is not annotated @RealTimeSafe")
- Run these tests as part of the standard `mvn test` suite to catch regressions automatically

## Non-Goals

- Runtime allocation tracking via JVMTI or `-XX:+TraceAllocation` (too heavyweight for unit tests)
- Static analysis via annotation processors or bytecode analysis tools (ErrorProne, SpotBugs) — those are valuable but are separate tooling stories
- Modifying the `@RealTimeSafe` annotation itself
- Enforcing `@RealTimeSafe` on external or CLAP plugin code (only DAW-internal code)
