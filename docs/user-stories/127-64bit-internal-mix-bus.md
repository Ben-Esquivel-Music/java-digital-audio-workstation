---
title: "64-Bit Double-Precision Internal Mix Bus"
labels: ["enhancement", "audio-engine", "dsp", "quality"]
---

# 64-Bit Double-Precision Internal Mix Bus

## Motivation

`MixerChannel.process` and `RenderPipeline` currently sum audio in 32-bit `float`. For sessions with a small number of tracks this is transparent, but summing 64+ tracks with headroom reserved for mastering reliably accumulates error in the low bits and loses resolution during dynamic processing. Every professional DAW has moved its summing bus to 64-bit `double`: Pro Tools HDX, Logic, Cubase, Studio One, Reaper, and Ableton all sum in `double` internally even when plugins process in `float`. This is a no-compromise correctness improvement.

`MixerChannel.processDouble` already exists as a fast path, and `BuiltInDawPlugin` processors expose a `supportsDouble()` capability. The work is to make double precision the default for the summing bus and the track-to-bus mix stages, while keeping per-plugin processing at the plugin's preferred precision.

## Goals

- Promote `RenderPipeline` internal buffers from `float[]` to `double[]` for all summing and gain-staging stages.
- Keep per-plugin I/O adapters that convert `double` ↔ `float` when a processor reports `supportsDouble() == false`.
- Update `AudioBufferPool` to support both `FloatBufferView` and `DoubleBufferView` record accessors over a shared backing store.
- Extend `MasterBus` and `Send` routing to operate on `double` buffers; the final hardware-output conversion to the device's native format (typically `float32`) happens once at the output stage.
- Confirm `BuiltInDawPlugin` implementations that benefit from double precision (EQ, compressor, limiter) opt in via `supportsDouble()` = true; all others convert transparently.
- Add `MixPrecision` enum (`FLOAT_32`, `DOUBLE_64`) exposed in `AudioSettingsDialog` with `DOUBLE_64` default; `FLOAT_32` retained for low-CPU machines.
- Regression tests: bit-exact comparison with prior-version golden renders on a pure `FLOAT_32` mix; a 128-track sine sum in `DOUBLE_64` mode matches analytical truth within -140 dBFS.
- Document in `java26-setup.md` that `DOUBLE_64` roughly doubles mix-bus memory bandwidth but typical total CPU impact is modest.

## Non-Goals

- 64-bit audio file I/O (export remains float-32 / int-24 / int-16 as today).
- Rewriting third-party plugin SDKs (CLAP remains float-per-the-spec unless plugin opts in).
- Fixed-point / DSD support.
