---
title: "Ambisonic Decoder Routing (B-Format to 5.1 / 7.1.4 / Binaural)"
labels: ["enhancement", "spatial", "immersive", "routing", "ambisonics"]
---

# Ambisonic Decoder Routing (B-Format to 5.1 / 7.1.4 / Binaural)

## Motivation

Ambisonics (B-format) is widely used in VR, 360° video, and immersive music for its speaker-layout-independent encoding: one recording plays correctly in stereo, headphones, or any surround system via a decoder. The current DAW has no path to host ambisonic content. Reaper has native ambisonic tracks, Nuendo has Ambisonic tracks with AmbiDecoder, IEM Plug-in Suite adds this to any DAW. Supporting ambisonics expands the DAW to the VR/AR and spatial music markets.

## Goals

- Add `AmbisonicOrder` enum in `com.benesquivelmusic.daw.sdk.spatial`: `FIRST_ORDER` (4 channels), `SECOND_ORDER` (9), `THIRD_ORDER` (16).
- Add `AmbisonicTrack` specialization of `Track` that holds an N-channel audio stream in ACN/SN3D encoding (the modern open standard).
- `AmbisonicDecoder` sealed interface with permitted variants: `StereoUhj`, `Binaural5`, `BinauralHrtf(HrtfProfile)`, `LoudspeakerRig(SpeakerLayout)`.
- Decoders consume B-format and produce the target channel layout; decoder choice selects the appropriate mathematical matrix.
- Mixer view: `AmbisonicTrack`s show as an N-channel strip with an "output decoder" selector.
- The decoded output is the signal that feeds the session's monitoring path (bed bus or stereo monitoring).
- Encode support: mono-to-ambisonic panner that places a mono source at an azimuth/elevation and encodes to ACN/SN3D.
- Import `.amb` and multi-channel WAV files with "ambiX" ordering.
- Persist `AmbisonicTrack` (order, channel assignments, decoder choice) via `ProjectSerializer`.
- Tests: round-trip a mono source through encode → decode (stereo UHJ) produces the expected L/R balance for a given azimuth; order-mismatched decoder signals a clear error.

## Non-Goals

- 4th+ order ambisonics (diminishing returns for typical use).
- FuMa encoding (legacy, superseded by ACN/SN3D — not supported).
- Binaural head-tracking (that is a separate future story after 174).
