---
title: "Atmos Renderer A/B Comparison with Reference Mix"
labels: ["enhancement", "spatial", "immersive", "mastering", "qc"]
---

# Atmos Renderer A/B Comparison with Reference Mix

## Motivation

A Dolby Atmos mix should sound equivalent across different renderers (Dolby Atmos Renderer, in-DAW renderers, AV receiver variants). During QC, engineers compare the DAW's internal render against a trusted reference (typically a recorded output from the Dolby Atmos Renderer) to verify object placement and level accuracy. Story 041 offers stereo reference A/B; this story extends it to the immersive case with matched time alignment across all channels.

## Goals

- Extend `ReferenceTrack` (story 041) to accept multi-channel reference files (up to 7.1.4 / 9.1.6).
- Add `AtmosAbComparator` in `com.benesquivelmusic.daw.core.spatial.qc` providing: per-channel level deltas, full-bed RMS delta, per-channel correlation, and a time-alignment estimate (cross-correlation peak within ±50 ms).
- `AtmosAbView` in `daw-app.ui.spatial`: side-by-side channel-level bars showing reference vs current mix, per-channel delta in dB, a color-coded overall match score, and a scrubbable waveform difference plot.
- Single-key A/B toggle (e.g., `\`) that crossfades monitoring between the DAW's render and the reference playback with level-matched output so the user hears like-for-like.
- Optional auto-trim: estimate and apply per-channel gain trim that minimizes delta, useful for spotting unintended gain changes.
- Persist reference file reference and trim values via `ProjectSerializer`.
- Tests: identical-to-identical comparison reports 0 delta; a known -3 dB offset on one channel is correctly measured; the time-alignment estimator recovers a synthetically inserted 20 ms offset to within 1 sample.

## Non-Goals

- Objective spatial-error metrics (ITD, ILD error) — a deep-research story.
- Automatic mix correction based on the comparison.
- Reference file format conversion (the reference and mix must share channel layout).
