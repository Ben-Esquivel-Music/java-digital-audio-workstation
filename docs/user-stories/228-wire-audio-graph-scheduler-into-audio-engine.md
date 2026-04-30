---
title: "Wire AudioGraphScheduler and AudioWorkerPool into AudioEngine for Multi-Core Render"
labels: ["bug", "audio-engine", "performance", "concurrency"]
---

# Wire AudioGraphScheduler and AudioWorkerPool into AudioEngine for Multi-Core Render

## Motivation

Story 125 — "Multi-Core Parallel Audio Graph Processing" — calls for the engine to dispatch independent graph branches across a bounded pool of high-priority platform threads so heavy sessions stop pegging a single core. The classes that do the work are already in place:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/AudioGraphScheduler.java` — inspects the routing graph and produces a per-block dependency DAG.
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/AudioWorkerPool.java` — fixed-size pool of `Thread.ofPlatform().daemon().priority(MAX_PRIORITY)` workers per the story's spec.
- Tests for both: `AudioGraphSchedulerTest`, `AudioWorkerPoolTest` exercise the scheduling logic and the pool's lock-free dispatch.

But:

```
$ grep -rn 'AudioGraphScheduler\|AudioWorkerPool' daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/AudioEngine.java daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/RenderPipeline.java
(no matches)
```

`AudioEngine` and `RenderPipeline` still process the entire graph serially on the audio callback thread. On the user's primary platform with a typical 8-core CPU, the multi-core scheduler that ships with the codebase is dead weight — the engine never asks it to do anything. A 32-track session with several inserts each hits xruns long before it would on a multi-threaded engine, defeating the entire performance story 125 was meant to deliver.

The integration is the small remaining step.

## Goals

- Inject `AudioGraphScheduler` and `AudioWorkerPool` into `AudioEngine` (constructor injection consistent with the story-199 DI direction) and route `RenderPipeline.renderBlock(...)` through the scheduler. The audio callback thread becomes a coordinator: submits independent graph branches to the worker pool, joins at summing nodes, returns once the master is computed.
- Worker-pool size: default `Runtime.getRuntime().availableProcessors() - 2`, configurable via `AudioEngineSettings.workerPoolSize` and exposed in `AudioSettingsDialog`. Persisted in `AudioSettingsStore`.
- Fall back to single-threaded rendering when:
  - block size < 64 samples (parallelism overhead exceeds benefit), or
  - configured pool size is 1.
- Add a "Threads in use" meter alongside the existing CPU bar in the transport / performance area; reads from `AudioWorkerPool` per-block stats.
- Correctness tests:
  - Bit-exact output between single-threaded and multi-threaded renders for a session with 16 tracks + 4 buses.
  - No deadlock when a bus is shared by every track.
  - No priority inversion (verified by JFR `jdk.ThreadStart` / `jdk.JavaMonitorWait` events during a stress run).
- Performance tests (gated under the `long-tests` profile so CI cost is bounded):
  - 32-track session × 4 inserts each renders ≥ 4× faster on an 8-core machine with the pool enabled vs disabled.
  - No allocation per block in steady state (reused per-branch buffers from `AudioBufferPool`).
- Document worker-thread real-time considerations in `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/package-info.java`: workers must respect the `@RealTimeSafe` contract, must not allocate, and must use `LockSupport.parkNanos` rather than blocking primitives.

## Non-Goals

- Speculative / anticipative processing ahead of the playhead (a separate future story).
- GPU offload for DSP.
- Dynamic worker-pool resizing during playback (size locks at stream open; restart required to change).
- Re-entrant graph structures (feedback loops beyond the existing send self-reference guard).

## Technical Notes

- Files: `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/AudioEngine.java`, `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/RenderPipeline.java` (route through scheduler), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/AudioSettingsDialog.java` (worker-pool size combo), `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/AudioSettingsStore.java` (persistence field), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (mount the threads-in-use meter).
- `AudioGraphScheduler` and `AudioWorkerPool` already exist and are tested in isolation — the integration is about composing them with the existing `AudioEngine` callback path, not changing their internals.
- The story's guidance to use *platform* (not virtual) threads is critical: virtual threads share carrier threads with non-realtime work and break audio-callback timing. Keep the existing `Thread.ofPlatform().daemon().priority(MAX_PRIORITY)` configuration in `AudioWorkerPool`.
- Reference original story: **125 — Multicore Parallel Graph Processing**.
