# Enhancement: Lock-Free Real-Time Audio Processing Engine

## Summary

Ensure the audio processing engine uses lock-free data structures and real-time-safe patterns on the audio callback thread. This means zero memory allocations, zero locks, zero blocking I/O, and zero system calls on the audio thread — using lock-free ring buffers for inter-thread communication and fixed-size block processing.

## Motivation

Glitch-free audio is non-negotiable for a professional DAW. Any operation that blocks the audio thread (memory allocation, lock contention, I/O, garbage collection) causes audible clicks, pops, or dropouts. Every successful open-source DAW (Ardour, Audacity, LMMS) enforces strict real-time safety on the audio thread. The Java environment adds additional challenges (GC pauses, object allocation) that must be carefully managed through pre-allocation, object pooling, and lock-free algorithms.

## Research Sources

- [Open Source DAW Tools](../research/open-source-daw-tools.md) — Pattern #3: "Lock-free audio thread: Critical for glitch-free audio; no allocations or locks on the audio thread"
- [Open Source DAW Tools](../research/open-source-daw-tools.md) — Pattern #3: "Ring buffers for communication between audio thread and UI thread"
- [Open Source DAW Tools](../research/open-source-daw-tools.md) — Pattern #3: "Fixed-size block processing: Process audio in fixed-size buffers (e.g., 128, 256, 512 samples)"
- [Research README](../research/README.md) — Architecture: "Lock-free audio processing on the real-time thread is non-negotiable for professional-quality audio"

## Sub-Tasks

- [ ] Audit existing `AudioEngine` for real-time safety violations (allocations, locks, blocking calls on audio thread)
- [ ] Implement lock-free SPSC (Single-Producer Single-Consumer) ring buffer for audio thread ↔ UI thread communication
- [ ] Implement lock-free MPSC (Multi-Producer Single-Consumer) ring buffer for parameter change messages
- [ ] Pre-allocate all `AudioBuffer` and `NativeAudioBuffer` instances at engine initialization (pool-based)
- [ ] Ensure `EffectsChain` processing uses pre-allocated intermediate buffers (no per-block allocation)
- [ ] Implement real-time-safe parameter smoothing (avoid clicks when parameters change during processing)
- [ ] Ensure `Mixer` and `MixerChannel` processing paths are allocation-free
- [ ] Set audio thread to real-time priority via `Thread.ofPlatform().priority(Thread.MAX_PRIORITY)` or OS-specific calls
- [ ] Implement audio thread watchdog to detect and report deadline misses (buffer underruns)
- [ ] Add `@RealTimeSafe` annotation for documenting which methods are safe to call from the audio thread
- [ ] Profile GC behavior during audio processing and minimize GC pauses (consider ZGC or Shenandoah with `--XX:+UseZGC`)
- [ ] Add unit tests for lock-free ring buffer correctness under concurrent access
- [ ] Add stress tests for audio engine under high load (many tracks, many effects, small buffer sizes)
- [ ] Document real-time safety guidelines for plugin developers in `daw-sdk`

## Affected Modules

- `daw-core` (`audio/AudioEngine`, `audio/AudioBuffer`, `audio/NativeAudioBuffer`, `audio/EffectsChain`, `mixer/Mixer`)
- `daw-sdk` (new `@RealTimeSafe` annotation, documentation)

## Priority

**High** — Foundational quality requirement for professional audio
