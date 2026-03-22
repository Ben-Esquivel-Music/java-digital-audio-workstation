# Enhancement: FluidSynth JNI Integration for MIDI Rendering

## Summary

Integrate [FluidSynth](https://www.fluidsynth.org/) via JNI to provide high-quality SoundFont-based MIDI rendering. FluidSynth is the industry-standard open-source SoundFont 2 synthesizer, enabling MIDI tracks to be rendered with any SF2 soundfont for realistic instrument playback and audio bouncing.

## Motivation

The Java Sound API provides basic MIDI support via `javax.sound.midi`, but its built-in synthesizer uses low-quality General MIDI samples. FluidSynth supports the full SoundFont 2 specification with high-quality sample playback, real-time MIDI rendering, multi-channel output, and effects processing. Integrating FluidSynth would give the DAW professional-quality MIDI instrument rendering comparable to commercial DAWs, with access to thousands of freely available SoundFont libraries.

## Research Sources

- [Audio Development Tools](../research/audio-development-tools.md) — Phase 1 Core Audio Engine: "MIDI synthesis → FluidSynth → JNI bindings"
- [Audio Development Tools](../research/audio-development-tools.md) — "FluidSynth: Real-time SoundFont 2 synthesizer — **High** relevance, SoundFont playback via JNI"
- [Research README](../research/README.md) — Near-Term #2: "JNI bindings for FluidSynth (MIDI rendering)"
- [Open Source DAW Tools](../research/open-source-daw-tools.md) — OpenDAW includes a "Soundfont Player" instrument

## Sub-Tasks

- [ ] Design `SoundFontRenderer` interface in `daw-sdk` for SoundFont-based MIDI playback
- [ ] Implement JNI bridge for FluidSynth core functions (fluid_synth_new, fluid_synth_sfload, fluid_synth_noteon/noteoff, fluid_synth_write_float)
- [ ] Implement SoundFont loading and management (load/unload SF2 files, list presets/banks)
- [ ] Implement MIDI event routing to FluidSynth (note on/off, CC, pitch bend, program change)
- [ ] Implement real-time audio rendering from FluidSynth into the DAW's audio pipeline
- [ ] Implement multi-channel FluidSynth output (16 MIDI channels with independent routing)
- [ ] Implement SoundFont preset selection per MIDI channel (bank + program)
- [ ] Implement FluidSynth built-in effects control (reverb, chorus) or bypass for DAW-native effects
- [ ] Implement MIDI track bounce-to-audio using FluidSynth rendering
- [ ] Add fallback to Java Sound API synthesizer when FluidSynth native library is unavailable
- [ ] Build native FluidSynth JNI library for Windows, macOS, and Linux
- [ ] Package a default General MIDI SoundFont for out-of-the-box functionality
- [ ] Add unit tests for MIDI event routing correctness
- [ ] Add integration tests for SoundFont loading and audio rendering
- [ ] Document SoundFont installation, preset selection, and MIDI channel configuration

## Affected Modules

- `daw-sdk` (new `midi/SoundFontRenderer` interface)
- `daw-core` (new `midi/fluidsynth/` package, integration with `audio/AudioEngine`)
- `daw-app` (SoundFont browser UI, MIDI track instrument selector)
- New native module for FluidSynth JNI bridge

## Priority

**Near-Term** — Significant improvement for MIDI workflow; requires JNI native builds
