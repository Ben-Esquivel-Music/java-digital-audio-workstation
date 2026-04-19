---
title: "True-Peak Brickwall Limiter Built-In Plugin with ISP Detection"
labels: ["enhancement", "plugins", "built-in", "dsp", "mastering", "loudness"]
---

# True-Peak Brickwall Limiter Built-In Plugin with ISP Detection

## Motivation

A brickwall limiter is the final stage of every mastering chain: it transparently holds output below a ceiling (usually -1.0 dBTP for streaming deliverables per AES TD1004.1.15-10). Sample-peak limiting is not enough — inter-sample peaks (ISPs) can exceed 0 dBFS after DAC reconstruction and cause downstream clipping. Every modern mastering tool (FabFilter Pro-L, Waves L2, iZotope Ozone Maximizer) uses 4× or higher oversampling for true-peak detection.

`SimpleLimiterProcessor` exists in `daw-core.dsp` but does not handle ISPs and is not wired as a first-class built-in plugin.

## Goals

- Add `TruePeakLimiterProcessor` in `com.benesquivelmusic.daw.core.dsp.dynamics` implementing a lookahead limiter with 4× oversampled true-peak detection.
- Parameters on `TruePeakLimiterPlugin`: `ceiling` (-3.0 to 0.0 dBTP), `release` (1–1000 ms), `lookahead` (1–10 ms; reports PDC via story 090), `isr` (oversampling factor 2/4/8), `channelLink` (0–100%, how linked L/R reduction is).
- Soft-knee compression curve near the ceiling to minimize distortion on peaky material.
- Gain-reduction meter, true-peak input/output meters (integrates with LUFS stories 014 / 166).
- `TruePeakLimiterPluginView`: ceiling knob, release knob, metering, A/B compare.
- Registered with `BuiltInPluginRegistry`; annotated with `@BuiltInPlugin(category = MASTERING)`.
- Persist state via reflective preset serializer (story 110).
- Tests: on a test signal with known ISP +0.8 dBTP, output does not exceed the configured ceiling at 4× ISR within 0.1 dB; bypass is bit-exact.

## Non-Goals

- Multiband limiting (story 160 covers multiband compressor; multiband limiter is a future story).
- Clipper mode (hard-knee saturating clip — separate story).
- AI-driven release auto-tuning.
