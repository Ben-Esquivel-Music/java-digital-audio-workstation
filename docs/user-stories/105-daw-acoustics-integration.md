---
title: "Integrate daw-acoustics Module into Core Audio Pipeline"
labels: ["enhancement", "audio-engine", "spatial-audio", "dsp", "core"]
---

# Integrate daw-acoustics Module into Core Audio Pipeline

## Motivation

The `daw-acoustics` module is a substantial standalone acoustics library (room simulation, FDN reverb, binaural rendering, IEM Ambisonics, diffraction modeling, DSP filters) with its own test suite. However, it has **no integration points** with `daw-core` or the audio engine. The room simulation computations in `SoundWaveTelemetryEngine` are used only for the telemetry visualization — they do not process audio. The `FdnReverbProcessor`, binaural renderer, and other acoustic processors in `daw-acoustics` are never instantiated or called from the playback pipeline.

Meanwhile, `daw-core` has its own simpler `ReverbProcessor` and `SpatialAudioProcessor` that are used in the mixer and spatial panning. The `daw-acoustics` module's more sophisticated algorithms (frequency-dependent FDN reverb, HRTF-based binaural rendering, early reflection modeling) sit unused.

For a world-class DAW with immersive audio capabilities, the acoustic simulation module should be available as audio processors that can be inserted into the mixer signal chain — e.g., a convolution reverb driven by the room simulation, a binaural monitoring processor for headphone preview of spatial mixes, or an early reflections processor for realistic room emulation.

## Goals

- Create adapter classes in `daw-core` that wrap `daw-acoustics` processors as `AudioProcessor` implementations usable in the mixer insert chain
- Create an `AcousticReverbProcessor implements AudioProcessor` that wraps the `daw-acoustics` FDN reverb with room-dimension-aware parameter presets
- Create a `BinauralMonitoringProcessor implements AudioProcessor` that wraps the `daw-acoustics` binaural renderer for headphone monitoring of multi-channel or spatial audio
- Create corresponding `BuiltInDawPlugin` implementations: `AcousticReverbPlugin` and `BinauralMonitorPlugin`, added to the sealed permits clause
- Ensure the `daw-core` module has a proper Maven dependency on `daw-acoustics`
- The acoustic processors should be available as insert effects on mixer channels and as options in the spatial panner's rendering backend
- Add integration tests verifying: (1) acoustic reverb produces non-zero output with correct decay characteristics, (2) binaural processor produces stereo output from mono input with HRTF-based spatialization
- Document the module boundary: `daw-acoustics` remains a standalone library with no dependencies on `daw-core` or `daw-app`; adapters live in `daw-core`

## Non-Goals

- Replacing the existing `ReverbProcessor` in `daw-core` (it remains as a simpler, lower-CPU alternative)
- Real-time room geometry editing that feeds into the audio reverb (the telemetry visualization remains separate)
- Ambisonics encoding/decoding in the mixer (separate spatial audio story)
- Head-tracking integration for binaural rendering (requires external hardware/software)
