---
name: research-tools
description: Consult the audio development tools catalog when choosing libraries or tools for implementation (DSP, synthesis, spatial audio, ML, I/O, plugins, analysis). Covers 446+ tools across 13 categories with relevance assessments.
argument-hint: [tool category or capability needed]
allowed-tools: Read Grep Glob
---

# Research: Audio Development Tools Catalog

Consult the tools catalog to find the best tool/library for **$ARGUMENTS**.

## Primary Source

Read `docs/research/audio-development-tools.md` for the full tools catalog based on Yuan-ManX/audio-development-tools (446+ tools, 13 categories).

## Tool Categories (Section Index)

Search the catalog for the relevant category:

1. **Audio Signal Processing Libraries**
   - Java/JVM: TarsosDSP (pure Java DSP), JAsioHost (ASIO)
   - C/C++ JNI candidates: JUCE, PortAudio, RtAudio, FFmpeg, KFR, STK, Maximilian, miniaudio
   - Python prototyping: librosa, Pedalboard, Pyo, pymixconsole

2. **Sound Synthesis Engines** — Csound, FluidSynth, Surge, Vital, ZynAddSubFX, Dexed, Faust, SuperCollider

3. **Spatial Audio Tools** — SAF, OpenAL Soft, Steam Audio, SpatGRIS, libmysofa, ambiX, spaudiopy

4. **Audio Effects and Processing**
   - Reverb: Cloud Seed, parallel-reverb-raytracer, spring-reverb-dl-models
   - Dynamics/EQ: Equalize It, DrumFixer, JDSP4Linux
   - Multi-effect: ChowMultiTool, tuna, AudioStretchy

5. **Audio Analysis and Metering** — aubio, Essentia, Gist, Friture, audiowaveform

6. **Audio I/O and File Format Libraries** — PortAudio, RtAudio, miniaudio, FFmpeg, libsndfile, TagLib

7. **Machine Learning Audio Tools** — Matchering, Demucs, Ultimate Vocal Remover, NeuralNote, DDSP

8. **Plugin Standards and Hosting** — VST3, LV2, CLAP, AU, AAX (with hosting strategy phases)

9. **Web Audio Tools (Reference)** — Tone.js, Peaks.js, AudioMass, WadJS

## Implementation Roadmap

### Phase 1 (Core Engine): PortAudio/RtAudio, TarsosDSP, FFmpeg + libsndfile, FluidSynth, aubio
### Phase 2 (Effects): Java EQ, compressor, reverb, delay, time stretch
### Phase 3 (Advanced): CLAP/LV2 hosting, SAF spatial audio, Matchering, Demucs, NeuralNote

## Instructions

1. Read `docs/research/audio-development-tools.md` and find tools relevant to **$ARGUMENTS**
2. Focus on **Relevance** ratings (High/Medium/Low) to prioritize recommendations
3. For JNI integration candidates, note the language (C/C++) and integration approach
4. Cross-reference with the Implementation Roadmap to determine phasing
5. Provide recommendations including:
   - Best tool(s) for the need with relevance justification
   - Integration approach (pure Java dependency, JNI binding, FFM API, or process integration)
   - Alternative options and trade-offs
   - Phase in the implementation roadmap
