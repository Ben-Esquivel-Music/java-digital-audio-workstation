---
title: "Multi-Core Parallel Audio Graph Processing"
labels: ["enhancement", "audio-engine", "performance", "real-time"]
---

# Multi-Core Parallel Audio Graph Processing

## Motivation

The current render pipeline in `RenderPipeline` and `MixerEngine` processes tracks and insert chains serially on a single audio thread. On modern 8–32 core machines this leaves most of the CPU idle during heavy sessions while a single core is pegged and throws xruns. Every professional DAW distributes the audio graph across worker threads: Reaper's "Anticipative FX" work pool, Cubase's "ASIO-Guard" pool, Logic's "Processing Threads" setting (default = core count). Parallel processing is the single biggest performance win available for this codebase and unlocks larger sessions on existing hardware.

The graph structure is already amenable: tracks that share no sends or busses are independent branches, and `MixerChannel` chains are self-contained until they hit a shared bus. A dependency-aware scheduler can submit independent branches to a bounded worker pool and join at summing nodes.

## Goals

- Introduce `AudioGraphScheduler` in `com.benesquivelmusic.daw.core.audio` that inspects the `MixerEngine` routing graph and computes a per-block dependency DAG.
- Add a fixed-size `AudioWorkerPool` of `Thread.ofPlatform().daemon().priority(MAX_PRIORITY)` workers (not virtual threads — real-time predictability requires pinned platform threads). Default size = `Runtime.getRuntime().availableProcessors() - 2`, configurable via `AudioSettingsDialog`.
- Submit independent graph branches to the pool using a lock-free `SpscArrayQueue`-style dispatcher; the audio callback thread only coordinates, it does not process tracks directly.
- Re-use buffers across blocks via `AudioBufferPool` keyed by `(channelCount, blockSize)` to avoid per-block allocation.
- Fall back to single-threaded mode when block size < 64 samples (parallelism overhead exceeds benefit) or when worker pool size is 1.
- Persist worker-pool sizing in `AudioEngineSettings` and expose a live "Threads in use" meter alongside the CPU bar.
- Correctness tests: bit-exact output between single-threaded and multi-threaded renders for a session with 16 tracks + 4 busses; no deadlock when a bus is shared by every track; no priority inversion.
- Performance tests: 32-track session with 4 inserts each renders ≥ 4× faster on 8-core machine with pool enabled.

## Non-Goals

- Speculative / anticipative processing ahead of the playhead (a separate future story).
- GPU offload for DSP.
- Re-entrant graph structures (feedback loops beyond the existing send self-reference guard).
