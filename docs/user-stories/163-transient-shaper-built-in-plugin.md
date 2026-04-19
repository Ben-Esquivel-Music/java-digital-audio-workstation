---
title: "Transient Shaper Built-In Plugin (Attack / Sustain Shaping)"
labels: ["enhancement", "plugins", "built-in", "dsp"]
---

# Transient Shaper Built-In Plugin (Attack / Sustain Shaping)

## Motivation

A transient shaper is a level-independent tool that boosts or suppresses the attack and sustain portions of a signal — invaluable for making a mushy kick punchier or taming a ringy snare. Unlike a compressor, it does not care about absolute level; it tracks envelope differentiation. Native Instruments' Transient Master, SPL Transient Designer, Logic Enveloper — every DAW ships one in some form.

## Goals

- Add `TransientShaperProcessor` in `com.benesquivelmusic.daw.core.dsp.dynamics` implementing dual-envelope transient detection (fast vs slow follower) with level-independent attack/sustain gain modulation.
- Parameters on `TransientShaperPlugin`: `attack` (-100% to +100%), `sustain` (-100% to +100%), `output` (-12 to +12 dB), `inputMonitor` (solos the transient-detection envelope for tuning).
- Dual-envelope algorithm: fast follower minus slow follower yields the transient energy; attack knob scales this energy's gain multiplier.
- Stereo link: independent L/R detection vs summed detection (channel link 0-100%).
- `TransientShaperPluginView`: two large knobs (ATTACK, SUSTAIN), output trim, input/output meters.
- Registered with `BuiltInPluginRegistry`.
- Persist state via `ProjectSerializer`.
- Tests: on a kick drum impulse, attack=+100% measurably increases peak-to-RMS ratio; attack=-100% reduces it; sustain=+100% extends the decay tail measured via RMS envelope.

## Non-Goals

- Per-band transient shaping (future multi-band story).
- Transient-preserving pitch-shift (unrelated).
- Auto-detection of signal type to set intelligent defaults.
