---
title: "Reflection-Powered DSP Processor Test Harness"
labels: ["enhancement", "testing", "dsp", "reflection", "quality"]
---

# Reflection-Powered DSP Processor Test Harness

## Motivation

The `daw-core/dsp` package contains 20 `AudioProcessor` implementations. Each needs a baseline set of tests: construction with valid/invalid arguments, process() producing output, reset() clearing state, parameter setters rejecting invalid values, getInputChannelCount/getOutputChannelCount consistency, and getLatencySamples() returning a non-negative value. Currently, each processor has its own test class with manually written tests that cover overlapping concerns — the `CompressorProcessorTest`, `NoiseGateProcessorTest`, and other test files each independently test constructor validation, process output, and reset behavior.

Java reflection enables a single parameterized test harness that discovers all `AudioProcessor` implementations, instantiates each via its `(int, double)` constructor, and runs a standard battery of structural and behavioral tests against every processor automatically. When a new processor is added, it gets tested immediately with zero additional test code. The `@ProcessorParam` annotations (story 107) further enable automatic parameter range testing: the harness can set each parameter to its min, max, and default values and verify the processor doesn't throw.

## Goals

- Create a `ProcessorTestHarness` in `daw-core` test sources that:
  1. **Discovers all processors** by scanning the `com.benesquivelmusic.daw.core.dsp` package for classes implementing `AudioProcessor`, using `Class.getConstructors()` to find those with a `(int, double)` constructor
  2. **Generates a parameterized test source** via JUnit 5 `@MethodSource` that yields one test case per discovered processor class
  3. **For each processor**, runs the following test battery:
     - **Construction**: instantiates with `(2, 44100.0)`, verifies non-null and correct channel counts
     - **Invalid construction**: verifies `(0, 44100.0)` and `(2, 0.0)` throw `IllegalArgumentException`
     - **Process produces output**: feeds a 512-frame buffer of 0.5f through `process()`, verifies output is non-null and has the expected dimensions
     - **Silence in, silence out**: feeds zeros through `process()`, verifies output is all zeros (for effects with no feedback or tail)
     - **Reset clears state**: calls `process()` with signal, then `reset()`, then `process()` with silence — verifies output returns to zero
     - **getLatencySamples non-negative**: verifies `getLatencySamples() >= 0`
     - **Parameter range testing** (if `@ProcessorParam` annotations are present): for each annotated parameter, sets the value to `min`, `max`, and `defaultValue` via the setter method, verifies no exception is thrown
     - **Parameter getter/setter round-trip**: sets a value via setter, reads back via getter, verifies they match
  4. **Reports failures clearly**: includes the processor class name and the specific test that failed
- Ensure the harness auto-discovers newly added processors without any test code changes
- Add a separate test verifying that every `AudioProcessor` in the `dsp` package is discoverable by the harness (no processor is accidentally skipped due to a non-standard constructor)

## Non-Goals

- Replacing existing per-processor tests (they test domain-specific behavior like compression curves, gate state machines, etc. that the harness cannot cover)
- Testing external or CLAP plugins (they have their own test patterns)
- Performance or benchmarking tests (separate concern)
- Audio quality validation (e.g., FFT-based distortion measurement)
