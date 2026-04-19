---
title: "ZGC Tuning Profile and Buffer Pool Recycling to Eliminate Audio-Thread GC Pauses"
labels: ["performance", "audio-engine", "gc", "real-time"]
---

# ZGC Tuning Profile and Buffer Pool Recycling to Eliminate Audio-Thread GC Pauses

## Motivation

A GC pause exceeding the audio buffer period (often 2–11 ms depending on buffer size) produces an audible dropout. ZGC on Java 26 reliably produces sub-millisecond pauses, but only if the audio thread avoids allocation — allocations trigger TLAB refills and stack-walks that can still exceed the deadline. The fix is twofold: run with ZGC configured for real-time-like behavior, and eliminate allocation on the audio thread via aggressive buffer pooling.

## Goals

- Add `zgc.conf` JVM options file shipped with the app: `-XX:+UseZGC -XX:+ZGenerational -XX:ZUncommit=false -XX:+AlwaysPreTouch -Xms<sessionMem> -Xmx<sessionMem>` with documented rationale.
- Extend `DawLauncher` to write the conf file into the user's settings dir and reference it from the launcher script.
- Audit every audio-thread code path with a `@RealTimeSafe` annotation; a build-time check (reusing story 109's verification) fails if an annotated method allocates, uses reflection, or takes a lock.
- Pool all audio buffers through `AudioBufferPool` keyed by `(channelCount, blockSize, precision)`; pool all MIDI events through `MidiEventPool`; pool `XrunEvent`/`LatencyTelemetry` snapshot records through per-type ring buffers so real-time events emit without allocation.
- Add a `RealtimeAllocationDetector` debug mode that instruments allocation on the audio thread and logs any found; disabled in release builds.
- Benchmark: before/after comparison with a stress session (64 tracks, 4 inserts each, 48 kHz / 64-sample buffer); target: 99.99th-percentile callback time under buffer period.
- Document GC profile tradeoffs in `java26-setup.md`.

## Non-Goals

- Alternative collectors (Shenandoah) — ZGC generational is the target.
- JVM pinning / CPU isolation (OS-level; out of scope).
- Native JNI audio thread (stays on JVM).
