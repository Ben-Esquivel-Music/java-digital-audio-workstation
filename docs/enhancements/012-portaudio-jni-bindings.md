# Enhancement: Low-Latency Audio I/O via PortAudio JNI Bindings

## Summary

Implement JNI bindings for [PortAudio](http://www.portaudio.com/) to provide low-latency, cross-platform audio input/output capabilities beyond what the Java Sound API offers. This enables professional-grade recording and playback with buffer sizes as low as 32–128 samples, critical for real-time monitoring and instrument tracking.

## Motivation

The Java Sound API (`javax.sound.sampled`) provides basic cross-platform audio I/O but suffers from high latency (typically 20–50ms+) due to Java's audio subsystem overhead and limited driver access. Professional DAWs require latencies under 10ms for real-time monitoring during recording. PortAudio is the industry-standard cross-platform C library (used by Audacity and many others) that provides direct access to native audio drivers (WASAPI/ASIO on Windows, CoreAudio on macOS, ALSA/JACK on Linux), enabling buffer sizes as low as 32 samples (~0.7ms at 44.1kHz).

## Research Sources

- [Audio Development Tools](../research/audio-development-tools.md) — Phase 1 Core Audio Engine: "Audio I/O → PortAudio → JNI bindings"
- [Audio Development Tools](../research/audio-development-tools.md) — "PortAudio: Cross-platform C audio I/O library — **High** relevance"
- [Open Source DAW Tools](../research/open-source-daw-tools.md) — Pattern #5: "PortAudio: Cross-platform C library used by Audacity and many others"
- [Research README](../research/README.md) — Near-Term #1: "JNI bindings for PortAudio (low-latency I/O)"
- [Audio Development Tools](../research/audio-development-tools.md) — Also references JAsioHost for ASIO-specific Windows support

## Sub-Tasks

- [ ] Design `NativeAudioBackend` interface in `daw-sdk` abstracting audio I/O (device enumeration, stream open/close, callback)
- [ ] Implement Java-side JNI wrapper classes for PortAudio API (Pa_Initialize, Pa_OpenStream, Pa_StartStream, Pa_StopStream, Pa_CloseStream, Pa_Terminate)
- [ ] Write C/C++ JNI native code bridging Java calls to PortAudio functions
- [ ] Implement device enumeration (list available input/output devices with capabilities)
- [ ] Implement configurable buffer size (32, 64, 128, 256, 512, 1024, 2048 samples)
- [ ] Implement configurable sample rate selection (44100, 48000, 88200, 96000, 176400, 192000 Hz)
- [ ] Implement audio callback mechanism (PortAudio callback → Java audio processing pipeline)
- [ ] Implement fallback to Java Sound API when PortAudio native library is not available
- [ ] Add latency reporting (input latency, output latency, round-trip latency)
- [ ] Build native libraries for Windows (x64), macOS (x64, aarch64), and Linux (x64)
- [ ] Package native libraries in Maven artifact with OS/arch classifier
- [ ] Add integration tests for device enumeration and stream lifecycle
- [ ] Add latency benchmark tests comparing PortAudio vs. Java Sound API
- [ ] Document native library build process and cross-compilation setup

## Affected Modules

- `daw-sdk` (new `audio/NativeAudioBackend` interface)
- `daw-core` (`audio/AudioEngine` — integrate PortAudio backend, new `audio/portaudio/` package)
- New native module or subproject for JNI C/C++ code

## Priority

**Near-Term** — Significant quality-of-life improvement for recording workflows
