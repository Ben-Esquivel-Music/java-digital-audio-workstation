# Research: Open Source Audio Tools and Techniques

This directory contains research documentation analyzing open source tools and techniques for recording, mixing, and mastering audio. The findings are intended to inform future implementation decisions for this Java-based Digital Audio Workstation.

## Documents

| Document | Focus Area | Primary Source |
|----------|-----------|----------------|
| [Mastering Techniques](mastering-techniques.md) | Professional mastering workflow, EQ, dynamics, loudness standards, deliverables | [Berklee Online](https://online.berklee.edu/takenote/music-mastering-techniques-from-the-pros/) |
| [Immersive Audio Mixing](immersive-audio-mixing.md) | Spatial audio, Dolby Atmos, Ambisonics, binaural rendering, 3D mixing | [Berklee Online](https://online.berklee.edu/takenote/immersive-audio-mixing-techniques-how-to-create-spatial-mixes-in-dolby-atmos-and-3d-music/) |
| [Open Source DAW Tools](open-source-daw-tools.md) | DAW platforms, architectures, plugin systems, design patterns | [OpenDAW.org](https://opendaw.org/) |
| [Audio Development Tools](audio-development-tools.md) | Comprehensive tool catalog: DSP, synthesis, spatial audio, ML, I/O | [Yuan-ManX/audio-development-tools](https://github.com/Yuan-ManX/audio-development-tools) |
| [AES Research Papers](aes-research-papers.md) | 43 AES conference papers: spatial audio, DSP, mixing, ML, acoustics | [Audio Engineering Society](https://www.aes.org/) |

## Key Findings Summary

### Recording
- **Audio I/O:** PortAudio and RtAudio are the leading cross-platform audio I/O libraries; JNI bindings would provide low-latency recording capabilities beyond the Java Sound API
- **File Formats:** FFmpeg and libsndfile cover all major audio formats (WAV, FLAC, OGG, MP3, AAC)
- **MIDI:** FluidSynth provides SoundFont-based MIDI rendering; Java's built-in `javax.sound.midi` covers basic MIDI I/O
- **Microphone Selection:** AES research shows microphone preference varies by vocalist and phrase — no single microphone is universally optimal for a given genre ([AES: Investigating Phrase and Vocalist Dependent Microphone Preferences](aes-research-papers.md#recording-techniques))

### Mixing
- **DSP Foundation:** TarsosDSP is the only pure-Java audio processing library with practical DSP capabilities (pitch detection, effects, filters)
- **Effects Chain:** EQ, compression, reverb, delay, and stereo imaging are the essential mixing effects — algorithms are well-documented in open source tools like JUCE, Cloud Seed, and KFR
- **Spatial Audio:** The Spatial Audio Framework (SAF) provides comprehensive Ambisonics, HRTF, and 3D panning algorithms suitable for immersive mixing
- **Plugin Hosting:** CLAP and LV2 are the most practical open plugin standards for JNI integration
- **AI-Assisted Mixing:** AES research demonstrates RLHF-based adaptive mixing and differentiable mix graph reconstruction for reference-matching workflows ([AES: ML and AI in Audio](aes-research-papers.md#machine-learning-and-ai-in-audio))
- **Analog Modeling:** Differentiable DSP enables efficient analog hardware emulation with fewer parameters than neural networks; physical modeling of spring reverb and Leslie effects offers computationally efficient alternatives to convolution ([AES: Audio Effects Modeling](aes-research-papers.md#audio-effects-modeling-and-dsp))

### Mastering
- **Signal Chain:** The standard mastering chain is: gain staging → corrective EQ → compression → tonal EQ → stereo imaging → limiting → dithering
- **Loudness:** LUFS metering with platform-specific targets (Spotify −14, Apple −16) is essential for modern mastering
- **Delivery:** Multi-format export with automatic sample rate conversion and dithering is a core requirement
- **Immersive Mastering:** Dolby Atmos and Apple Spatial Audio are the primary immersive delivery formats, requiring ADM BWF export and binaural rendering
- **Intelligent EQ:** AES research presents semantic-embedding-based auto-EQ that adapts to content type, and lightweight neural EQ using 6 biquad filters for real-time use ([AES: Mixing and Mastering](aes-research-papers.md#mixing-and-mastering))
- **Audio Quality Metrics:** ODAQ dataset and CNN-based virtual listener panels enable automated perceptual quality assessment for mastering validation ([AES: Audio Quality](aes-research-papers.md#audio-quality-and-perceptual-evaluation))

### Architecture
- **Module separation** (SDK / Core / App) is the standard pattern across successful open source DAWs — this project already follows it
- **Lock-free audio processing** on the real-time thread is non-negotiable for professional-quality audio
- **DAWproject format** support enables session interoperability with other DAWs
- **Room Acoustics:** RoomAcoustiC++ is an open-source C++ library for real-time room acoustic modeling using hybrid geometric acoustics and FDN — a JNI integration candidate ([AES: Acoustics and Room Modeling](aes-research-papers.md#acoustics-and-room-modeling))

## Implementation Priority

### Immediate (Core Engine)
1. Pure Java DSP via TarsosDSP integration
2. Parametric EQ, compressor, limiter implementations
3. LUFS loudness metering
4. Multi-format audio export with dithering

### Near-Term (Enhanced Features)
1. JNI bindings for PortAudio (low-latency I/O)
2. JNI bindings for FluidSynth (MIDI rendering)
3. Stereo imager and correlation metering
4. Mastering chain presets and templates

### Future (Advanced Capabilities)
1. CLAP/LV2 plugin hosting via JNI
2. Spatial audio (3D panner, binaural renderer, Ambisonics with salient/diffuse separation)
3. Dolby Atmos / Apple Spatial Audio export
4. AI-assisted features (RLHF mixing, semantic auto-EQ, mix graph reconstruction, stem separation)
5. Room acoustic simulation via RoomAcoustiC++ JNI integration
6. Perceptual quality metrics for automated mastering validation
