---
title: "ASIOSampleType Conversion at the Buffer-Switch Boundary (Int16/Int24/Int32/Float32/Float64, LSB & MSB)"
labels: ["bug", "audio-engine", "native", "windows", "asio", "dsp"]
---

# ASIOSampleType Conversion at the Buffer-Switch Boundary (Int16/Int24/Int32/Float32/Float64, LSB & MSB)

## Motivation

Story 311 implements the ASIO `bufferSwitch` streaming loop, copying samples between the driver's I/O buffers and the engine's `AudioBlock`s. But ASIO drivers do **not** all hand the host the same sample format. The Steinberg SDK's `ASIOSampleType` (already surfaced as the `type` field of the 56-byte `ASIOChannelInfo` struct decoded by `asioshim_getChannelInfo` in stories 215 / 223) can be any of a dozen layouts, and a single interface commonly exposes a non-float native format:

- `ASIOSTInt16LSB` / `ASIOSTInt16MSB`
- `ASIOSTInt24LSB` / `ASIOSTInt24MSB` (packed 3-byte samples)
- `ASIOSTInt32LSB` / `ASIOSTInt32MSB` and the right-justified `ASIOSTInt32LSB16/18/20/24` variants
- `ASIOSTFloat32LSB` / `ASIOSTFloat32MSB`
- `ASIOSTFloat64LSB` / `ASIOSTFloat64MSB`

The DAW's mix bus is float32 (and 64-bit internally per story 127). If story 311 copies driver buffers verbatim assuming `Float32LSB`, then on the very common case of an interface delivering `Int24LSB` or `Int32LSB` the audio is reinterpreted as garbage — full-scale noise on input, and on output the driver reinterprets the host's float bytes as integers (loud, potentially speaker-damaging noise). "Full ASIO driver support" must convert each channel from / to its driver-reported `ASIOSampleType`, including correct endianness, 24-bit packing, and right-justified bit-depth handling.

## Goals

- Centralise `ASIOSampleType` conversion at the buffer-switch copy boundary established by story 311, converting **per channel** using the `type` already reported by `asioshim_getChannelInfo` (stories 215 / 223) for that channel:
  - **Input (driver → engine):** decode the driver buffer for the channel's `ASIOSampleType` into normalised float32 in `[-1.0, 1.0]` before publishing the `AudioBlock` via `AudioBackendSupport#publishInput`.
  - **Output (engine → driver):** encode the engine's float32 `sink(...)` block into the channel's `ASIOSampleType`, with saturating clamp to the integer range (no wraparound) for the integer formats.
- Cover the full set of formats a Windows interface realistically uses: `Int16LSB/MSB`, `Int24LSB/MSB` (3-byte packed), `Int32LSB/MSB`, the right-justified `Int32LSB16/18/20/24` variants, `Float32LSB/MSB`, and `Float64LSB/MSB`. Endianness is handled explicitly (byte-swap on the MSB variants and on big-endian-only host builds); on the only supported target (x64 Windows, little-endian) the LSB variants are the fast no-swap path.
- Decide and document the conversion locus. Preferred: implement the conversion in native code inside the shim's `bufferSwitch` trampoline (story 311) so only normalised float32 ever crosses the FFM boundary, keeping the per-callback Java work allocation-free and format-agnostic. Add a shim export `asioshim_getChannelSampleType(int channelIndex, int isInput, int* outType)` (or reuse the `type` already returned by `asioshim_getChannelInfo`) so the conversion table is driven by the driver's report, not guessed.
- Real-time safety: conversion runs on the driver's `bufferSwitch` callback thread; it must be branch-on-format-once (resolve the per-channel converter at `ASIOCreateBuffers` time, story 311) and then allocation-free and lock-free in the hot path, consistent with the project's `@RealTimeSafe` conventions.
- Round-trip integrity: a float32 buffer encoded to an integer `ASIOSampleType` and decoded back is within that format's quantisation error of the original (bit-exact for `Float32LSB`). 24-bit packing must consume / produce exactly 3 bytes per sample with the correct sign extension.

## Goals — Tests

- Pure-Java (or pure-native) unit tests over each `ASIOSampleType`: a known float pattern (full-scale, −full-scale, zero, ±0.5, a sine) encodes and decodes round-trip within the format's quantisation tolerance; full-scale-plus-epsilon clamps (no wraparound) for integer formats.
- A 24-bit packing test asserts byte-exact little-endian layout for `Int24LSB` and correct sign extension of negative samples.
- An endianness test asserts `Int16MSB` / `Int32MSB` byte order differs from the LSB variant by the expected swap.
- An integration-style test wires a stub driver buffer of a non-float type (e.g. `Int24LSB`) through the story-311 buffer-switch bridge and asserts the published `AudioBlock` is correctly normalised float32 (and the reverse for `sink`).

## Non-Goals

- The buffer-switch loop itself, `ASIOCreateBuffers` / `ASIOStart` / `ASIOStop` — owned by story 311 (prerequisite); this story only fills in the per-sample conversion at that boundary.
- Sample-**rate** conversion — the driver runs at the negotiated rate (stories 213 / 220 / 258); resampling at bus boundaries is story 126's concern, not sample-format conversion.
- Dithering on bit-depth reduction — story 167 (Dithered Bit-Depth Reduction Stage) owns dithering; this story does straight saturating quantisation at the hardware boundary.
- Non-Windows targets; ASIO is Windows-only. (CoreAudio / JACK backends handle their own formats.)
- Exotic / deprecated `ASIOSampleType`s with no realistic Windows hardware (e.g. DSD types) — return a clear `AudioBackendException` for an unsupported reported type rather than mis-decoding it.

## Technical Notes

- Files: `daw-core/native/asio/asioshim.cpp` + `asioshim.h` (per-channel converter selection in the `bufferSwitch` trampoline; optional `asioshim_getChannelSampleType` export), and/or a Java converter alongside `daw-sdk/.../AsioBackend.java` if any conversion remains on the Java side; the `ASIOChannelInfo.type` field is already decoded by the story-215 / 223 channel-info path.
- The `ASIOSampleType` enum values are defined in the Steinberg SDK's `asio.h`; the existing `asioshim_getChannelInfo` already passes the driver's `type` across the FFM boundary as a normalised int32, so the conversion table can key off that value directly.
- Resolve each channel's converter once (at `ASIOCreateBuffers`, story 311) to keep the `bufferSwitch` hot path branch-free; do not switch on `ASIOSampleType` per sample.
- Research backing: `docs/research/audio-development-tools.md` (JAsioHost, relevance High) handles exactly this `ASIOSampleType` → float conversion matrix in its Java ASIO host and is the reference for the format coverage list.
- Reference original stories: **311 — ASIO Real-Time Audio Streaming** (prerequisite), **215 / 223 — Driver-Reported Channel Names / Channel Info** (source of the per-channel `ASIOSampleType`), **127 — 64-bit Internal Mix Bus**, **167 — Dithered Bit-Depth Reduction** (complementary, not overlapping).
