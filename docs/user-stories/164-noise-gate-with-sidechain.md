---
title: "Noise Gate Built-In Plugin with Sidechain Input and Hysteresis"
labels: ["enhancement", "plugins", "built-in", "dsp"]
---

# Noise Gate Built-In Plugin with Sidechain Input and Hysteresis

## Motivation

A noise gate silences audio below a threshold — essential for vocal mics picking up room hiss, drum mics bleeding between hits, and guitar amp hum between phrases. The classic gate adds hysteresis (the open and close thresholds differ) to prevent chattering on signals that hover near threshold, and supports sidechain input so (e.g.) a kick-mic gate can be triggered by a clean kick-in trigger rather than the bleeding overhead mic it is filtering.

`NoiseGateProcessor` may already exist in basic form; this story is the feature-complete built-in plugin with sidechain and UI.

## Goals

- Audit / extend `NoiseGateProcessor` in `com.benesquivelmusic.daw.core.dsp.dynamics` to support: threshold, hysteresis (close-threshold offset), attack, hold, release, range (floor depth in dB), lookahead, and external-sidechain routing from story 091.
- `NoiseGatePlugin` parameters (`@AutomationParameter`): threshold, hysteresis, attack, hold, release, range, lookahead, sidechainEnabled, sidechainFilterFreq, sidechainFilterQ.
- Sidechain filter: bandpass on the sidechain signal so the gate opens only for kick-like frequencies (e.g., 50–100 Hz for a kick gate).
- `NoiseGatePluginView`: knobs for each parameter, gate status LED (open/closed), level meter with threshold line.
- Registered with `BuiltInPluginRegistry`.
- Persist state via `ProjectSerializer`.
- Tests: signal rising above threshold opens the gate within `attack` time; signal falling below `close threshold` (threshold - hysteresis) closes the gate after `hold + release`; sidechain with filter triggers only on in-band content.

## Non-Goals

- Multi-band gating.
- Duck mode (inverted gate — separate story; conceptually a different tool).
- Envelope-controlled gate (MIDI-driven gate via noteon — future).
