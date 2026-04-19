---
title: "Convolution Reverb Built-In Plugin with IR Loading and Trimming"
labels: ["enhancement", "plugins", "built-in", "dsp", "reverb"]
---

# Convolution Reverb Built-In Plugin with IR Loading and Trimming

## Motivation

Convolution reverb applies an impulse response (IR) of a real space or hardware reverb to a signal, producing accurate spatial reproduction impossible with algorithmic reverb. Altiverb, Waves IR-1, Logic's Space Designer, Reaper's ReaVerb — every DAW offers it. Combined with a library of IRs (churches, plate reverbs, guitar cabinets), it is a uniquely powerful creative tool. Story 105 integrates `daw-acoustics` for simulated rooms; convolution reverb consumes *captured* room responses instead.

## Goals

- Add `ConvolutionReverbProcessor` in `com.benesquivelmusic.daw.core.dsp.reverb` implementing partitioned FFT-based convolution for efficient long-IR processing (impulse responses up to 10 s at 48 kHz).
- Parameters on `ConvolutionReverbPlugin`: IR file path, stretch (0.5× to 2.0× IR length), predelay (0–200 ms), low-cut, high-cut, dry/wet, stereo width, trim (IR start/end).
- `ConvolutionReverbPluginView`: waveform display of the loaded IR with draggable trim markers; file browser / drop target; parameter knobs; stretch slider.
- Bundled IR library under `daw-core/src/main/resources/impulse-responses/` with 6–10 representative rooms and plates.
- Non-realtime IR preparation (FFT partitioning, normalization) runs on a virtual thread (story 205) to keep UI responsive when loading long IRs.
- PDC reporting (story 090) honors partition size; the report is accurate to the sample.
- Persist IR file reference + parameters via `ProjectSerializer`; relative paths inside the project bundle for portability.
- Tests: loading a known-length IR reports the correct length; null test with a single-sample IR reproduces input scaled by that sample; stretch factor 2× produces a 2× longer IR with predictable spectral shift.

## Non-Goals

- True-stereo (4-channel) IRs beyond simple mono-to-stereo / stereo-to-stereo.
- Sampled reverb (sampled late reverb resynthesis) — that is a different class of algorithm.
- Real-time IR capture via sine-sweep (a separate measurement-tool story).
