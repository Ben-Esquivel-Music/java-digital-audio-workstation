---
title: "Waveshaper and Saturation Built-In Plugin with Oversampling"
labels: ["enhancement", "plugins", "dsp"]
---

# Waveshaper and Saturation Built-In Plugin with Oversampling

## Motivation

Saturation and harmonic distortion are among the most commonly used effects in modern music production — from subtle analog warmth on vocals and buses to aggressive distortion on synths and guitars. The DAW has a `SaturationProcessor` in `daw-core/dsp`, but it operates at the native sample rate without oversampling, which introduces aliasing artifacts when the nonlinear transfer function generates harmonics above the Nyquist frequency. These artifacts are audible as harsh, inharmonic tones that degrade audio quality.

AES research on oversampled nonlinear waveshaping (Oversampling for Nonlinear Waveshaping: Choosing the Right Filters, 2019) establishes that 2x–4x oversampling with properly designed anti-aliasing filters effectively suppresses aliasing in waveshaping processors. The research recommends polyphase FIR half-band filters for best rejection and minimum-phase IIR filters for lowest latency. Additionally, research on antiderivative antialiasing (ADAA) provides an alternative zero-oversampling approach.

A dedicated waveshaper plugin with configurable oversampling, multiple transfer function shapes, and proper anti-aliasing would fill a significant gap in the built-in effect catalog.

## Goals

- Create a `WaveshaperProcessor implements AudioProcessor` in `daw-core/dsp` with configurable oversampling (1x, 2x, 4x, 8x)
- Implement polyphase FIR half-band upsampler/downsampler pairs for oversampling, using the existing `DspUtils` for coefficient computation
- Provide built-in transfer functions: soft-clip (tanh), hard-clip, tube saturation, tape saturation, and a custom curve defined by control points
- Expose parameters: drive (input gain, 0–48 dB), mix (wet/dry, 0–100%), output gain (-12 to +12 dB), oversampling factor, transfer function type
- Report accurate latency via `getLatencySamples()` based on the oversampling filter's group delay
- Create a `WaveshaperPlugin implements BuiltInDawPlugin` with `getCategory()` returning `EFFECT`, `getMenuLabel()` returning "Waveshaper / Saturation"
- Add `WaveshaperPlugin` to the `BuiltInDawPlugin` sealed `permits` clause
- Add unit tests verifying: (1) soft-clip at 0 dB drive produces unity output, (2) oversampled output has lower aliasing energy than non-oversampled, (3) latency reporting is accurate, (4) the processor is `@RealTimeSafe` at all oversampling factors
- Extend or replace the existing `SaturationProcessor` if appropriate, or position `WaveshaperProcessor` as the higher-quality alternative

## Non-Goals

- Guitar amp simulation or cabinet impulse response loading (separate feature)
- Visual transfer function curve editor in the plugin UI (use generic parameter controls initially)
- Multi-band saturation (processing different frequency bands with different drive amounts)
- ADAA as an alternative to oversampling (can be added as a future enhancement)
