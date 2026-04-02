---
title: "Mastering Chain Live Audio Processing and Gain Reduction Metering"
labels: ["enhancement", "ui", "mastering", "dsp", "metering"]
---

# Mastering Chain Live Audio Processing and Gain Reduction Metering

## Motivation

User story 015 describes a mastering chain view with presets and A/B comparison. The `MasteringView` exists and renders a multi-stage mastering signal chain (EQ, Compression, Stereo Width, Saturation, Limiter, etc.) with per-stage controls, an A/B comparison toggle, and preset management. However, each stage is populated with a `PlaceholderProcessor` — a no-op `AudioProcessor` that passes audio through unchanged. The gain reduction meters display static "0.0 dB" placeholder labels. The mastering chain is not connected to the `AudioEngine`'s master effects chain, so none of the mastering settings affect the audio output. Users can adjust knobs and toggle stages but hear no difference in the audio. In a professional mastering workflow, the mastering chain must process audio in real time with accurate gain reduction and level metering so the engineer can hear and see the effect of each stage.

## Goals

- Replace the `PlaceholderProcessor` in each mastering stage with the corresponding real DSP processor from `daw-core/dsp` — map EQ stage to `ParametricEqProcessor`, Compression to `CompressorProcessor`, Limiter to `LimiterProcessor`, Saturation to `SaturationProcessor`, etc.
- Wire the mastering chain stages into the `AudioEngine`'s master `EffectsChain` so that audio passing through `processBlock()` is processed by the mastering chain in real time
- Implement real-time gain reduction metering for the Compressor and Limiter stages — read the gain reduction value from the processor after each block and display it on the corresponding meter label
- Implement real-time input/output level metering for each stage so users can see signal levels at each point in the chain
- Ensure the A/B comparison toggle correctly swaps between two mastering chain configurations so users can hear the difference
- Ensure per-stage bypass toggles remove the processor from the signal path without removing it from the UI
- Update meters at the UI refresh rate (e.g., 30 Hz) without blocking the audio thread

## Non-Goals

- Third-party plugin hosting in the mastering chain (only built-in DSP processors)
- Loudness-matched A/B comparison (automatically leveling A and B for fair comparison)
- Mastering for surround/immersive formats (stereo mastering only)
