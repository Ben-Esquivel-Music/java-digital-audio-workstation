---
title: "Plugin DSP Regression Test Framework (Golden-File Audio Comparisons per Processor)"
labels: ["testing", "ci", "dsp", "plugins"]
---

# Plugin DSP Regression Test Framework (Golden-File Audio Comparisons per Processor)

## Motivation

Every DSP processor — EQ, compressor, limiter, reverb, waveshaper — can subtly drift over time as the code is refactored, and the change is inaudible in unit assertions but very audible at mix time. The standard protection is a golden-file regression test per processor: feed a well-defined test signal (sine sweep, white noise, transient snare, speech clip), run it through the processor with a canonical parameter set, compare the output against a committed golden file within a tolerance. This is how iZotope, FabFilter, and every serious DSP vendor catches regressions.

## Goals

- Add `DspRegressionHarness` in `daw-core/src/test/java/.../dsp/` providing: load a test signal from `daw-core/src/test/resources/test-signals/` (sine sweep, pink noise, transient hit, speech, music), process through a `DawPlugin` with a canonical parameter `Preset`, compare output to a golden file.
- Comparison: per-sample absolute difference in dB, reported as peak + RMS error; pass threshold default -80 dB peak.
- `@DspRegression` JUnit meta-annotation with `(testSignal, preset, goldenFile, peakToleranceDb)` parameters so per-processor tests are one-liners.
- Annotation-driven discovery: every class implementing `BuiltInDawPlugin.EffectPlugin` must have at least one `@DspRegression` test; a compile-time check (story 114 harness) flags missing coverage.
- Rebaselining: `mvn test -Pdsp-rebaseline` regenerates all golden files with a verbose diff summary; require manual review + commit of the new goldens.
- Each processor has at least three parameter presets: `Default`, `Aggressive`, `Subtle` — golden-file per combination.
- CI enforcement: `long-tests` profile (story 209) runs the full regression suite.

## Non-Goals

- Subjective listening tests (pure numerical comparison).
- Perceptual error metrics (ODG, PEAQ) beyond peak/RMS (a possible follow-on story).
- Automated generation of test signals from mathematical definitions (tests use committed audio files for byte-exact reproducibility).
