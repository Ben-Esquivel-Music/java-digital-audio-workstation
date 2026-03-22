# Enhancement Proposals

Enhancement proposals derived from analysis of [research resources](../research/) including mastering techniques, immersive audio mixing, open source DAW tools, audio development tools, and 43 AES conference papers.

Each enhancement includes a summary, motivation, research source citations, and a sub-task checklist for breaking the work into smaller units.

## Enhancement Index

### Immediate Priority — Core Engine

| # | Enhancement | Source Documents |
|---|-------------|-----------------|
| [001](001-tarsodsp-integration.md) | Pure Java DSP Foundation via TarsosDSP Integration | Audio Development Tools, Research README |
| [005](005-lufs-loudness-metering.md) | LUFS Loudness Metering with Platform-Specific Presets | Mastering Techniques, Research README |
| [006](006-multi-format-export.md) | Multi-Format Audio Export with Dithering and SRC | Mastering Techniques, Audio Development Tools |
| [015](015-lock-free-audio-engine.md) | Lock-Free Real-Time Audio Processing Engine | Open Source DAW Tools, Research README |

### High Priority — Core Mastering and Mixing

| # | Enhancement | Source Documents |
|---|-------------|-----------------|
| [002](002-linear-phase-midside-eq.md) | Linear-Phase and Mid/Side Parametric EQ | Mastering Techniques, AES Research Papers |
| [003](003-multiband-compressor.md) | Multiband Compressor | Mastering Techniques, Audio Development Tools |
| [004](004-lookahead-limiter-truepeak.md) | Look-Ahead Limiter with True Peak Detection | Mastering Techniques |
| [007](007-stereo-imaging-enhancements.md) | Stereo Imaging and Correlation Metering Enhancements | Mastering Techniques, AES Research Papers |
| [008](008-3d-spatial-panner.md) | 3D Spatial Panner with Automation | Immersive Audio Mixing, AES Research Papers |

### Near-Term — Enhanced Features

| # | Enhancement | Source Documents |
|---|-------------|-----------------|
| [009](009-binaural-renderer-hrtf.md) | Binaural Renderer with HRTF/SOFA Support | Immersive Audio Mixing, AES Research Papers, Audio Development Tools |
| [012](012-portaudio-jni-bindings.md) | Low-Latency Audio I/O via PortAudio JNI Bindings | Audio Development Tools, Open Source DAW Tools |
| [014](014-dawproject-format-support.md) | DAWproject Format Support for Session Interoperability | Open Source DAW Tools |
| [020](020-fluidsynth-jni-midi-rendering.md) | FluidSynth JNI Integration for MIDI Rendering | Audio Development Tools |

### Medium Priority — Spatial and Workflow

| # | Enhancement | Source Documents |
|---|-------------|-----------------|
| [010](010-ambisonic-encoding-decoding.md) | Ambisonic Encoding and Decoding (FOA/HOA) | Immersive Audio Mixing, AES Research Papers |
| [011](011-object-based-mixing.md) | Object-Based Mixing (Dolby Atmos Workflow) | Immersive Audio Mixing |
| [019](019-mastering-chain-presets-album-sequencing.md) | Mastering Chain Presets and Album Sequencing | Mastering Techniques |
| [022](022-physical-modeling-effects.md) | Physical Modeling Audio Effects (Spring Reverb, Leslie) | AES Research Papers, Audio Development Tools |

### Future — Advanced Capabilities

| # | Enhancement | Source Documents |
|---|-------------|-----------------|
| [013](013-clap-lv2-plugin-hosting.md) | CLAP/LV2 External Plugin Hosting via JNI | Audio Development Tools, Open Source DAW Tools |
| [016](016-ai-assisted-mixing-rlhf.md) | AI-Assisted Mixing with RLHF | AES Research Papers |
| [017](017-semantic-auto-eq.md) | Semantic Embedding Auto-EQ | AES Research Papers |
| [018](018-room-acoustic-simulation.md) | Room Acoustic Simulation via RoomAcoustiC++ JNI | AES Research Papers |
| [021](021-perceptual-audio-quality-metrics.md) | Perceptual Audio Quality Metrics | AES Research Papers |

## Research Source Coverage

| Research Document | Enhancements Derived |
|-------------------|---------------------|
| [Mastering Techniques](../research/mastering-techniques.md) | 001, 002, 003, 004, 005, 006, 007, 019 |
| [Immersive Audio Mixing](../research/immersive-audio-mixing.md) | 008, 009, 010, 011 |
| [Open Source DAW Tools](../research/open-source-daw-tools.md) | 014, 015 |
| [Audio Development Tools](../research/audio-development-tools.md) | 001, 006, 012, 013, 020 |
| [AES Research Papers](../research/aes-research-papers.md) | 002, 007, 008, 009, 010, 016, 017, 018, 021, 022 |
