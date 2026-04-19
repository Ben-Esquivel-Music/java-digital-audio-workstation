---
title: "JavaFX View Snapshot / Visual Regression Testing (TestFX + Image Diff)"
labels: ["testing", "ci", "ui"]
---

# JavaFX View Snapshot / Visual Regression Testing (TestFX + Image Diff)

## Motivation

UI regressions — a layout breaking on a `ThemeContrastValidator` (story 194) change, a clip renderer drawing one pixel off, a mixer strip misaligning — are invisible to unit tests. Every UI-heavy project adopts visual-regression testing sooner or later: golden PNGs per view, pixel-diff on each run, rebaseline when intentional. TestFX provides the headless-JavaFX runner; image diffing is a small, boring piece of glue code.

## Goals

- Add TestFX + a small image-diff library dependency in the `daw-app` test classpath.
- Add `FxSnapshotTest` base class in `daw-app/src/test/java/.../ui/` providing: set up JavaFX in headless mode (`-Dprism.order=sw`), render the view under test, take a `Screenshot` via `javafx.scene.Scene.snapshot(...)`, compare to a golden PNG under `daw-app/src/test/resources/snapshots/<test-class>/<test-method>.png`.
- Pixel diff with tolerance (default ≤0.5% differing pixels, max Δ per channel ≤4) to avoid flakiness from subpixel rendering differences across platforms.
- On diff: fail the test with both images attached to the report and a "rebaseline" Gradle/Maven command hint.
- Cover the major views: `ArrangementCanvas`, `MixerView`, `EditorView`, `TelemetrySetupPanel`, `MasteringChainView`.
- Theme-aware: each view is snapshotted in each bundled theme (story 194) so theme regressions are caught.
- CI: snapshots generated on a fixed platform (Linux headless) to avoid font-rendering cross-platform drift.
- Document rebaselining workflow in `CONTRIBUTING.md`.

## Non-Goals

- Interaction testing (click, drag) — covered by regular TestFX outside this story.
- Animation regression testing (transient frames).
- Cross-OS snapshots (Linux-CI baseline only; OS-specific differences are annotated).
