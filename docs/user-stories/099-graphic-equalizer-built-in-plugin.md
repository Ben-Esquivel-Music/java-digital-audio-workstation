---
title: "Graphic Equalizer Built-In Plugin with Octave and Third-Octave Bands"
labels: ["enhancement", "plugins", "dsp"]
---

# Graphic Equalizer Built-In Plugin with Octave and Third-Octave Bands

## Motivation

The DAW includes a `ParametricEqProcessor` for fully configurable parametric EQ, but no graphic equalizer. Graphic EQs are a staple of audio production workflows — they provide fixed-frequency band sliders (10 bands for octave, 31 bands for third-octave) that are faster and more intuitive for broad tonal shaping than parametric EQ. Live sound engineers, mastering engineers applying room correction, and producers making quick tonal adjustments all reach for graphic EQs regularly.

AES research on linear-phase graphic equalizer design (Linear-Phase Octave Graphic Equalizer, 2022) and digitally controlled graphic EQ (Design of a Digitally Controlled Graphic Equalizer, 2017) provides well-established algorithms for flat-sum magnitude response and optimal band interaction. The existing `BiquadFilter` and `LinearPhaseFilter` infrastructure in `daw-core/dsp` provides the building blocks for implementation.

## Goals

- Create a `GraphicEqProcessor implements AudioProcessor` in `daw-core/dsp` with two modes: octave (10 bands, 31.5 Hz – 16 kHz) and third-octave (31 bands, 25 Hz – 20 kHz) using ISO standard center frequencies
- Each band is backed by a peak/bell biquad filter from the existing `BiquadFilter` class
- Gain range: ±12 dB per band with 0.5 dB resolution
- Optional linear-phase mode that converts the combined biquad response to a symmetric FIR filter
- Create a `GraphicEqPlugin implements BuiltInDawPlugin` that wraps the processor, with `getCategory()` returning `EFFECT`, `getMenuLabel()` returning "Graphic EQ", and `getMenuIcon()` returning `"eq"`
- Add `GraphicEqPlugin` to the `BuiltInDawPlugin` sealed `permits` clause
- The plugin exposes per-band gain parameters through `getParameters()` so the `PluginParameterEditorPanel` can render a slider for each band
- Add unit tests verifying: (1) each band boosts/cuts at the correct center frequency, (2) flat settings produce unity gain, (3) the processor is `@RealTimeSafe`
- Add the plugin to the `BuiltInDawPlugin` sealed interface's permits list for automatic discovery

## Non-Goals

- Custom graphic EQ UI with a frequency response curve display (use the generic parameter editor initially)
- Real-time spectrum analyzer overlay on the EQ display
- Preset management specific to the graphic EQ (use the standard plugin preset system)
- Room correction or measurement-based automatic EQ curves
