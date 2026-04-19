---
title: "Virtual Threads for Non-Realtime Work (Import, Export, Analysis, Autosave)"
labels: ["performance", "concurrency", "virtual-threads"]
---

# Virtual Threads for Non-Realtime Work (Import, Export, Analysis, Autosave)

## Motivation

Java 26 finalizes virtual threads — lightweight, carried-on-a-small-pool-of-platform-threads threads ideal for I/O-bound, high-concurrency work. The current codebase uses platform thread pools for non-realtime work (file I/O on import/export, autosave, analysis, audio-file scanning). Swapping these to virtual threads dramatically simplifies the code (no pool sizing, no bounded queues), reduces context-switch overhead on typical workloads, and scales naturally to hundreds of concurrent I/O operations (e.g., batch export of an album).

*Audio callback threads remain platform threads* — virtual threads are emphatically *not* for realtime work (the carrier-thread semantics break deadlines).

## Goals

- Add `DawTaskRunner` abstraction in `com.benesquivelmusic.daw.core.concurrent` with `submit(Task)` routing to either virtual threads (default) or a bounded platform pool (for short CPU-bound work).
- Convert these call sites to virtual threads: `AudioFileImporter`, `WavExporter` + stem export, `AutosaveService`, `BrowserPanelController`'s background directory scans, `WaveformPeakCalculator`, `SpectrumAnalyzerEngine`.
- Audit every `CompletableFuture.supplyAsync(...)` site and explicitly choose executor (virtual for I/O, platform for CPU-bound).
- Structured concurrency: use `StructuredTaskScope` for fan-out/fan-in patterns like bundle export (story 181) where parent should fail fast on child failure.
- Monitoring: a debug view shows active virtual threads by task-name category; helps spot leaks.
- Tests: 100 concurrent imports complete correctly; cancellation of a `StructuredTaskScope` cancels all children deterministically.
- Document in `ARCHITECTURE.md`: "audio thread = platform; everything else = virtual by default."

## Non-Goals

- Migrating the audio thread to virtual threads (explicitly rejected — deadline-critical).
- Reactive-streams-style backpressure (out of scope).
- Fiber-style continuations beyond what the JDK provides.
