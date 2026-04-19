---
title: "Headless Audio Test Harness for Deterministic CI Runs Without Audio Device"
labels: ["testing", "ci", "audio-engine"]
---

# Headless Audio Test Harness for Deterministic CI Runs Without Audio Device

## Motivation

Audio-engine tests today require a working audio device or rely on `MockAudioBackend` in ad-hoc fashion. CI runs on headless Linux containers without sound cards. A proper `HeadlessAudioHarness` makes integration tests first-class: load a project, "play" it at any speed (faster than real time for fast tests), write the output to a byte buffer, assert properties on the result. This unlocks end-to-end testing of arrangement + mixer + automation + plugins in a single test.

## Goals

- Add `HeadlessAudioHarness` in `daw-core/src/test/java/.../audio/` providing: `load(Project)`, `renderRange(long startFrame, long endFrame)` returning `double[][]`, `playAtSpeed(double speedFactor)` for stress runs faster than real time.
- Backed by a `HeadlessAudioBackend implements AudioBackend` that captures output into memory buffers without device output.
- Offline-friendly: no `Platform.startup`, no JavaFX threads touched in engine-only tests.
- Golden-file comparison helpers: `assertRenderMatches(Path goldenFile, double[][] actual, double toleranceDbfs)`.
- Deterministic clock + seed injection (story 199's `Clock` + `Random` injection) so every test is byte-reproducible.
- Timeout enforcement: by default no harness render exceeds 10 seconds of wall clock regardless of session length at the configured speed factor.
- Integration with JUnit 5 via `@ExtendWith(HeadlessAudioExtension.class)` that sets up and tears down deterministic state.
- Tests-for-the-harness: a sine generator produces the mathematically expected output buffer.

## Non-Goals

- JavaFX / UI testing (that is story 208).
- Real-time-deadline verification (not a goal of headless harness — `@RealTimeSafe` checks cover that).
- Performance benchmarking (separate JMH harness).
