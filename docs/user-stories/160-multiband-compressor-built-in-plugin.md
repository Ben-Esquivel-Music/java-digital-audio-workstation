---
title: "Multiband Compressor Built-In Plugin"
labels: ["enhancement", "plugins", "built-in", "dsp", "mastering"]
---

# Multiband Compressor Built-In Plugin

## Motivation

A multiband compressor splits the signal into frequency bands and applies independent dynamics to each, allowing surgical control (squash the low end without pumping the cymbals). It is a core mastering tool and a flexible mixing tool for complex sources like drum busses and vocals. FabFilter Pro-MB, iZotope Ozone's Multiband, Logic's Multipressor — every DAW ships one or lists this as a top requested feature. The existing single-band compressor processor can be leveraged via parallel band instances.

## Goals

- Add `MultibandCompressorProcessor` in `com.benesquivelmusic.daw.core.dsp.dynamics` with configurable 3–5 bands and Linkwitz-Riley 4th-order crossovers.
- Each band is an independent `CompressorProcessor` instance with its own threshold/ratio/attack/release/makeup.
- Parameters on `MultibandCompressorPlugin`: `bandCount` (3/4/5), crossover frequencies (per pair), per-band parameters (threshold/ratio/attack/release/makeup/bypass/mute/solo).
- Band solo/mute for A/B comparison during tuning.
- `MultibandCompressorPluginView`: spectrum display with draggable crossover markers; per-band gain-reduction meters; per-band knob cluster.
- Linear-phase crossover option for mastering contexts (at the cost of latency, reported via PDC story 090).
- Registered with `BuiltInPluginRegistry` via the annotation-driven discovery from story 112.
- Persist state via `ProjectSerializer` using the reflective preset serializer (story 110).
- Tests: bit-exact reconstruction of input when all bands bypassed; crossover frequencies match target within FIR/IIR tolerances; null test passes when all thresholds are +12 dB (no compression).

## Non-Goals

- Dynamic EQ (sidechain-driven narrow-band EQ) — separate future story.
- Per-band oversampling independently (global oversampling only).
- Linked-band gain reduction (each band is independent).
