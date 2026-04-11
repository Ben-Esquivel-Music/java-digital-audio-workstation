---
name: research-daw
description: Consult DAW architecture research when making design decisions (module structure, plugin systems, real-time audio, project formats, audio I/O). Based on analysis of Audacity, Ardour, LMMS, OpenDAW, and 13+ open source DAW platforms.
argument-hint: [architecture topic or design question]
allowed-tools: Read Grep Glob
---

# Research: Open Source DAW Architecture

Consult the DAW architecture research to inform design decisions about **$ARGUMENTS**.

## Primary Source

Read `docs/research/open-source-daw-tools.md` for the full DAW ecosystem analysis based on OpenDAW.org and Yuan-ManX/audio-development-tools.

## Key Reference Sections

### OpenDAW Analysis
- Architecture: studio-core, studio-sdk, lib-dsp, lib-xml, lib-box
- Instruments: Playfield (drum sampler), Nano (sampler), Tape (clip playback), Soundfont Player
- Effects: Delay, Crusher, Cheap Reverb, Stereo Tool
- Design principles: Privacy-first, local-first, open source, SDK-first, DAWproject support

### Open Source DAW Catalog (13+ platforms)
- Full DAWs: Audacity, Ardour, LMMS, Qtractor, Tracktion, Mixxx, Radium, Bass Studio, GridSound, Meadowlark, Jackdaw, TuneFlow, ossia score
- Plugin frameworks: JUCE, iPlug 2, DPF, vst3sdk, LV2, CLAP
- Extension/scripting: reapy, reaper-sdk, AbletonParsing, PyFLP, Ableton.js

### Architectural Patterns (5 Key Lessons)
1. **Modular Architecture (SDK + Core + App)** — This project already follows this pattern
2. **Plugin System Design** — ServiceLoader, hot-loading, sandboxing, standard APIs (CLAP/LV2)
3. **Real-Time Audio Processing** — Lock-free audio thread, ring buffers, fixed-size blocks, priority scheduling
4. **Project File Format** — DAWproject for interoperability, versioned custom format
5. **Audio I/O Abstraction** — PortAudio, JACK, ASIO, CoreAudio, Java Sound API

### Feature Comparison Matrix
- Compares: Multi-track recording, MIDI, plugin hosting, non-destructive editing, mixer, automation, spatial audio, cross-platform

### Implementation Recommendations
- From OpenDAW: SDK-first API, modular instruments/effects, DAWproject, education features
- From ecosystem: Plugin hosting (LV2/CLAP), audio I/O abstraction, lock-free engine, automation, session management

## Instructions

1. Read `docs/research/open-source-daw-tools.md` and find patterns relevant to **$ARGUMENTS**
2. Check the AES Conference Papers table for relevant architecture research
3. Cross-reference with `docs/research/audio-development-tools.md` for tool choices
4. Provide architecture guidance including:
   - How other open source DAWs solve this problem
   - Recommended architectural pattern with rationale
   - Alignment with this project's existing SDK/Core/App structure
   - Trade-offs and alternatives considered
   - Relevant tools or libraries for implementation
