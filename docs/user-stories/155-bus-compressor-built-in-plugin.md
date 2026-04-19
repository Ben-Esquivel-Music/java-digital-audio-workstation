---
title: "SSL-Style Bus Compressor Built-In Plugin"
labels: ["enhancement", "plugins", "built-in", "dsp", "mixer"]
---

# SSL-Style Bus Compressor Built-In Plugin

## Motivation

The "mix glue" compressor — a VCA-style bus compressor modeled on the SSL 4000 G, Neve 33609, or API 2500 — is a near-universal mastering-bus / drum-bus tool. Every mix engineer reaches for it. Story 088 bridges existing DSP processors to built-in plugins, but there is no dedicated bus compressor; the closest is a generic compressor aimed at single-channel dynamics. A first-class `BusCompressorPlugin` specifically tuned for gentle, program-dependent gain reduction is a high-value addition.

## Goals

- Add `BusCompressorProcessor` in `com.benesquivelmusic.daw.core.dsp.dynamics` implementing a feedforward VCA bus compressor with auto-release envelope, program-dependent release, and smooth attack curve.
- Parameters (all `@AutomationParameter`): threshold (-40 to 0 dB), ratio (1.5, 2, 4, 10 — stepped), attack (0.1, 0.3, 1, 3, 10, 30 ms — stepped), release (0.1, 0.3, 0.6, 1.2 s + AUTO — stepped), makeup (0–24 dB), mix (0–100%).
- External sidechain input support (story 091): compressor responds to the sidechain signal instead of program material when enabled.
- Analog-style soft-knee and harmonic coloration (2nd- and 3rd-order) toggled by a "DRIVE" switch.
- Gain-reduction meter output via `PluginMeterSnapshot` record for UI consumption (drives a needle-style VU in the plugin view).
- `BusCompressorPlugin` record implementing `BuiltInDawPlugin.EffectPlugin`; registered in `BuiltInPluginRegistry`.
- `BusCompressorPluginView` JavaFX component: knobs for threshold/ratio/attack/release/makeup/mix, gain-reduction meter, drive switch.
- Golden-file regression tests: a 1 kHz tone -10 dBFS at threshold -20 / ratio 4:1 produces the expected 2.5 dB gain reduction within 0.1 dB; null test (mix=0) reproduces input bit-exact.

## Non-Goals

- Multiband bus compression (story 160 covers multiband compressor).
- Specific-vendor model emulation (this is "SSL-style," not a licensed clone).
- Oversampled processing beyond the shared `Oversampler` tier.
