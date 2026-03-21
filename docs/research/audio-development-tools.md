# Open Source Audio Development Tools

> Research catalog based on [Yuan-ManX/audio-development-tools](https://github.com/Yuan-ManX/audio-development-tools) — a comprehensive collection of 446+ tools across 13 categories

## Overview

This document catalogs open source audio development tools most relevant to building a Java-based DAW for recording, mixing, and mastering. Tools are organized by functional area and assessed for implementation relevance.

---

## Audio Signal Processing Libraries

These libraries provide core DSP primitives — the building blocks for audio effects, analysis, and manipulation.

### Java / JVM

| Tool | Description | Relevance |
|------|-------------|-----------|
| [TarsosDSP](https://github.com/JorenSix/TarsosDSP) | Java library for audio processing: pitch detection, time stretching, effects, filters | **High** — Pure Java, no external dependencies, directly usable |
| [JAsioHost](https://github.com/mhroth/jasiohost) | Java-based ASIO host for low-latency audio I/O on Windows | **High** — Enables low-latency audio via ASIO in Java |

### C/C++ (JNI Integration Candidates)

| Tool | Description | Relevance |
|------|-------------|-----------|
| [JUCE](https://github.com/juce-framework/JUCE) | Industry-standard C++ framework for audio plugins and hosts | **High** — Plugin hosting via JNI; VST3/AU/LV2 support |
| [PortAudio](http://www.portaudio.com/) | Cross-platform C audio I/O library | **High** — Low-latency audio I/O via JNI bindings |
| [RtAudio](https://github.com/thestk/rtaudio) | C++ classes providing common API for realtime audio across platforms | **High** — Alternative to PortAudio for cross-platform audio |
| [FFmpeg](https://github.com/FFmpeg/FFmpeg) | Multimedia processing: encoding, decoding, transcoding | **High** — Audio format conversion, codec support |
| [Opus](https://github.com/xiph/opus) | Modern audio compression codec | **Medium** — Compressed audio format support |
| [KFR](https://github.com/kfrlib/kfr) | High-performance DSP: FFT, sample rate conversion, FIR/IIR filters | **Medium** — Optimized DSP primitives via JNI |
| [The Synthesis ToolKit (STK)](https://github.com/thestk/stk) | Audio signal processing and synthesis classes in C++ | **Medium** — Synthesis algorithms and effects |
| [Maximilian](https://github.com/micknoise/Maximilian) | Cross-platform audio synthesis and signal processing | **Medium** — Additional synthesis capabilities |
| [Q DSP Library](https://cycfi.github.io/q/) | C++ audio DSP library focused on simplicity and quality | **Medium** — Clean DSP primitives |
| [DaisySP](https://github.com/electro-smith/DaisySP) | Open source DSP library in C++ | **Medium** — Portable DSP algorithms |
| [miniaudio](https://github.com/mackron/miniaudio) | Single-file C audio playback and capture library | **Medium** — Lightweight audio I/O option |

### Python (Prototyping and Analysis)

| Tool | Description | Relevance |
|------|-------------|-----------|
| [librosa](https://librosa.org/) | Python library for music and audio analysis | **Low** — Useful for prototyping algorithms, not runtime |
| [Pedalboard](https://github.com/spotify/pedalboard) | Python library for audio effects and VST3/AU hosting | **Low** — Reference for effect implementations |
| [Pyo](https://github.com/belangeo/pyo) | Python DSP module for real-time audio processing | **Low** — Algorithm reference |
| [pymixconsole](https://github.com/csteinmetz1/pymixconsole) | Headless multitrack mixing console in Python | **Low** — Architecture reference for mixer design |

---

## Sound Synthesis Engines

Tools for generating audio from MIDI, parameters, or algorithms.

### Directly Relevant

| Tool | Description | Relevance |
|------|-------------|-----------|
| [Csound](https://csound.com/) | Programmable sound design, synthesis, and signal processing | **High** — Can be embedded via JNI; extensive synthesis capabilities |
| [FluidSynth](https://www.fluidsynth.org/) | Real-time SoundFont 2 synthesizer | **High** — SoundFont playback via JNI; widely used for MIDI rendering |
| [Surge](https://surge-synthesizer.github.io/) | Free and open-source hybrid synthesizer | **Medium** — Reference for subtractive/wavetable synthesis design |
| [Vital](https://vital.audio/) | Spectral warping wavetable synthesizer | **Medium** — Modern synthesis approach reference |
| [ZynAddSubFX](https://zynaddsubfx.sourceforge.io/) | Open source software synthesizer (additive, subtractive, pad) | **Medium** — Comprehensive synthesis engine |
| [Dexed](https://github.com/asb2m10/dexed) | DX7-compatible FM synthesizer | **Medium** — FM synthesis reference |

### Algorithm References

| Tool | Description | Relevance |
|------|-------------|-----------|
| [Faust](https://faust.grame.fr/) | Functional audio stream programming language | **Low** — DSP algorithm prototyping and code generation |
| [SuperCollider](https://supercollider.github.io/) | Audio synthesis and algorithmic composition platform | **Low** — Synthesis algorithm reference |
| [Pure Data](https://puredata.info/) | Visual programming language for multimedia | **Low** — Patching paradigm reference |
| [ChucK](https://chuck.cs.princeton.edu/) | Strongly-timed audio programming language | **Low** — Timing model reference |

---

## Spatial Audio Tools

Tools for 3D audio, Ambisonics, binaural rendering, and immersive sound.

| Tool | Language | Description | Relevance |
|------|----------|-------------|-----------|
| [Spatial Audio Framework (SAF)](https://github.com/leomccormack/Spatial_Audio_Framework) | C/C++ | Ambisonics, HRIR, panning, room simulation | **High** — Core spatial processing via JNI |
| [OpenAL Soft](https://github.com/kcat/openal-soft) | C | 3D audio API implementation | **High** — 3D audio rendering via JNI |
| [Steam Audio](https://github.com/ValveSoftware/steam-audio) | C++ | HRTF, physics-based propagation | **Medium** — Advanced spatial features |
| [SpatGRIS](https://github.com/GRIS-UdeM/SpatGRIS) | C++ | Speaker-independent spatialization | **Medium** — Spatialization algorithm reference |
| [libmysofa](https://github.com/hoene/libmysofa) | C | SOFA HRTF file reader | **Medium** — HRTF data loading |
| [ambiX](https://github.com/kronihias/ambix) | C++ | Ambisonic VST/LV2 plugins | **Medium** — Ambisonic processing reference |
| [Omnitone](https://github.com/nicklocks/nicklockwood) | JavaScript | Web-based Ambisonic decoding | **Low** — Web approach reference |
| [spaudiopy](https://github.com/chris-hld/spaudiopy) | Python | Spatial audio encoding/decoding | **Low** — Algorithm prototyping |

---

## Audio Effects and Processing

### Reverb

| Tool | Description | Relevance |
|------|-------------|-----------|
| [Cloud Seed](https://github.com/ValdemarOrn/CloudSeed) | Algorithmic reverb for huge, endless spaces | **Medium** — Algorithmic reverb reference (C#/C++) |
| [parallel-reverb-raytracer](https://github.com/reuk/parallel-reverb-raytracer) | Raytracer for impulse responses | **Low** — Physics-based reverb research |
| [spring-reverb-dl-models](https://github.com/francescopapaleo/spring-reverb-dl-models) | ML-based spring reverb model | **Low** — ML reverb research |

### Dynamics and EQ

| Tool | Description | Relevance |
|------|-------------|-----------|
| [Equalize It](https://github.com/SmEgDm/equalize_it) | VST EQ plugin with spectrum analyzer | **Medium** — EQ and spectrum analyzer reference |
| [DrumFixer](https://github.com/jatinchowdhury18/DrumFixer) | Audio plugin for drum mixing | **Low** — Specialized processing reference |
| [JDSP4Linux](https://github.com/Audio4Linux/JDSP4Linux) | Audio effect processor for PipeWire/PulseAudio | **Low** — Linux audio effect pipeline reference |

### Multi-Effect and Utility

| Tool | Description | Relevance |
|------|-------------|-----------|
| [ChowMultiTool](https://github.com/Chowdhury-DSP/ChowMultiTool) | Multi-tool audio plugin | **Low** — Plugin architecture reference |
| [tuna](https://github.com/Theodeus/tuna) | Web Audio effects library | **Low** — Effect algorithm reference |
| [AudioStretchy](https://github.com/twardoch/audiostretchy) | Time-stretch audio without pitch change | **Medium** — Time-stretching algorithm reference |

---

## Audio Analysis and Metering

| Tool | Language | Description | Relevance |
|------|----------|-------------|-----------|
| [aubio](https://aubio.org/) | C/Python | Pitch detection, onset detection, beat tracking | **High** — Core audio analysis via JNI |
| [Essentia](http://essentia.upf.edu/) | C++/Python | Audio analysis and MIR | **Medium** — Comprehensive analysis toolkit |
| [Gist](https://github.com/adamstark/Gist) | C++ | Audio analysis library | **Medium** — Lightweight analysis |
| [Friture](https://friture.org/) | Python | Real-time audio visualization | **Low** — Visualization approach reference |
| [audiowaveform](https://github.com/bbc/audiowaveform) | C++ | Waveform generation from audio files | **Medium** — Waveform display generation |

---

## Audio I/O and File Format Libraries

| Tool | Language | Description | Relevance |
|------|----------|-------------|-----------|
| [PortAudio](http://www.portaudio.com/) | C | Cross-platform audio I/O | **High** — Primary low-latency I/O candidate |
| [RtAudio](https://github.com/thestk/rtaudio) | C++ | Cross-platform realtime audio | **High** — Alternative I/O library |
| [miniaudio](https://github.com/mackron/miniaudio) | C | Single-file audio library | **Medium** — Lightweight alternative |
| [CPAL](https://github.com/RustAudio/cpal) | Rust | Cross-platform audio I/O | **Low** — Rust ecosystem reference |
| [FFmpeg](https://github.com/FFmpeg/FFmpeg) | C | Universal audio/video processing | **High** — Format conversion and codec support |
| [libsndfile](http://www.mega-nerd.com/libsndfile/) | C | Audio file reading/writing (WAV, FLAC, OGG, etc.) | **High** — Robust audio file I/O |
| [tinytag](https://github.com/devsnd/tinytag) | Python | Audio metadata reading | **Low** — Metadata approach reference |
| [TagLib](https://github.com/taglib/taglib) | C++ | Audio metadata reading/writing | **Medium** — Metadata via JNI |

---

## Machine Learning Audio Tools

Relevant for AI-assisted features in the DAW.

| Tool | Description | Relevance |
|------|-------------|-----------|
| [Matchering](https://github.com/sergree/matchering) | Automated audio matching and mastering | **Medium** — AI-assisted mastering reference |
| [Demucs](https://github.com/facebookresearch/demucs) | Source separation (vocals, drums, bass, other) | **Medium** — Stem separation feature |
| [Ultimate Vocal Remover](https://github.com/Anjok07/ultimatevocalremovergui) | Deep neural network vocal removal | **Low** — Vocal isolation reference |
| [NeuralNote](https://github.com/DamRsn/NeuralNote) | Audio to MIDI transcription | **Medium** — Audio-to-MIDI feature |
| [DDSP](https://github.com/magenta/ddsp) | Differentiable digital signal processing | **Low** — AI synthesis research |
| [Polymath](https://github.com/samim23/polymath) | ML-powered music library management | **Low** — Sample library management reference |

---

## Plugin Standards and Hosting

| Standard | Description | Relevance to Java DAW |
|----------|-------------|----------------------|
| **VST3** | Steinberg's plugin standard; most widely supported | **High** — Must-have for plugin ecosystem access; requires C++ JNI bridge |
| **LV2** | Open source plugin standard; extensible C API | **High** — Open standard; good JNI integration candidate |
| **CLAP** | Modern open plugin standard by free-audio | **High** — Newest standard with clean C API; good JNI candidate |
| **AU (Audio Unit)** | Apple's plugin format for macOS/iOS | **Medium** — macOS-specific; JNI bridge needed |
| **AAX** | Avid's Pro Tools plugin format | **Low** — Proprietary; complex licensing |

### Hosting Strategy for Java DAW
1. **Phase 1:** Internal plugin API via `daw-sdk` (current approach)
2. **Phase 2:** CLAP or LV2 hosting via JNI (open standards, clean C API)
3. **Phase 3:** VST3 hosting via JNI wrapper (broadest plugin availability)

---

## Web Audio Tools (Reference)

While this is a Java desktop application, web audio tools provide useful algorithm and UX references.

| Tool | Description | Relevance |
|------|-------------|-----------|
| [Tone.js](https://tonejs.github.io/) | Web Audio framework with DAW-like features | **Low** — Transport and scheduling design reference |
| [Peaks.js](https://github.com/bbc/peaks.js) | Waveform visualization UI component | **Low** — Waveform display approach reference |
| [AudioMass](https://github.com/pkalogiros/AudioMass) | Web-based audio editor | **Low** — UI/UX approach reference |
| [WadJS](https://github.com/rserota/wad) | Web Audio DAW library | **Low** — Synthesis architecture reference |

---

## Implementation Roadmap Based on Tool Analysis

### Phase 1 — Core Audio Engine
| Need | Recommended Tool | Integration |
|------|-----------------|-------------|
| Audio I/O | PortAudio or RtAudio | JNI bindings |
| Basic DSP | TarsosDSP | Direct Java dependency |
| File I/O | FFmpeg + libsndfile | JNI bindings |
| MIDI synthesis | FluidSynth | JNI bindings |
| Audio analysis | aubio | JNI bindings |

### Phase 2 — Effects and Processing
| Need | Recommended Approach | Reference |
|------|---------------------|-----------|
| EQ | Implement in Java using TarsosDSP | Equalize It, KFR |
| Compressor/Limiter | Implement in Java | JUCE DSP module |
| Reverb | Convolution reverb + algorithmic | Cloud Seed, SAF |
| Delay/Chorus/Flanger | Implement in Java | TarsosDSP, tuna |
| Time stretch | Implement or wrap library | AudioStretchy |

### Phase 3 — Advanced Features
| Need | Recommended Tool | Integration |
|------|-----------------|-------------|
| Plugin hosting (LV2/CLAP) | JUCE or direct C API | JNI bridge |
| Spatial audio | Spatial Audio Framework | JNI bindings |
| AI mastering | Matchering | Process integration |
| Source separation | Demucs | Process integration |
| Audio-to-MIDI | NeuralNote / aubio | JNI or process |

---

## References

- [Yuan-ManX/audio-development-tools](https://github.com/Yuan-ManX/audio-development-tools) — Primary source catalog
- [JUCE Framework](https://juce.com/) — Industry-standard audio development framework
- [PortAudio](http://www.portaudio.com/) — Cross-platform audio I/O
- [TarsosDSP](https://github.com/JorenSix/TarsosDSP) — Java audio processing library
- [FluidSynth](https://www.fluidsynth.org/) — SoundFont synthesizer
- [Spatial Audio Framework](https://github.com/leomccormack/Spatial_Audio_Framework) — Spatial audio library
