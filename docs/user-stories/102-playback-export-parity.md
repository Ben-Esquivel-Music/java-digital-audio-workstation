---
title: "Playback-Export Parity: Unified Render Pipeline for Live and Offline Processing"
labels: ["enhancement", "audio-engine", "export", "core"]
---

# Playback-Export Parity: Unified Render Pipeline for Live and Offline Processing

## Motivation

The DAW has two completely separate audio rendering paths:

1. **Live playback** — `AudioEngine.processBlock()` → `Mixer.mixDown()` → audio output. This path does **not** apply channel insert effects (story 086), does not read automation (story 087), and uses a plain `EffectsChain` instead of the `MasteringChain` for the master bus.

2. **Offline export** — `StemExporter` and `TrackBouncer` process tracks through a different code path that **does** apply channel insert effects via `EffectsChain.process()`.

This dual-path architecture means the exported audio will differ from what the user heard during playback — a critical violation of the "what you hear is what you get" (WYHIWYG) principle that every professional DAW upholds. Even after stories 086, 087, and 073 close the individual gaps, maintaining two separate render pipelines creates ongoing risk of divergence as features are added.

## Goals

- Refactor the audio rendering into a single `RenderPipeline` class (or equivalent) in `daw-core` that encapsulates the complete per-block processing: read clips → apply inserts → apply automation → mix down → apply master chain
- `AudioEngine.processBlock()` delegates to `RenderPipeline.renderBlock()` for live playback, which writes to the audio output
- `StemExporter` and `TrackBouncer` delegate to the same `RenderPipeline.renderBlock()` in a loop for offline export, which writes to file buffers
- The only difference between live and offline rendering is the output destination and timing: live rendering is paced by the audio callback, offline rendering runs as fast as possible
- Offline rendering can optionally run at higher precision (e.g., 64-bit internal processing downsampled at the end) — expose this as an export option
- Remove duplicated rendering logic from `StemExporter` and `TrackBouncer` — they become thin wrappers around `RenderPipeline`
- Add integration tests that verify live and offline rendering produce bit-identical output for the same project state
- `RenderPipeline` must remain `@RealTimeSafe` when used in the live path (no allocations, no locks)

## Non-Goals

- Changing the audio output backend or I/O layer
- Adding new export formats (covered by other stories)
- Real-time performance optimization (profiling, SIMD) — that is a separate concern
- Offline rendering UI (progress bars, cancel buttons — already exists in export dialogs)
