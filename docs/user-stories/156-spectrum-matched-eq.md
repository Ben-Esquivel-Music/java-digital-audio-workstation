---
title: "Spectrum-Matched EQ Built-In Plugin"
labels: ["enhancement", "plugins", "built-in", "dsp", "mastering"]
---

# Spectrum-Matched EQ Built-In Plugin

## Motivation

"Match EQ" tools analyze a reference track's spectrum and fit an EQ curve to the current signal to approximate that tonal balance. iZotope's Match EQ, FabFilter's Pro-Q Match, Ozone's Match EQ, Logic's Match EQ are all go-to mastering tools and a common feature request. Story 099 covers graphic EQ; this is the matching-side automation that drives it.

Story 041 (Reference Track A/B) already supports loading a reference; this story consumes that reference's average spectrum.

## Goals

- Add `MatchEqProcessor` in `com.benesquivelmusic.daw.core.dsp.eq`: analyzes a long-term-average spectrum of `source` and `reference`, computes the difference, and applies it as a linear-phase FIR or minimum-phase IIR cascade (user choice).
- Parameters: FFT size (1024/2048/4096), smoothing (critical-band / third-octave / sixth-octave), amount (0–100% blend between source spectrum and target spectrum), phase mode (linear / minimum).
- `MatchEqPlugin` record registered with `BuiltInPluginRegistry`.
- `MatchEqPluginView`: dual spectrum overlay (source vs reference), resulting EQ curve overlay, sliders for smoothing and amount, reference loader.
- Acquires reference spectrum from story 041's `ReferenceTrack`; supports loading a new audio file directly for one-off matches.
- "Capture source" button freezes the current source spectrum so the match is stable during playback.
- Persist captured spectra and settings via `ProjectSerializer`; spectra stored as downsampled `double[]` for compact storage.
- Unit tests: feeding identical reference and source produces a flat (unity) curve within FFT resolution; feeding pink vs white noise produces the expected pink filter; linear-phase mode introduces no group-delay variation.

## Non-Goals

- AI-driven tonal matching beyond spectral magnitude (phase/transient matching is out of scope).
- Per-band time-varying match.
- Match across stereo position (M/S-aware match is a future extension).
