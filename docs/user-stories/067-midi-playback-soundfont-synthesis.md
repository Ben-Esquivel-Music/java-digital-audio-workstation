---
title: "MIDI Track Playback via SoundFont Synthesis in Audio Engine"
labels: ["enhancement", "audio-engine", "midi", "core"]
---

# MIDI Track Playback via SoundFont Synthesis in Audio Engine

## Motivation

User story 013 describes MIDI recording and piano roll note editing, and user story 043 describes SoundFont browser and MIDI instrument assignment. The core module has a complete `FluidSynthRenderer` (via FFM/JEP 454) that can load SoundFont files, select presets, send MIDI events, and render audio. `MidiClip` stores `MidiNoteData` entries, `Track` has a `SoundFontAssignment`, and the `EditorView` renders a piano roll for editing MIDI notes. However, the `AudioEngine.renderSegment()` method only reads audio data from `AudioClip.getAudioData()` — it does not process `MidiClip` note events or invoke `FluidSynthRenderer` to synthesize audio for MIDI tracks. MIDI tracks with SoundFont assignments will produce complete silence during playback because the audio engine has no code path to convert MIDI note data into audible audio via the SoundFont synthesizer. This means users can create MIDI tracks, draw notes in the piano roll, and assign SoundFont presets, but will never hear any output from those tracks.

## Goals

- Integrate `FluidSynthRenderer` into the `AudioEngine` so that MIDI tracks are synthesized during `processBlock()` alongside audio track rendering
- For each MIDI track with a `SoundFontAssignment`, load the assigned SoundFont and select the correct bank/program on the FluidSynth instance
- During `renderSegment()`, scan the track's `MidiClip` for notes that overlap the current beat range and send note-on/note-off events to the FluidSynth renderer at the correct frame positions
- Render the FluidSynth audio output into the track's pre-allocated buffer so it flows through the mixer channel strip (volume, pan, inserts, sends) like any audio track
- Fall back to a Java Sound MIDI synthesizer if the FluidSynth native library is not available on the system
- Handle SoundFont assignment changes during playback (reload the SoundFont and select the new preset without stopping the engine)
- Pre-allocate any FluidSynth rendering buffers during `AudioEngine.start()` to maintain real-time safety in the audio callback

## Non-Goals

- Real-time MIDI input monitoring (live playing through FluidSynth while recording — separate feature)
- MIDI effect processing (arpeggiators, chord generators)
- Multi-timbral SoundFont rendering (multiple presets on different MIDI channels within a single track)
