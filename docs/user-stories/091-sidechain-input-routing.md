---
title: "Sidechain Input Routing for Compressor and Dynamics Processing"
labels: ["enhancement", "mixer", "dsp", "core"]
---

# Sidechain Input Routing for Compressor and Dynamics Processing

## Motivation

Sidechain compression is one of the most essential mixing techniques in modern music production. It allows the compressor on one track (e.g., a bass synth) to be triggered by the signal from another track (e.g., a kick drum), creating the characteristic "pumping" effect used in electronic, pop, and hip-hop music. It is also used for ducking dialogue over music, de-essing with a filtered sidechain, and frequency-dependent compression.

The `CompressorProcessor` in `daw-core` operates on a single stereo input — it detects the envelope of its own input signal and applies gain reduction accordingly. There is no way to feed a different signal as the detection source. The `AudioProcessor` interface's `process(float[][] input, float[][] output, int numFrames)` signature only provides a single input, with no sidechain bus.

Professional DAWs (Pro Tools, Logic, Ableton, Reaper, Ardour) allow any bus or track output to be routed as a sidechain input to any dynamics processor. The sidechain signal drives the detector while the main signal path passes through the gain reduction stage.

## Goals

- Extend the `AudioProcessor` interface (or create a `SidechainAwareProcessor` sub-interface) with a `processSidechain(float[][] input, float[][] sidechain, float[][] output, int numFrames)` method
- Update `CompressorProcessor` to implement `SidechainAwareProcessor`: when a sidechain input is provided, use it for envelope detection instead of the main input
- Similarly update `GateProcessor` to support sidechain triggering
- Add a sidechain source selector to the insert slot UI — a dropdown that lists available mixer channels and buses as potential sidechain sources
- In `Mixer.mixDown()`, when a channel's insert contains a `SidechainAwareProcessor` with a configured sidechain source, pass the source channel's audio as the sidechain buffer
- Pre-allocate sidechain routing buffers so the processing path remains `@RealTimeSafe`
- Add an `InsertSlot.setSidechainSource(MixerChannel source)` method to store the routing configuration
- Include sidechain routing in project serialization so it persists across save/load
- Add tests verifying: (1) sidechain compression reduces gain based on the sidechain signal, (2) no sidechain falls back to internal detection, (3) sidechain source changes take effect on the next block

## Non-Goals

- External sidechain from hardware inputs (requires audio I/O routing — separate story)
- Sidechain for effects other than dynamics processors (reverb ducking, etc. — future enhancement)
- Multi-band sidechain processing
- Visual sidechain signal display in the plugin parameter editor
