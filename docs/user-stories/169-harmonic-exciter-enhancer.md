---
title: "Harmonic Exciter / Psychoacoustic Enhancer Built-In Plugin"
labels: ["enhancement", "plugins", "built-in", "dsp", "mastering"]
---

# Harmonic Exciter / Psychoacoustic Enhancer Built-In Plugin

## Motivation

A harmonic exciter adds subtle high-frequency harmonic content (controlled 2nd- and 3rd-order distortion) to perceptually brighten a signal without raising broadband level — a trick made famous by the Aphex Aural Exciter in the 1970s and still standard in mastering and broadcast sweetening. iZotope Ozone Exciter, FabFilter Saturn, Waves Maxx Bass/Treble variants, Logic Exciter. Related but narrower than the full-saturation story 106.

## Goals

- Add `ExciterProcessor` in `com.benesquivelmusic.daw.core.dsp.saturation` with a psychoacoustic model: high-pass the signal at a user-controlled crossover, run the high-passed portion through a soft-clipping nonlinearity, sum back to the dry signal at a mix level.
- Parameters on `ExciterPlugin`: `frequency` (crossover 1–16 kHz), `drive` (0–100%), `mix` (0–100%), `mode` (CLASS_A_TUBE / TRANSFORMER / TAPE with different harmonic spectra), `output` (-12 to +12 dB).
- Each mode uses a different polynomial / waveshaper to produce characteristic harmonic signatures.
- `ExciterPluginView`: crossover sweep, drive, mix, mode cycler; a mini FFT display showing added harmonics.
- Registered with `BuiltInPluginRegistry`.
- Oversampling (integrated with story 106's oversampler) to prevent aliasing from the nonlinearity.
- Persist state via `ProjectSerializer`.
- Tests: drive=0 is unity (mix irrelevant); drive=50%+mix=100% produces measurable 2nd and 3rd harmonics at the expected frequencies; each mode has distinguishable spectra on identical input.

## Non-Goals

- Amp-modeling-grade saturation (story 106 covers full waveshaping).
- Dynamic excitation (amount varies with input level) beyond per-sample waveshaper response.
- LF exciter / sub-bass enhancer (a separate story; this is HF-only per tradition).
