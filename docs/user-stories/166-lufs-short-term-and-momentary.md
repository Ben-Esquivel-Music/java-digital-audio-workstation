---
title: "LUFS Short-Term and Momentary Display (Full EBU R128 Meter)"
labels: ["enhancement", "metering", "mastering", "loudness"]
---

# LUFS Short-Term and Momentary Display (Full EBU R128 Meter)

## Motivation

Story 014 introduces LUFS *integrated* metering for platform-target compliance. A full EBU R128 meter also displays `momentary` (400 ms sliding window), `short-term` (3 s sliding window), `LRA` (loudness range over the integration), and `true-peak` — all of which are required for broadcast deliverables and informative for music mastering. TC Electronic's LM1/LM6, NuGen VisLM, Waves WLM, Logic's Loudness Meter all display these alongside integrated.

The existing `LufsMeter` and `LufsLoudnessEngine` already compute the K-weighted power integral; extending to shorter windows is mathematically the same filter bank with different time constants.

## Goals

- Add `LoudnessSnapshot` record in `com.benesquivelmusic.daw.sdk.mastering`: `record LoudnessSnapshot(double momentaryLufs, double shortTermLufs, double integratedLufs, double loudnessRangeLu, double truePeakDbtp)`.
- Extend `LufsLoudnessEngine` to compute all four measurements simultaneously; publish `LoudnessSnapshot` at 10 Hz on a `Flow.Publisher`.
- Extend `LufsMeter` (JavaFX view) into an `EbuR128Meter` with four needle/bar columns (M, S, I, LRA) and a true-peak bar; historic short-term plot for the last minute.
- Numeric readout panel: every measurement displayed in LUFS/dBTP with 0.1 precision plus target-delta compared to the active platform target (Spotify -14 LUFS, Apple Music -16, YouTube -14, etc.).
- Reset button clears the integrated and LRA accumulators.
- Playback-export parity (story 102) guarantees exported-file loudness matches the live meter within 0.2 LUFS.
- Persist platform-target selection per-project via `ProjectSerializer`.
- Tests: on an EBU conformance test signal (the BS.1770 test set), measurements match published targets within 0.1 LUFS.

## Non-Goals

- ATSC A/85 or ITU-R BS.1770-4-specific variants (EBU R128 is our reference).
- Log file / PDF loudness report (future story).
- Perceptual loudness models beyond BS.1770 (e.g., Vicent / Moore's model).
